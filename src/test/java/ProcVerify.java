import com.localagent.toolkit.Proc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Proc 子进程通道回归（P0-1）。
 *
 * 覆盖旧实现"先 waitFor 再 readAllBytes"在 stdout 写满约 64KB 管道缓冲后
 * 父子互锁挂死的问题：
 * - 200KB / 1.5MB 输出均能在限时内正常返回（不挂死）；
 * - 单流超 1MiB 时截断并追加中文标记，进程退出码仍准确；
 * - stdout/stderr 分别捕获；非零退出码透传；超时强杀抛 IOException。
 *
 * 辅助负载 BigGen 用当前 JBR 现场编译，不依赖仓库内任何额外产物。
 *
 * 创建时间：2026-09，核心用途：build.ps1 回归集的管道安全验证项。
 */
public class ProcVerify {
    static int pass = 0, fail = 0;

    /**
     * 断言辅助。
     * @param name 用例名
     * @param cond 断言条件
     */
    static void t(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    /**
     * 递归删除临时目录（best-effort，静默）：按路径倒序先删文件再删空目录，
     * 遇 Windows 文件锁做短暂重试。
     * 不输出 stderr：build.ps1 以 EAP=Stop 运行测试，原生进程 stderr 会被
     * 包装成终止错误导致整轮构建中断。
     * @param root 待删除目录；为 null 或不存在时直接返回
     */
    static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        for (int attempt = 0; attempt < 3; attempt++) {
            boolean remaining = false;
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    try { Files.deleteIfExists(p); } catch (IOException e) { remaining = true; }
                }
            } catch (IOException e) {
                return;
            }
            if (!remaining || !Files.exists(root)) return;
            try { Thread.sleep(120); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
    }

    /**
     * 注册 JVM 退出清理钩子。本回归以 System.exit 结束（try/finally 不会执行），
     * 必须通过 shutdown hook 保证 %TEMP% 下零残留。
     * @param dir 退出时递归删除的临时目录
     */
    static void cleanupOnExit(Path dir) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> deleteRecursively(dir), "test-cleanup"));
    }

    /**
     * 回归入口：准备负载源码后依次执行大输出/退出码/超时用例。
     * 负载通过 JDK 单文件源码模式（java BigGen.java）直接运行，与父进程同一
     * java.exe，避免部分机器 ASR 策略拦截 javac 子进程造成与本回归无关的失败。
     * @param args 未使用
     * @throws Exception 执行发生意外异常时抛出（视为回归失败）
     */
    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("localagent-proctest");
        cleanupOnExit(work);
        Path javaHome = Path.of(System.getProperty("java.home"));
        boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
        String javaExe = javaHome.resolve("bin").resolve(win ? "java.exe" : "java").toString();

        // 负载源码：big=按字节数输出定长行 + stderr 标记；exit=退出码 7；sleep=长睡眠（用于超时）
        String src = """
                public class BigGen {
                    public static void main(String[] a) throws Exception {
                        if ("big".equals(a[0])) {
                            int total = Integer.parseInt(a[1]);
                            byte[] line = "0123456789ABCDEF".repeat(40).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            int lines = (total + line.length - 1) / line.length;
                            for (int i = 0; i < lines; i++) { System.out.write(line); System.out.write('\\n'); }
                            System.out.flush();
                            System.err.println("STDERR_MARK_42");
                        } else if ("exit".equals(a[0])) {
                            System.exit(7);
                        } else if ("sleep".equals(a[0])) {
                            Thread.sleep(20000);
                        }
                    }
                }
                """;
        Path srcFile = work.resolve("BigGen.java");
        Files.writeString(srcFile, src, StandardCharsets.UTF_8);

        // 用例 1：1.5MB stdout，旧实现必然互锁挂死；新实现限时返回且退出码为 0
        long t0 = System.currentTimeMillis();
        Proc.Result big = Proc.exec(javaExe, List.of(srcFile.toString(), "big", "1500000"),
                work.toString(), 60_000, null);
        long cost = System.currentTimeMillis() - t0;
        t("1.5MB 大输出不挂死且退出码为 0", big.code() == 0 && cost < 60_000);
        t("超 1MiB 输出被截断并带标记",
                big.out().contains("1024 KiB 上限") && big.out().length() < 1_100_000);
        t("stderr 独立捕获标记", big.err().contains("STDERR_MARK_42"));

        // 用例 2：200KB（超过 64KB 管道缓冲但小于捕获上限）不应出现截断标记
        Proc.Result mid = Proc.exec(javaExe, List.of(srcFile.toString(), "big", "200000"),
                work.toString(), 60_000, null);
        t("200KB 输出完整无截断标记",
                mid.code() == 0 && !mid.out().contains("上限") && mid.out().length() >= 200_000);

        // 用例 3：非零退出码必须原样透传
        Proc.Result exit7 = Proc.exec(javaExe, List.of(srcFile.toString(), "exit"),
                work.toString(), 30_000, null);
        t("非零退出码 7 透传", exit7.code() == 7);

        // 用例 4：超时必须抛 IOException 且进程被强杀（不残留）
        boolean timeoutFired = false;
        try {
            Proc.exec(javaExe, List.of(srcFile.toString(), "sleep"),
                    work.toString(), 1_500, null);
        } catch (java.io.IOException e) {
            timeoutFired = e.getMessage() != null && e.getMessage().contains("超时");
        }
        t("超时强杀并抛 IOException", timeoutFired);

        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        System.exit(fail > 0 ? 1 : 0);
    }
}
