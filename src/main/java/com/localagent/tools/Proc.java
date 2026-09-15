package com.localagent.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 子进程执行辅助（移植 tools.js pwsh/run_command 的执行语义）。
 *
 * 安全要点：
 * - 全部走 ProcessBuilder 参数数组（等价 shell:false），shell 元字符不会被二次解释；
 * - 子进程环境注入失效代理变量（纵深防御，仅影响遵循代理的程序）；
 * - 输出由独立线程实时排空（修复"先 waitFor 再读管道"在输出超过约 64KB 时的互锁挂死），
 *   并设单流 1MiB 捕获上限防止失控输出撑爆内存；
 * - 超时强杀后等待排空线程收尾，不留僵尸句柄。
 *
 * 创建时间：2026-09-15，核心用途：为本机助手全部子进程调用提供统一、防挂死的执行通道。
 */
public final class Proc {

    /** 单条流（stdout/stderr 各自）最大捕获字节数，超出部分丢弃并追加截断标记。 */
    private static final int MAX_CAPTURE_BYTES = 1 << 20;

    private Proc() {}

    /**
     * 失效代理环境：仅对遵循 HTTP_PROXY 的程序有效，属纵深防御。
     * @return 以当前进程环境为底本、覆盖为指向 127.0.0.1:9 死代理的环境映射
     */
    public static Map<String, String> deadProxyEnv() {
        Map<String, String> env = new HashMap<>(System.getenv());
        String dead = "http://127.0.0.1:9";
        env.put("HTTP_PROXY", dead);
        env.put("HTTPS_PROXY", dead);
        env.put("ALL_PROXY", dead);
        env.put("http_proxy", dead);
        env.put("https_proxy", dead);
        env.put("all_proxy", dead);
        env.put("NO_PROXY", "");
        env.put("no_proxy", "");
        return env;
    }

    /**
     * 子进程执行结果。
     * @param code 退出码
     * @param out  标准输出（UTF-8，单流超 1MiB 时尾部带截断标记）
     * @param err  标准错误（UTF-8，规则同 out）
     */
    public record Result(int code, String out, String err) {}

    /**
     * 执行外部程序（不经 shell）。
     * 执行逻辑：启动子进程后立即为 stdout/stderr 各起一个守护线程实时排空，
     * 再限时等待进程退出；超时时强杀进程并等待排空线程收尾。
     * @param program    程序名或路径
     * @param argv       参数数组（可为 null）
     * @param cwd        工作目录（null=用户目录）
     * @param timeoutMs  超时毫秒；超时抛 IOException 并强杀子进程
     * @param stdinText  需要写入子进程 stdin 的文本（null 表示不写）
     * @return 退出码与双路输出
     * @throws IOException          程序启动失败、执行超时或流被截断到不可读状态时抛出
     * @throws InterruptedException 等待进程/排空线程期间被中断时抛出（中断标志会被恢复）
     */
    public static Result exec(String program, List<String> argv, String cwd,
                              long timeoutMs, String stdinText) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(program);
        if (argv != null) cmd.addAll(argv);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear();
        pb.environment().putAll(deadProxyEnv());
        if (cwd != null) pb.directory(new java.io.File(cwd));
        Process child = pb.start();

        // 先启动排空线程再写 stdin/等待，杜绝管道缓冲写满后父子进程互锁
        StreamGobbler outG = new StreamGobbler(child.getInputStream(), "proc-stdout");
        StreamGobbler errG = new StreamGobbler(child.getErrorStream(), "proc-stderr");
        outG.start();
        errG.start();

        try {
            if (stdinText != null) {
                try (OutputStream os = child.getOutputStream()) {
                    os.write((stdinText + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            boolean finished = child.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                child.destroyForcibly();
                child.waitFor(2, TimeUnit.SECONDS);
                throw new IOException("执行超时（" + timeoutMs + "ms），已终止进程：" + program);
            }
            // 进程退出后管道会很快 EOF；留 2 秒收尾余量，防止读到半截多字节字符
            outG.join(2_000L);
            errG.join(2_000L);
            if (outG.ioError != null) throw outG.ioError;
            return new Result(child.exitValue(), outG.text(), errG.text());
        } finally {
            // 任何退出路径都确保进程不残留
            if (child.isAlive()) child.destroyForcibly();
        }
    }

    /**
     * PowerShell 只读查询（内部工具使用，非 run_command 用户通道）。
     * @param script     PowerShell 脚本文本
     * @param timeoutSec 超时秒
     * @return 去首尾空白的标准输出；任何异常一律返回空串（调用方按"取不到"处理）
     */
    public static String pwsh(String script, int timeoutSec) {
        try {
            Result r = exec("powershell.exe",
                    List.of("-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-Command", script),
                    null, timeoutSec * 1000L, null);
            return (r.out() == null ? "" : r.out()).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 独立线程排空一条子进程输出流。
     * 设计思路：边读边累积到带 1MiB 上限的缓冲区，既消除管道互锁又防止失控输出 OOM；
     * 到达上限后继续排空（丢弃后续字节），保证子进程不会因管道堵塞再次挂死。
     */
    private static final class StreamGobbler extends Thread {
        /** 被排空的流。 */
        private final InputStream in;
        /** 累积缓冲（达到上限后停止写入但继续读取）。 */
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        /** 读取过程中遇到的 IO 异常（供主线程抛出）。 */
        private volatile IOException ioError;
        /** 是否已因超过上限截断。 */
        private volatile boolean truncated;

        /**
         * @param in   子进程输出流
         * @param name 线程名（便于线程转储识别）
         */
        StreamGobbler(InputStream in, String name) {
            super(name);
            this.in = in;
            setDaemon(true);
        }

        /** 持续读取直到 EOF；不抛出，异常记录到 {@link #ioError}。 */
        @Override
        public void run() {
            byte[] chunk = new byte[4096];
            try (in) {
                int n;
                while ((n = in.read(chunk)) != -1) {
                    if (buf.size() < MAX_CAPTURE_BYTES) {
                        int allowed = Math.min(n, MAX_CAPTURE_BYTES - buf.size());
                        buf.write(chunk, 0, allowed);
                        if (allowed < n) truncated = true;
                    } else {
                        truncated = true;
                    }
                }
            } catch (IOException e) {
                ioError = e;
            }
        }

        /**
         * 把已捕获字节解码为文本。
         * @return UTF-8 文本；发生截断时追加中文标记
         */
        String text() {
            String s = buf.toString(StandardCharsets.UTF_8);
            return truncated ? s + "\n…（输出超过 " + (MAX_CAPTURE_BYTES >> 10) + " KiB 上限，后续内容已丢弃）" : s;
        }
    }
}
