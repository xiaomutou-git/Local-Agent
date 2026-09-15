import com.localagent.knowledge.Knowledge;
import com.localagent.util.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 知识库索引持久化回归（P0-6）。
 *
 * 覆盖：
 * - 索引文件不落在被索引目录内（旧实现会把 knowledge-index.json 自己吃进去），
 *   且旧版目录内索引文件被显式跳过；
 * - 内容/修改时间变化后，新实例凭 mtime+size 清单判定缓存陈旧并自动重建，
 *   新关键词可被检索（旧实现缓存永久陈旧）；
 * - 缓存文件损坏时安全回退为全量重建，检索仍可用。
 *
 * 创建时间：2026-09，核心用途：build.ps1 回归集的索引正确性验证项。
 */
public class KnowledgeIndexVerify {
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
     * 等待索引就绪（构建在后台守护线程执行）。
     * @param k        知识库实例
     * @param timeoutMs 最多等待毫秒
     * @return true=已就绪；false=超时仍未就绪
     * @throws InterruptedException 等待休眠被中断时抛出
     */
    static boolean waitReady(Knowledge k, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (k.isReady()) return true;
            Thread.sleep(100);
        }
        return k.isReady();
    }

    /**
     * 在 AppData 索引目录中找到指向指定知识目录的缓存文件。
     * @param knowledgeDir 被索引目录
     * @return 缓存文件路径；不存在时返回 null
     * @throws Exception 读取目录发生 IO 错误时抛出
     */
    static Path findCacheFile(Path knowledgeDir) throws Exception {
        Path base = Paths.get(System.getProperty("user.home"),
                "AppData", "Roaming", "本机助手", "data", "knowledge");
        if (!Files.isDirectory(base)) return null;
        Path onlyCandidate = null;
        int candidates = 0;
        try (Stream<Path> files = Files.list(base)) {
            for (Path f : files.toList()) {
                if (!f.getFileName().toString().startsWith("index-")) continue;
                candidates++;
                try {
                    var node = Json.mapper().readTree(Files.readString(f));
                    if (knowledgeDir.toString().equals(node.path("dir").asText(""))) return f;
                } catch (Exception ignored) {
                    // 损坏文件无法解析，先跳过；若最终只有一个候选则按 onlyCandidate 采用
                }
                onlyCandidate = f;
            }
        }
        if (candidates == 1) return onlyCandidate;
        // 多候选且无 dir 精确匹配：无法可靠定位，返回 null
        return null;
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
     * 钩子同时删除 AppData 下本用例产生的索引缓存与临时知识目录，
     * 保证用例中途失败也不留残余（全程静默，原因同 deleteRecursively）。
     * @param knowledgeDir 退出时需清理的临时知识目录（据此定位其索引缓存）
     */
    static void cleanupOnExit(Path knowledgeDir) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                Path cache = findCacheFile(knowledgeDir);
                if (cache != null) Files.deleteIfExists(cache);
            } catch (Exception ignored) {
                // 缓存删除失败属 best-effort，下次用例使用不同临时目录，不受影响
            }
            deleteRecursively(knowledgeDir);
        }, "test-cleanup"));
    }

    /**
     * 回归入口。
     * @param args 未使用
     * @throws Exception 文件/等待相关异常时抛出（视为回归失败）
     */
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("localagent-kbtest");
        cleanupOnExit(dir);
        Path doc = dir.resolve("a.md");
        Files.writeString(doc, "# 笔记一\n这是蓝鲸ZQX9001知识库测试内容，用于BM25检索。\n");
        // 旧版本写在知识目录内的索引文件：含独有标记，必须永不被索引
        Path legacy = dir.resolve("knowledge-index.json");
        Files.writeString(legacy, "{\"junk\":\"LEGACYINDEXTOKEN777\"}");

        Knowledge k1 = new Knowledge();
        k1.init(dir.toString());
        t("首次初始化后台建索引完成", waitReady(k1, 30_000));
        t("可检索到文件内容（蓝鲸ZQX9001）", !k1.search("蓝鲸ZQX9001", 5).isEmpty());
        t("旧版目录内索引文件被跳过（LEGACYINDEXTOKEN777 不可命中）",
                k1.search("LEGACYINDEXTOKEN777", 5).isEmpty());
        Path cache1 = findCacheFile(dir);
        t("索引缓存位于 AppData（非知识目录内）", cache1 != null && !cache1.startsWith(dir));
        try (Stream<Path> inside = Files.walk(dir)) {
            t("知识目录内没有新建 index-* 缓存文件",
                    inside.noneMatch(p -> p.getFileName().toString().startsWith("index-")));
        }

        // 修改文件内容并确保 mtime 跨毫秒推进，新实例必须识别缓存陈旧
        Thread.sleep(60);
        Files.writeString(doc, "# 笔记一（更新）\n新增内容标记白鲸ZQX9002，旧内容蓝鲸ZQX9001仍保留。\n");
        Thread.sleep(60);
        Knowledge k2 = new Knowledge();
        k2.init(dir.toString());
        t("文件变更后新实例重建索引完成", waitReady(k2, 30_000));
        t("重建后可检索新增内容（白鲸ZQX9002）", !k2.search("白鲸ZQX9002", 5).isEmpty());
        t("重建后旧内容仍可检索（蓝鲸ZQX9001）", !k2.search("蓝鲸ZQX9001", 5).isEmpty());

        // 损坏缓存：写入垃圾字节后新实例应回退重建而非不可用
        Path cache2 = findCacheFile(dir);
        if (cache2 != null) Files.writeString(cache2, "@@@不是合法JSON@@@");
        Knowledge k3 = new Knowledge();
        k3.init(dir.toString());
        t("缓存损坏时回退重建并恢复检索", waitReady(k3, 30_000)
                && !k3.search("蓝鲸ZQX9001", 5).isEmpty());

        // AppData 索引缓存与临时知识目录由退出钩子统一清理（失败路径也覆盖）

        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        System.exit(fail > 0 ? 1 : 0);
    }
}
