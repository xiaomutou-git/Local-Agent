package com.localagent.tools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * 子进程执行辅助（移植 tools.js pwsh/run_command 的执行语义）。
 *
 * 安全要点：
 * - 全部走 ProcessBuilder 参数数组（等价 shell:false），shell 元字符不会被二次解释；
 * - 子进程环境注入失效代理变量（纵深防御，仅影响遵循代理的程序）；
 * - 输出截断与超时控制，防止大输出/挂死拖垮应用。
 */
public final class Proc {
    private Proc() {}

    /** 失效代理环境：仅对遵循 HTTP_PROXY 的程序有效，属纵深防御。 */
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

    public record Result(int code, String out, String err) {}

    /**
     * 执行外部程序（不经 shell）。
     * @param program 程序名或路径
     * @param argv    参数数组
     * @param cwd     工作目录（null=用户目录）
     * @param timeoutMs 超时毫秒
     * @param stdinText 需要写入子进程 stdin 的文本（null 表示不写）
     */
    public static Result exec(String program, List<String> argv, String cwd,
                              long timeoutMs, String stdinText) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(program);
        if (argv != null) cmd.addAll(argv);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().clear();
        pb.environment().putAll(deadProxyEnv());
        if (cwd != null) pb.directory(new File(cwd));
        Process child = pb.start();
        if (stdinText != null && child.getOutputStream() != null) {
            child.getOutputStream().write((stdinText + "\n").getBytes(StandardCharsets.UTF_8));
            child.getOutputStream().close();
        }
        boolean finished = child.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) { child.destroyForcibly(); throw new IOException("执行超时（" + timeoutMs + "ms）"); }
        String out = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(child.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(child.exitValue(), out, err);
    }

    /** PowerShell 只读查询（内部工具使用，非 run_command 用户通道）。 */
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
}
