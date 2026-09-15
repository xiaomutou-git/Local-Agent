import com.localagent.system.SingleInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 单实例文件锁回归（P0-7）。
 *
 * 验证：首次获取成功；锁被持有时第二次获取返回 null（拦截重复启动）；
 * 主动释放后可再次获取（进程正常退出后下一次启动不受影响）；
 * 锁文件父目录不存在时自动创建。
 *
 * 创建时间：2026-09，核心用途：build.ps1 回归集的单实例行为验证项。
 */
public class SingleInstanceVerify {
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
     * 回归入口。
     * @param args 未使用
     * @throws Exception 锁文件 IO 异常时抛出（视为回归失败）
     */
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("localagent-locktest");
        cleanupOnExit(dir);
        Path lockFile = dir.resolve("nested").resolve("app.lock");

        SingleInstance first = SingleInstance.tryAcquire(lockFile);
        t("首次获取单实例锁成功（父目录自动创建）",
                first != null && Files.exists(lockFile));

        SingleInstance second = SingleInstance.tryAcquire(lockFile);
        t("锁持有时重复获取返回 null", second == null);

        first.release();
        SingleInstance again = SingleInstance.tryAcquire(lockFile);
        t("释放后可重新获取锁", again != null);
        again.release();

        // 锁文件与嵌套目录由退出钩子统一递归清理
        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        System.exit(fail > 0 ? 1 : 0);
    }
}
