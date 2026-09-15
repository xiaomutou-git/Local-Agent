import com.localagent.office.Office;
import com.localagent.tools.ToolResult;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/**
 * Office OOXML 生成/读取烟测：xlsx/docx/pptx 生成 -> 读回校验关键字。
 */
public class OfficeVerify {
    static int pass = 0, fail = 0;
    static void t(String n, boolean c) { if (c) { pass++; System.out.println("[PASS] " + n); } else { fail++; System.out.println("[FAIL] " + n); } }

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

    public static void main(String[] args) throws Exception {
        Office office = new Office();
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "localagent-office-test");
        Files.createDirectories(dir);
        cleanupOnExit(dir);

        // xlsx
        Path xlsx = dir.resolve("t.xlsx");
        Map<String, Object> xa = Map.of("path", xlsx.toString(),
                "sheets", List.of(Map.of("name", "S1", "rows", List.of(List.of("姓名", "分数"), List.of("小明", 95)))));
        ToolResult r1 = office.createXlsx(xa);
        t("xlsx 生成: " + (r1.ok() ? r1.message() : r1.error()), r1.ok());
        ToolResult r1r = office.readOffice(Map.of("path", xlsx.toString()));
        t("xlsx 读回含内容", r1r.ok() && r1r.message().contains("小明") && r1r.message().contains("95"));

        // docx
        Path docx = dir.resolve("t.docx");
        Map<String, Object> da = Map.of("path", docx.toString(), "title", "标题甲",
                "paragraphs", List.of(Map.of("type", "para", "text", "这是正文内容乙")));
        ToolResult r2 = office.createDocx(da);
        t("docx 生成: " + (r2.ok() ? r2.message() : r2.error()), r2.ok());
        ToolResult r2r = office.readOffice(Map.of("path", docx.toString()));
        t("docx 读回含内容", r2r.ok() && r2r.message().contains("正文内容乙"));

        // pptx
        Path pptx = dir.resolve("t.pptx");
        Map<String, Object> pa = Map.of("path", pptx.toString(), "title", "演示",
                "slides", List.of(Map.of("title", "第一页标题", "bullets", List.of("要点甲", "要点乙"))));
        ToolResult r3 = office.createPpt(pa);
        t("pptx 生成: " + (r3.ok() ? r3.message() : r3.error()), r3.ok());
        if (r3.ok()) {
            ToolResult r3r = office.readOffice(Map.of("path", pptx.toString()));
            t("pptx 读回含内容", r3r.ok() && r3r.message().contains("第一页标题"));
        }

        // 危险内容拦截
        Map<String, Object> bad = Map.of("path", dir.resolve("bad.docx").toString(),
                "paragraphs", List.of(Map.of("type", "para", "text", "powershell -c Invoke-Expression")));
        ToolResult r4 = office.createDocx(bad);
        t("危险 docx 被拦截", !r4.ok() && r4.error().contains("危险"));

        // 临时文件由退出钩子统一递归清理（含 bad.docx 与目录本身）

        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        System.exit(fail > 0 ? 1 : 0);
    }
}
