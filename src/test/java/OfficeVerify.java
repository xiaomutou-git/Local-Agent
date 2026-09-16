import com.localagent.office.Office;
import com.localagent.toolkit.ToolResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.*;

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

    /** 测试用 1MiB 常量（zip 炸弹构造按 MB 计）。 */
    private static final int MB = 1024 * 1024;

    /**
     * 按给定顺序构造 zip 容器（OOXML 本质即 zip），全部条目 DEFLATED 压缩。
     * 填充内容为全零字节：高压缩比，压缩后只有几 KB，可在极小磁盘开销下
     * 模拟解压放大（zip 炸弹）。
     * @param zip     目标 zip 路径
     * @param entries 有序条目映射（键=条目名，值=未压缩字节），null 条目跳过
     * @throws IOException 创建/写入 zip 失败时抛出
     */
    static void writeZip(Path zip, Map<String, byte[]> entries) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                if (e.getValue() == null) continue;
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
    }

    /**
     * 生成全零字节数组（高压缩比填充）。
     * @param n 字节数（允许取值 0..100MiB，测试内不做越界保护，交由 JVM 抛 OOM 暴露问题）
     * @return 长度为 n 的全零数组
     */
    static byte[] zeros(int n) { return new byte[n]; }

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

        // ================= 编辑已有文档 =================
        // xlsx：按名选第二个工作表追加行 + 定点写入/覆盖单元格 + 数字类型
        Path xlsx2 = dir.resolve("t2.xlsx");
        Map<String, Object> xa2 = new LinkedHashMap<>();
        xa2.put("path", xlsx2.toString());
        xa2.put("sheets", List.of(
                Map.of("name", "S1", "rows", List.of(List.of("甲1", "甲2"))),
                Map.of("name", "S2", "rows", List.of(List.of("乙1", "乙2")))));
        ToolResult xe0 = office.createXlsx(xa2);
        t("双表 xlsx 生成", xe0.ok());
        Map<String, Object> xe1 = new LinkedHashMap<>();
        xe1.put("path", xlsx2.toString());
        xe1.put("sheet", "S2");
        xe1.put("appendRows", List.of(List.of("乙3", "数据X")));
        xe1.put("cells", List.of(Map.of("ref", "B1", "value", "乙2改"), Map.of("ref", "C3", "value", 88)));
        ToolResult xr1 = office.editXlsx(xe1);
        t("xlsx 追加行+定点写入: " + (xr1.ok() ? xr1.message() : xr1.error()), xr1.ok());
        ToolResult xr1r = office.readOffice(Map.of("path", xlsx2.toString()));
        String xrText = xr1r.message() == null ? "" : xr1r.message();
        t("xlsx 编辑后含追加行/覆盖值/数字", xr1r.ok() && xrText.contains("数据X") && xrText.contains("乙2改")
                && xrText.contains("88") && !xrText.contains("乙2\n"));
        int markerCount = xrText.split("数据X", -1).length - 1;
        t("追加只落在指定工作表 S2（标记仅出现 1 次）", markerCount == 1);
        t("xlsx 备份 .bak 已生成", Files.exists(Paths.get(xlsx2 + ".bak")));
        // 公式注入必须被拒绝且文件仍可正常读取
        Map<String, Object> xbad = new LinkedHashMap<>();
        xbad.put("path", xlsx2.toString());
        xbad.put("cells", List.of(Map.of("ref", "D1", "value", "=cmd|'/c calc'!A1")));
        ToolResult xrBad = office.editXlsx(xbad);
        t("xlsx 公式注入被拦截", !xrBad.ok() && xrBad.error().contains("风险"));

        // docx：文末追加 + find/replace
        Map<String, Object> de1 = new LinkedHashMap<>();
        de1.put("path", docx.toString());
        de1.put("paragraphs", List.of(Map.of("type", "heading1", "text", "新增小标题"),
                Map.of("type", "para", "text", "追加的正文丙")));
        de1.put("find", "正文内容乙");
        de1.put("replace", "正文内容乙已替换");
        ToolResult dr1 = office.editDocx(de1);
        t("docx 追加+替换: " + (dr1.ok() ? dr1.message() : dr1.error()), dr1.ok());
        ToolResult dr1r = office.readOffice(Map.of("path", docx.toString()));
        String drText = dr1r.message() == null ? "" : dr1r.message();
        t("docx 编辑后含追加段落", dr1r.ok() && drText.contains("新增小标题") && drText.contains("追加的正文丙"));
        t("docx find/replace 生效", drText.contains("正文内容乙已替换"));
        t("docx 备份 .bak 已生成", Files.exists(Paths.get(docx + ".bak")));
        // 找不到目标文本：整体失败且不得破坏原文件
        Map<String, Object> de2 = new LinkedHashMap<>();
        de2.put("path", docx.toString());
        de2.put("find", "文档里根本不存在的句子XYZ");
        de2.put("replace", "whatever");
        ToolResult dr2 = office.editDocx(de2);
        t("docx 替换不到文本时报错", !dr2.ok() && dr2.error().contains("未在文档中找到"));
        ToolResult dr2r = office.readOffice(Map.of("path", docx.toString()));
        t("替换失败未破坏 docx（仍可读且内容还在）", dr2r.ok() && dr2r.message().contains("正文内容乙已替换"));

        // pptx：末尾追加一页
        Map<String, Object> pe1 = new LinkedHashMap<>();
        pe1.put("path", pptx.toString());
        pe1.put("slides", List.of(Map.of("title", "第二页新标题", "bullets", List.of("新要点一", "新要点二"))));
        ToolResult pr1 = office.editPpt(pe1);
        t("pptx 追加页: " + (pr1.ok() ? pr1.message() : pr1.error()), pr1.ok());
        ToolResult pr1r = office.readOffice(Map.of("path", pptx.toString()));
        String prText = pr1r.message() == null ? "" : pr1r.message();
        t("pptx 编辑后含新旧两页", pr1r.ok() && prText.contains("第一页标题")
                && prText.contains("第二页新标题") && prText.contains("新要点二"));
        t("pptx 备份 .bak 已生成", Files.exists(Paths.get(pptx + ".bak")));

        // 非法目标：不存在的文件 / 错误扩展名 / 空操作
        t("编辑不存在文件报错", !office.editDocx(Map.of("path", dir.resolve("nope.docx").toString(),
                "paragraphs", List.of(Map.of("text", "x")))).ok());
        Map<String, Object> wrongExt = new LinkedHashMap<>();
        wrongExt.put("path", dir.resolve("t.txt").toString());
        wrongExt.put("slides", List.of(Map.of("title", "x")));
        t("错误扩展名报错", !office.editPpt(wrongExt).ok());
        t("无任何编辑动作报错", !office.editXlsx(Map.of("path", xlsx2.toString())).ok());

        // ================= V-01 zip 炸弹防护（流式闸门：单条目 / 条目数 / 累计量）=================
        // 正常追加参数（必须通过危险内容检查后才会走到 zip 读取层，确保错误来自炸弹闸门）
        List<Map<String, Object>> safeAppend = List.of(Map.of("type", "para", "text", "正常追加文本"));

        // 1) 单条目解压 20MB+1：编辑路径严格读取，读到第 20MB+1 字节即抛错（不会先全量入内存）
        Path bombEntry = dir.resolve("bomb-entry.docx");
        Map<String, byte[]> be = new LinkedHashMap<>();
        be.put("word/document.xml", zeros(20 * MB + 1));
        writeZip(bombEntry, be);
        ToolResult br1 = office.editDocx(Map.of("path", bombEntry.toString(), "paragraphs", safeAppend));
        t("zip 炸弹：单条目解压超 20MB 被硬拒（不 OOM）",
                !br1.ok() && br1.error() != null && br1.error().contains("20MB"));

        // 2) 1 个正常文档条目 + 10000 个垃圾条目（共 10001）：枚举阶段即拒
        Path bombCount = dir.resolve("bomb-count.docx");
        Map<String, byte[]> bc = new LinkedHashMap<>();
        bc.put("word/document.xml", "<w:document/>".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 10_000; i++) bc.put("junk/e" + i + ".bin", new byte[]{0});
        writeZip(bombCount, bc);
        ToolResult br2 = office.editDocx(Map.of("path", bombCount.toString(), "paragraphs", safeAppend));
        t("zip 炸弹：条目数超 1 万被硬拒",
                !br2.ok() && br2.error() != null && br2.error().contains("条目数"));

        // 3) 6 个 17MB 条目（单条均未超 20MB，累计 102MB > 100MB 总闸）：累计量闸门拒绝
        Path bombTotal = dir.resolve("bomb-total.docx");
        Map<String, byte[]> bt = new LinkedHashMap<>();
        bt.put("word/document.xml", "<w:document/>".getBytes(StandardCharsets.UTF_8));
        for (int i = 0; i < 6; i++) bt.put("junk/big" + i + ".bin", zeros(17 * MB));
        writeZip(bombTotal, bt);
        ToolResult br3 = office.editDocx(Map.of("path", bombTotal.toString(), "paragraphs", safeAppend));
        t("zip 炸弹：累计解压超 100MB 被硬拒",
                !br3.ok() && br3.error() != null && br3.error().contains("100MB"));

        // 4) 读取路径 25MB 单幻灯片：保持"截断到 20MB"语义，成功返回且不崩溃
        Path bombRead = dir.resolve("bomb-read.pptx");
        Map<String, byte[]> brmap = new LinkedHashMap<>();
        brmap.put("[Content_Types].xml", "<Types/>".getBytes(StandardCharsets.UTF_8));
        brmap.put("ppt/slides/slide1.xml", zeros(25 * MB));
        writeZip(bombRead, brmap);
        ToolResult br4 = office.readOffice(Map.of("path", bombRead.toString()));
        t("读取路径 25MB 单条目按 20MB 截断不崩溃", br4.ok());

        // ================= V-02 createPpt 危险内容拦截 =================
        Map<String, Object> badPpt = new LinkedHashMap<>();
        badPpt.put("path", dir.resolve("bad.pptx").toString());
        badPpt.put("slides", List.of(Map.of("title", "正常标题",
                "bullets", List.of("powershell -c Invoke-Expression"))));
        ToolResult r5 = office.createPpt(badPpt);
        t("危险 pptx 生成被拦截（V-02）", !r5.ok() && r5.error() != null && r5.error().contains("危险"));

        // 临时文件由退出钩子统一递归清理（含 bad.docx、*.bak、*.tmp 与目录本身）

        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        System.exit(fail > 0 ? 1 : 0);
    }
}
