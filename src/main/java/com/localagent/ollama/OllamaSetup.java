package com.localagent.ollama;

import com.localagent.config.Config;
import com.localagent.util.Json;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Ollama 首次引导编排：安装检测 →（缺安装时）下载并校验官方安装器 →
 * 启动后台服务 → 按硬件档位拉取模型 → 写回配置。
 *
 * 安全约束：
 * - 安装器仅来自固定 HTTPS 地址 {@link OllamaEnv#SETUP_URL}，下载后必须通过
 *   Windows Authenticode 签名校验（Status=Valid 且证书主体含 Ollama）才允许执行，
 *   防止下载链路被劫持后运行伪造安装器；
 * - 所有外部进程一律 ProcessBuilder 参数数组启动，不经 shell；
 * - 整个流程需要用户在 UI 中明确授权（见 OllamaSetupDialog），本类不自行弹窗执行。
 *
 * 创建时间：2026-09-16，核心用途：首次启动时把"装 Ollama + 下模型"闭环到可用状态。
 */
public final class OllamaSetup {
    private OllamaSetup() {}

    /** 引导阶段（供 UI 决定文案与模型选择区是否可用）。 */
    public enum Stage {
        /** 未安装 ollama.exe：需要下载并运行官方安装器。 */
        NEED_INSTALL,
        /** 已安装但回环服务未运行：需要启动后台服务。 */
        NEED_SERVICE,
        /** 服务正常但本地没有任何模型：仅需拉取模型。 */
        NEED_MODEL,
        /** 已就绪：服务在线且本地已有模型。 */
        READY
    }

    /**
     * 进度监听回调（全部由后台工作线程调用，UI 实现需自行切回 EDT）。
     */
    public interface Listener {
        /**
         * 更新当前步骤说明。
         * @param text 步骤文本（非空）
         */
        void stage(String text);

        /**
         * 更新进度。
         * @param ratio 0..1 确定进度；-1 表示不确定进度（忙碌中无百分比）
         * @param detail 细节文本（如 "已下载 320MB / 800MB"），可为 null
         */
        void progress(double ratio, String detail);

        /**
         * 是否被用户取消（工作线程在每个等待点轮询）。
         * @return true=尽快中止当前步骤并抛出 InterruptedException
         */
        boolean cancelled();

        /**
         * 便捷的取消检查点：已取消则立即抛 InterruptedException。
         * @throws InterruptedException cancelled() 返回 true 时抛出
         */
        default void throwIfCancelled() throws InterruptedException {
            if (cancelled()) throw new InterruptedException("用户取消了引导操作。");
        }
    }

    /**
     * 判定当前引导阶段（纯探测，不做任何修改）。
     * @param baseUrl 配置中的 Ollama 回环地址
     * @return 引导阶段枚举，永不为 null
     */
    public static Stage detect(String baseUrl) {
        if (OllamaEnv.findInstalledExe() == null) return Stage.NEED_INSTALL;
        if (!OllamaEnv.serviceUp(baseUrl)) return Stage.NEED_SERVICE;
        return OllamaEnv.localTags(baseUrl).isEmpty() ? Stage.NEED_MODEL : Stage.READY;
    }

    /**
     * 全流程执行：确保服务在线且指定模型已拉取。已完成的步骤自动跳过（可重复调用）。
     * 执行逻辑：探测 ollama.exe →（缺失）下载+签名校验+运行安装器并等待服务 →
     * （服务下线）拉起桌面端/serve 并轮询就绪 → 校验模型标签存在性，缺失则
     * {@code ollama pull} 并实时回报进度 → 写回 Config.model。
     * @param tag 目标模型标签（如 qwen2.5:7b 或视觉版 qwen2.5vl:7b）
     * @param baseUrl 配置中的 Ollama 回环地址
     * @param l 进度/取消监听，不允许为 null
     * @throws Exception 用户取消（InterruptedException）、下载/签名/安装/拉取失败时抛出，
     *                   异常消息可直接向用户展示
     */
    public static void ensureReady(String tag, String baseUrl, Listener l) throws Exception {
        // 1) 安装
        Path exe = OllamaEnv.findInstalledExe();
        if (exe == null) {
            installOllama(l);
            exe = waitForExe(l);
            if (exe == null) throw new IllegalStateException("安装已结束，但未在默认目录找到 ollama.exe。");
        }

        // 2) 服务
        if (!OllamaEnv.serviceUp(baseUrl)) {
            startService(exe, l);
            if (!pollService(baseUrl, 60, l))
                throw new IllegalStateException("Ollama 服务在 60 秒内未就绪，请从开始菜单启动 Ollama 后重试。");
        }

        // 3) 模型
        Set<String> tags = OllamaEnv.localTags(baseUrl);
        if (!OllamaEnv.hasTag(tags, tag)) {
            pullModel(exe, tag, l);
            // pull 成功后再次确认，避免进程退出码 0 但标签实际不可用
            if (!OllamaEnv.hasTag(OllamaEnv.localTags(baseUrl), tag))
                throw new IllegalStateException("拉取命令已结束，但模型列表中仍找不到 " + tag + "。");
        }

        ObjectNode patch = Json.mapper().createObjectNode();
        patch.put("model", tag);
        Config.set(patch);
    }

    /**
     * 下载、校验并启动官方安装器。
     * 执行逻辑：临时目录下载固定 URL → Authenticode 签名校验 → 启动 GUI 安装器
     * （用户按提示点完）→ 以服务上线作为安装完成信号（不依赖安装器退出码）。
     * @param l 进度/取消监听
     * @throws Exception 下载失败、签名不可信、用户取消或安装器无法启动时抛出
     */
    private static void installOllama(Listener l) throws Exception {
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "local-agent-setup");
        Files.createDirectories(dir);
        Path installer = dir.resolve("OllamaSetup.exe");

        l.stage("正在从 " + OllamaEnv.SETUP_SOURCE + " 下载安装器…");
        l.progress(0, "准备连接 ollama.com");
        try {
            OllamaEnv.download(OllamaEnv.SETUP_URL, installer, ratio ->
                    l.progress(ratio, ratio < 0 ? "下载中…" : "已下载 " + Math.round(ratio * 100) + "%"));
        } catch (InterruptedException ie) {
            throw ie;
        } catch (Exception dl) {
            // 干净机器上最常见的失败是网络不通/受限：把英文底层异常翻译成可执行的中文指引，
            // 让用户知道可以走「手动下载/说明」而不是对着一串技术报错无从下手
            throw new IllegalStateException("无法从 ollama.com 下载安装器（" + networkReason(dl)
                    + "）。请检查网络连接，或点击「手动下载/说明」用浏览器自行下载安装 Ollama 后重启本程序。", dl);
        }
        l.throwIfCancelled();

        l.stage("正在校验安装器数字签名…");
        l.progress(-1, "验证发行者为 Ollama 官方");
        String sigOut = runShort(OllamaEnv.signatureCheckCommand(installer), 60);
        if (!OllamaEnv.isTrustedOllamaSignature(sigOut))
            throw new IllegalStateException("安装器签名校验未通过（输出：" + sigOut.trim() + "），已中止以防运行伪造文件。"
                    + "请改用浏览器打开 https://ollama.com 手动下载。");

        l.stage("正在启动 Ollama 官方安装器，请在弹出的安装窗口中完成安装…");
        l.progress(-1, "等待安装完成");
        Process p = new ProcessBuilder(installer.toString()).redirectErrorStream(true).start();
        // 安装完成的可靠信号是服务/文件出现而非安装器退出码（安装器可能在收尾前返回）
        if (!pollService(Config.getString("baseUrl", "http://127.0.0.1:11434"), 600, l))
            throw new IllegalStateException("等待 Ollama 安装完成超时（10 分钟）。如已安装完毕，请重启本程序。");
        p.destroy();
    }

    /**
     * 等待 ollama.exe 在默认安装位置出现（安装器先落文件后启服务）。
     * @param l 取消监听
     * @return 出现后的可执行路径；用户取消返回中断异常；超时返回 null
     * @throws InterruptedException 用户取消时抛出
     */
    private static Path waitForExe(Listener l) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            l.throwIfCancelled();
            Path exe = OllamaEnv.findInstalledExe();
            if (exe != null) return exe;
            Thread.sleep(500);
        }
        return null;
    }

    /**
     * 启动 Ollama 后台服务：优先桌面端 "ollama app.exe"（托盘+服务，官方默认形态），
     * 不存在时回退 {@code ollama serve} 子进程（输出丢弃到临时日志）。
     * @param exe ollama.exe 路径
     * @param l 进度监听
     * @throws Exception 两种启动方式都无法拉起进程时抛出
     */
    private static void startService(Path exe, Listener l) throws Exception {
        l.stage("正在启动 Ollama 后台服务…");
        Path app = OllamaEnv.desktopAppOf(exe);
        if (app != null && Files.isRegularFile(app)) {
            try {
                new ProcessBuilder(app.toString()).redirectErrorStream(true).start();
                return;
            } catch (Exception e) {
                // 桌面端启动失败则落到 serve 兜底
            }
        }
        Path log = Paths.get(System.getProperty("java.io.tmpdir"), "local-agent-setup", "ollama-serve.log");
        Files.createDirectories(log.getParent());
        try {
            new ProcessBuilder(exe.toString(), "serve")
                    .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        } catch (Exception e) {
            throw new IllegalStateException("无法启动 Ollama 服务：" + e.getMessage(), e);
        }
    }

    /**
     * 轮询回环服务直到就绪或超时。
     * @param baseUrl 服务地址
     * @param maxSeconds 最长等待秒数
     * @param l 取消监听
     * @return true=就绪；false=超时；用户取消抛 InterruptedException
     * @throws InterruptedException 等待睡眠被中断/用户取消时抛出
     */
    private static boolean pollService(String baseUrl, int maxSeconds, Listener l) throws InterruptedException {
        for (int i = 0; i < maxSeconds * 2; i++) {
            l.throwIfCancelled();
            if (OllamaEnv.serviceUp(baseUrl)) return true;
            Thread.sleep(500);
        }
        return false;
    }

    /**
     * 执行 {@code ollama pull <tag>} 并逐行解析进度回报。
     * @param exe ollama.exe 路径
     * @param tag 模型标签
     * @param l 进度/取消监听
     * @throws Exception 进程启动失败、退出码非 0 或用户取消时抛出
     */
    private static void pullModel(Path exe, String tag, Listener l) throws Exception {
        l.stage("正在下载模型 " + tag + "（取决于网速，可能需要数分钟到数十分钟）…");
        l.progress(0, "连接模型仓库");
        ProcessBuilder pb = new ProcessBuilder(exe.toString(), "pull", tag).redirectErrorStream(true);
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (l.cancelled()) {
                    p.destroyForcibly();
                    throw new InterruptedException("用户取消了模型下载。");
                }
                OllamaEnv.PullState st = OllamaEnv.parsePullLine(line);
                if (st.done()) { l.progress(1.0, "模型写入完成"); break; }
                if (st.permille() >= 0) l.progress(st.permille() / 1000.0, humanPull(st));
                else if (st.status() != null && !st.status().isBlank()) l.progress(-1, st.status());
            }
        }
        boolean finished = p.waitFor(30, TimeUnit.MINUTES);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("模型拉取超过 30 分钟无响应，已中止。可稍后重试（支持断点续传）。");
        }
        if (p.exitValue() != 0)
            throw new IllegalStateException("ollama pull 失败（退出码 " + p.exitValue()
                    + "）。请检查网络后重试（模型仓库较大，访问受限时也会失败；已下载部分支持断点续传）。");
    }

    /**
     * 沿异常因果链提取首个与网络相关的根因，归类为简短中文短语。
     * @param t 下载过程抛出的异常，允许为 null
     * @return 中文原因短语（连接失败/超时/证书问题/HTTP 错误/下载被中断）；无法归类时给通用文案
     */
    private static String networkReason(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String n = c.getClass().getSimpleName();
            String m = c.getMessage() == null ? "" : c.getMessage();
            if (c instanceof java.net.UnknownHostException) return "无法解析域名（DNS 失败或无网络连接）";
            if (c instanceof java.net.ConnectException) return "连接被拒绝或网络不可达";
            if (c instanceof java.net.SocketTimeoutException
                    || c instanceof java.net.http.HttpTimeoutException) return "连接超时";
            if (c instanceof javax.net.ssl.SSLException
                    || c instanceof java.security.cert.CertificateException) return "TLS 证书校验失败";
            if (m.startsWith("下载失败，HTTP")) return m;
            if (n.contains("ConnectException") || n.contains("UnknownHost")) return "网络连接失败";
        }
        return "网络异常";
    }

    /**
     * 把 pull 的状态短语翻译为中文细节。
     * @param st 进度快照
     * @return 中文细节文本（含百分比）
     */
    private static String humanPull(OllamaEnv.PullState st) {
        String s = st.status() == null ? "" : st.status();
        String zh;
        if (s.startsWith("pulling")) zh = "下载模型文件";
        else if (s.startsWith("verifying")) zh = "校验完整性";
        else if (s.startsWith("writing")) zh = "写入本地模型库";
        else zh = s.isBlank() ? "处理中" : s;
        return zh + " " + (st.permille() / 10) + "%";
    }

    /**
     * 执行短时外部命令并收集全部输出（参数数组，不经 shell）。
     * @param command 完整命令参数列表
     * @param timeoutSeconds 最长等待秒数
     * @return 标准输出+错误输出合并文本
     * @throws Exception 启动失败或超时（强杀进程）时抛出
     */
    private static String runShort(List<String> command, int timeoutSeconds) throws Exception {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        String out;
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            out = sb.toString();
        }
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("签名校验命令执行超时。");
        }
        return out;
    }

    /**
     * 持久化"引导已完成/已跳过"标记，避免每次启动都弹窗。
     */
    public static void markDone() {
        ObjectNode patch = Json.mapper().createObjectNode();
        patch.put("ollamaBootstrapDone", true);
        Config.set(patch);
    }
}
