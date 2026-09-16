package com.localagent.ollama;

import com.localagent.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Ollama 运行环境探测与首次引导所需的纯逻辑/同步 IO 工具集。
 *
 * 设计思路：
 * - 检测分两层：①可执行文件是否存在（不依赖当前进程可能过期的 PATH，直接探测
 *   官方默认安装目录）；②回环服务 /api/version 是否可连（安装了但没启动也算未就绪）；
 * - 模型推荐只按物理内存分档（Q4 量化模型运行内存约为下载体积的 1.3~1.6 倍，需给
 *   系统留出余量），中文场景选用 Ollama 官方仓库的 qwen2.5 系列，视觉候选为
 *   qwen2.5vl 系列；
 * - 拉取进度解析 {@code ollama pull} 的 NDJSON 输出，供 Swing 进度条实时展示。
 *
 * 本类无 UI 依赖、无状态，所有判定均可单元回归（路径探测/选型/进度解析接受
 * 注入参数，不直接读全局环境）。
 * 创建时间：2026-09-16，核心用途：首次启动引导（安装 Ollama + 下载合适模型）。
 */
public final class OllamaEnv {
    private OllamaEnv() {}

    /** Ollama 官方 Windows 安装器固定下载地址（HTTPS，仅允许此地址）。 */
    public static final String SETUP_URL = "https://ollama.com/download/OllamaSetup.exe";

    /** 安装器官方来源展示文本（用于用户授权对话框）。 */
    public static final String SETUP_SOURCE = "ollama.com 官方安装器";

    /**
     * 候选模型规格。
     *
     * @param tag        ollama pull 使用的标签（文本模型）
     * @param visionTag  同级视觉模型标签；null 表示该档位无视觉候选
     * @param minRamGb   建议的最小物理内存（GB，含系统余量后的保守阈值）
     * @param sizeText   下载体积展示文本（来自 Ollama 官方库标注）
     * @param label      档位中文说明
     */
    public record ModelOption(String tag, String visionTag, int minRamGb, String sizeText, String label) {}

    /**
     * 模型档位表（按 minRamGb 升序）。体积数据取自 https://ollama.com/library/qwen2.5
     * 与 qwen2.5vl 官方页面（2025 年标注）。
     */
    private static final List<ModelOption> OPTIONS = List.of(
            new ModelOption("qwen2.5:0.5b", null, 0, "约 398MB", "极速轻量（老旧/低内存电脑可用，回答质量有限）"),
            new ModelOption("qwen2.5:1.5b", null, 4, "约 986MB", "轻量（4GB 内存，日常问答可用）"),
            new ModelOption("qwen2.5:3b", "qwen2.5vl:3b", 8, "约 1.9GB", "均衡（8GB 内存，速度与质量兼顾）"),
            new ModelOption("qwen2.5:7b", "qwen2.5vl:7b", 16, "约 4.7GB", "推荐（16GB 内存，质量较好，支持工具调用）"),
            new ModelOption("qwen2.5:14b", null, 32, "约 9.0GB", "高质量（32GB 以上内存，回答更细致）"));

    /**
     * 返回全部可选档位（升序）。
     * @return 不可变档位列表，调用方不应修改
     */
    public static List<ModelOption> options() { return OPTIONS; }

    /**
     * 按物理内存推荐档位：取 minRamGb 不超过总内存的最高档位。
     * @param totalRamBytes 物理内存总字节数（允许 0/负值，此时按最低档返回）
     * @return 最接近硬件能力上限的档位（永不为 null）
     */
    public static ModelOption recommend(long totalRamBytes) {
        double gb = totalRamBytes / (1024.0 * 1024 * 1024);
        ModelOption picked = OPTIONS.get(0);
        for (ModelOption o : OPTIONS) if (gb + 1e-9 >= o.minRamGb()) picked = o;
        return picked;
    }

