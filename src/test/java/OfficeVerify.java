import com.localagent.office.Office;
import com.localagent.tools.ToolResult;

import java.nio.file.*;
import java.util.*;

/**
 * Office OOXML 生成/读取烟测：xlsx/docx/pptx 生成 -> 读回校验关键字。
 */
public class OfficeVerify {
    static int pass = 0, fail = 0;
    static void t(String n, boolean c) { if (c) { pass++; System.out.println("[PASS] " + n); } else { fail++; System.out.println("[FAIL] " + n); } }

    public static void main(String[] args) throws Exception {
        Office office = new Office();
        Path dir = Paths.get(System.getProperty("java.io.tmpdir"), "localagent-office-test");
        Files.createDirectories(dir);

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

        // 清理
        for (String e : new String[]{"t.xlsx", "t.docx", "t.pptx"}) Files.deleteIfExists(dir.resolve(e));

        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        System.exit(fail > 0 ? 1 : 0);
    }
}