    /**
     * 读取物理内存总量。
     * @return 物理内存字节数；读取失败返回 0（调用方按最低档推荐）
     */
    public static long totalPhysicalMemory() {
        try {
            com.sun.management.OperatingSystemMXBean bean =
                    (com.sun.management.OperatingSystemMXBean)
                            java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            return bean.getTotalMemorySize();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 依据注入的环境变量表生成 ollama.exe 候选绝对路径（纯函数，便于回归）。
     * 依次覆盖官方用户态安装目录、Program Files、PATH 解析。
     * @param env 环境变量映射（通常传 System.getenv()；测试可注入任意表）
     * @return 去重后的候选路径列表（不做存在性判断）；PATH 中不含分隔符时返回空 PATH 段
     */
    public static List<Path> candidateExePaths(Map<String, String> env) {
        Set<Path> out = new LinkedHashSet<>();
        addIfPresent(out, env.get("LOCALAPPDATA"), "Programs", "Ollama", "ollama.exe");
        addIfPresent(out, env.get("ProgramFiles"), "Ollama", "ollama.exe");
        addIfPresent(out, env.get("ProgramFiles(x86)"), "Ollama", "ollama.exe");
        String pathEnv = env.get("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (dir == null || dir.isBlank()) continue;
                try { out.add(Paths.get(dir.trim(), "ollama.exe").normalize()); }
                catch (Exception ignored) { /* PATH 非法段跳过 */ }
            }
        }
        return new ArrayList<>(out);
    }

    /**
     * 拼接 根目录 + 子段 加入集合（根为空时跳过）。
     * @param out 目标集合
     * @param root 根目录环境变量值，可为 null
     * @param parts 相对子路径
     */
    private static void addIfPresent(Set<Path> out, String root, String... parts) {
        if (root == null || root.isBlank()) return;
        try {
            Path p = Paths.get(root.trim(), parts).normalize();
            out.add(p);
        } catch (Exception ignored) { /* 非法环境变量值跳过 */ }
    }

    /**
     * 返回候选列表中第一个真实存在的 ollama.exe。
     * @param candidates candidateExePaths 的返回值
     * @return 首个存在的可执行文件路径；全部不存在返回 null
     */
    public static Path firstExisting(List<Path> candidates) {
        if (candidates != null) for (Path p : candidates) {
            try { if (p != null && Files.isRegularFile(p)) return p; } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * 探测本机已安装的 ollama.exe（便捷封装，读真实环境变量）。
     * @return ollama.exe 绝对路径；未安装返回 null
     */
    public static Path findInstalledExe() {
        return firstExisting(candidateExePaths(System.getenv()));
    }

    /**
     * 定位与 ollama.exe 同目录的桌面端 "ollama app.exe"（系统托盘 + 后台服务）。
     * @param ollamaExe ollama.exe 路径；为 null 时返回 null
     * @return ollama app.exe 路径（不做存在性保证，调用方自行 isRegularFile 判断）
     */
    public static Path desktopAppOf(Path ollamaExe) {
        return ollamaExe == null ? null : ollamaExe.resolveSibling("ollama app.exe");
    }

    /**
     * 探测回环 Ollama 服务是否就绪（GET /api/version 返回 2xx）。
     * @param baseUrl 服务根地址（调用前应已通过 OllamaClient.assertLoopback 约束）
     * @return true=服务可连且返回 2xx；false=连接失败/超时/非 2xx
     */
    public static boolean serviceUp(String baseUrl) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .followRedirects(HttpClient.Redirect.NEVER).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/version"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.statusCode() / 100 == 2;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 查询本地已存在模型标签集合（GET /api/tags）。
     * @param baseUrl 服务根地址
     * @return 标签名集合（如 qwen2.5:7b，可能含 @sha256 形式，调用方按前缀匹配）；服务异常返回空集
     */
    public static Set<String> localTags(String baseUrl) {
        Set<String> tags = new LinkedHashSet<>();
        try {
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/tags"))
                    .timeout(Duration.ofSeconds(5)).GET().build();
            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() / 100 != 2) return tags;
            JsonNode models = Json.mapper().readTree(resp.body()).path("models");
            if (models.isArray()) for (JsonNode m : models) tags.add(m.path("name").asText(""));
            tags.remove("");
        } catch (Exception ignored) { /* 异常按空集处理 */ }
        return tags;
    }

    /**
     * 判断标签是否已存在于本地（兼容 name 带 @sha256 摘要的形式）。
     * @param tags localTags 返回集合
     * @param tag 目标标签，如 qwen2.5:7b
     * @return true=已有同标签模型
     */
    public static boolean hasTag(Set<String> tags, String tag) {
        if (tags == null || tag == null) return false;
        String base = tag.contains(":") ? tag : tag + ":latest";
        for (String t : tags) {
            String head = t.contains("@") ? t.substring(0, t.indexOf('@')) : t;
            if (head.equalsIgnoreCase(tag) || head.equalsIgnoreCase(base)) return true;
        }
        return false;
    }

    /**
     * 拉取进度快照（permille 为千分比 0..1000；-1 表示不确定进度的中间态）。
     *
     * @param permille 完成千分比；-1=不确定（verify/解压等无 total 的阶段）
     * @param status   原始状态短语（pulling/verifying/writing/success 等）
     * @param done     是否到达 success 终态
     */
    public record PullState(int permille, String status, boolean done) {}

    /**
     * 解析 {@code ollama pull} 的单行 NDJSON 进度输出（纯函数）。
     * @param line 一行 JSON（允许 null/空白/非 JSON，此时返回不确定态）
     * @return 进度快照；无法解析时 permille=-1、done=false
     */
    public static PullState parsePullLine(String line) {
        if (line == null || line.isBlank()) return new PullState(-1, "", false);
        try {
            JsonNode n = Json.mapper().readTree(line);
            String status = n.path("status").asText("");
            if ("success".equalsIgnoreCase(status)) return new PullState(1000, status, true);
            long total = n.path("total").asLong(0);
            long completed = n.path("completed").asLong(0);
            if (total > 0 && completed >= 0) {
                int permille = (int) Math.max(0, Math.min(1000, completed * 1000 / total));
                return new PullState(permille, status, false);
            }
            return new PullState(-1, status, false);
        } catch (Exception e) {
            return new PullState(-1, "", false);
        }
    }

    /**
     * 解析 PowerShell Get-AuthenticodeSignature 的 "Status|Subject" 输出，
     * 判定安装器是否为 Ollama 官方有效签名（纯函数，便于回归）。
     * @param output 形如 "Valid|CN=Ollama, Inc., O=Ollama, Inc..." 的脚本输出（大小写不敏感）
     * @return true=签名有效且证书主体含 Ollama；其余（未签名/无效/非 Ollama）一律 false
     */
    public static boolean isTrustedOllamaSignature(String output) {
        if (output == null) return false;
        String trimmed = output.trim();
        int bar = trimmed.indexOf('|');
        String status = (bar >= 0 ? trimmed.substring(0, bar) : trimmed).trim();
        String subject = bar >= 0 ? trimmed.substring(bar + 1) : "";
        return status.equalsIgnoreCase("Valid")
                && subject.toLowerCase(Locale.ROOT).contains("ollama");
    }

    /**
     * 构造安装器数字签名校验的 PowerShell 命令参数（参数数组，不经 shell）。
     * @param installerPath 安装器绝对路径
     * @return powershell.exe 的完整参数列表
     */
    public static List<String> signatureCheckCommand(Path installerPath) {
        String p = installerPath.toString().replace("'", "''");
        String script = "$s=Get-AuthenticodeSignature -LiteralPath '" + p + "';"
                + "Write-Output ($s.Status.ToString() + '|' + $s.SignerCertificate.Subject)";
        return List.of("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", script);
    }

    /**
     * 把字节数格式化为中文可读体积。
     * @param bytes 字节数（允许负值，按 0 处理）
     * @return 形如 "16.0 GB" / "512 MB" 的文本
     */
    public static String humanSize(long bytes) {
        double v = Math.max(0, bytes);
        if (v >= 1024L * 1024 * 1024) return String.format(Locale.ROOT, "%.1f GB", v / (1024 * 1024 * 1024));
        if (v >= 1024L * 1024) return String.format(Locale.ROOT, "%.0f MB", v / (1024 * 1024));
        return String.format(Locale.ROOT, "%.0f KB", v / 1024);
    }

    /**
     * HTTP 下载（仅用于用户明确授权的安装器获取）：跟随重定向（ollama.com 会
     * 302 到官方 CDN/GitHub Release），流式写盘并按已写字节实时汇报进度。
     * @param url      下载地址（调用方必须保证为固定官方 HTTPS 地址）
     * @param target   落盘路径（父目录需已存在）
     * @param progress 进度回调（0..1；响应无 Content-Length 时回传 -1 表示不确定）；允许 null
     * @throws IOException 网络错误/非 2xx/写盘失败时抛出；失败会尝试删除半成品文件
     * @throws InterruptedException 线程在下载等待中被中断时抛出
     */
    public static void download(String url, Path target, java.util.function.DoubleConsumer progress)
            throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(20)).build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(30)).GET().build();
        HttpResponse<java.io.InputStream> resp =
                client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() / 100 != 2) {
            try { resp.body().close(); } catch (Exception ignored) {}
            throw new IOException("下载失败，HTTP 状态码 " + resp.statusCode());
        }
        long total = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        try (java.io.InputStream in = resp.body();
             java.io.OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                done += n;
                if (progress != null) progress.accept(total > 0 ? Math.min(1.0, (double) done / total) : -1);
            }
        } catch (IOException e) {
            try { Files.deleteIfExists(target); } catch (Exception ignored) {}
            throw e;
        }
    }
}
