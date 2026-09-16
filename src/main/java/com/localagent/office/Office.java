package com.localagent.office;

import com.localagent.safety.DocSafety;
import com.localagent.toolkit.ToolResult;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.*;

/**
 * Office 文档生成与读取（移植 office.js，零第三方依赖版）。
 *
 * OOXML（docx/xlsx/pptx）本质是 ZIP+XML，用 JDK 内置 java.util.zip 手工构造，
 * 避免引入 Apache POI（Maven 网络不可达）。
 *
 * 安全：
 * - 路径写入由 Tools 前置的 Safety.checkWrite 统一校验；
 * - 读取结果统一经 DocSafety 危险内容拦截；
 * - 50MB 压缩包 / 单条目解压 20MB / 单包 1 万条目 / 累计解压 100MB 四重上限，
 *   且上限在流式读取阶段即时生效（不再先全量入内存），从根本上防 zip 炸弹 OOM。
 */
public class Office {
    /** 压缩包本体大小上限（50MB，压缩后字节）。 */
    private static final long MAX_FILE = 50L * 1024 * 1024;
    /** 单个 zip 条目解压后字节上限：读取场景按此截断，编辑场景超限即拒绝。 */
    private static final int MAX_ENTRY = 20 * 1024 * 1024;
    /** 单个 OOXML 包允许的最大条目数（防海量中央目录项耗尽内存/CPU）。 */
    private static final int MAX_ENTRIES = 10_000;
    /** 整包解压后累计字节总闸：50MB 压缩包最多解压 100MB，超限按压缩炸弹处理。 */
    private static final long MAX_TOTAL = 100L * 1024 * 1024;

    private static String norm(String p) {
        if (p == null || p.isBlank()) return "";
        return p.trim();
    }

    // ================= DOCX =================
    public ToolResult createDocx(Map<String, Object> a) {
        String out = norm(asString(a.get("path")));
        if (out.isEmpty() || !out.toLowerCase(Locale.ROOT).endsWith(".docx")) return ToolResult.error("路径必须以 .docx 结尾。");
        List<Map<String, Object>> paras = paras(a);
        // 危险内容检查
        StringBuilder allText = new StringBuilder();
        if (a.get("title") != null) allText.append(asString(a.get("title"))).append('\n');
        for (var p : paras) allText.append(asString(p.get("text"))).append('\n');
        var issues = DocSafety.detect(allText.toString());
        if (!issues.isEmpty()) return ToolResult.error("文档包含危险内容（" + String.join("、", issues) + "），禁止生成。");
        try {
            String body = "";
            if (a.get("title") != null && !asString(a.get("title")).isBlank())
                body += paraXml(escape(asString(a.get("title"))), true, 36);
            for (var p : paras) {
                String type = asString(p.getOrDefault("type", "para"));
                int size = "title".equals(type) ? 32 : "heading1".equals(type) ? 28 : 22;
                body += paraXml(escape(asString(p.get("text"))), "title".equals(type) || "heading1".equals(type), size);
            }
            String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>"
                    + body + "<w:sectPr/></w:body></w:document>";
            Map<String, String> files = new LinkedHashMap<>();
            files.put("[Content_Types].xml", CONTENT_TYPES_DOCX);
            files.put("_rels/.rels", RELS_DOCX);
            files.put("word/document.xml", document);
            writeZip(out, files);
            return ToolResult.ok("已生成 Word 文档：" + out);
        } catch (Exception e) {
            return ToolResult.error("生成 docx 失败：" + e.getMessage());
        }
    }

    private static final String CONTENT_TYPES_DOCX = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
            <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
            <Default Extension="xml" ContentType="application/xml"/>
            <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-offedocument.wordprocessingml.document.main+xml"/>
            </Types>""";
    private static final String RELS_DOCX = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
            </Relationships>""";

    private static String paraXml(String text, boolean bold, int halfPoints) {
        String b = bold ? "<w:b/>" : "";
        return "<w:p><w:r><w:rPr>" + b + "<w:sz w:val=\"" + halfPoints + "\"/></w:rPr><w:t xml:space=\"preserve\">" + text + "</w:t></w:r></w:p>";
    }

    // ================= XLSX（inlineStr，无需 sharedStrings）=================
    public ToolResult createXlsx(Map<String, Object> a) {
        String out = norm(asString(a.get("path")));
        if (out.isEmpty() || !out.toLowerCase(Locale.ROOT).endsWith(".xlsx")) return ToolResult.error("路径必须以 .xlsx 结尾。");
        List<Map<String, Object>> sheets = sheets(a);
        if (sheets.isEmpty()) sheets.add(Map.of("name", "Sheet1", "rows", List.of(List.of("列1", "列2"))));
        // 危险检查 + 公式注入检查
        for (var sh : sheets) {
            for (var rowObj : (List<?>) sh.getOrDefault("rows", List.of())) {
                for (var v : (List<?>) rowObj) {
                    String s = asString(v);
                    var issues = DocSafety.detect(s);
                    if (!issues.isEmpty()) return ToolResult.error("表格包含危险内容（" + String.join("、", issues) + "），禁止生成。");
                    if (s.matches("^[=+\\-@].+")) return ToolResult.error("单元格以 =/+/-/@ 开头（" + truncate(s, 20) + "）疑似公式注入。");
                }
            }
        }
        try {
            if (sheets.size() > 50) sheets = sheets.subList(0, 50);
            StringBuilder contentTypes = new StringBuilder("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    """);
            StringBuilder wb = new StringBuilder("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>
                    """);
            StringBuilder wbRels = new StringBuilder("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    """);
            Map<String, String> files = new LinkedHashMap<>();
            for (int i = 0; i < sheets.size(); i++) {
                var sh = sheets.get(i);
                String name = escapeAttr(asString(sh.getOrDefault("name", "Sheet" + (i + 1))));
                String file = "worksheets/sheet" + (i + 1) + ".xml";
                contentTypes.append("<Override PartName=\"/xl/").append(file).append("\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
                wb.append("<sheet name=\"").append(name).append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
                wbRels.append("<Relationship Id=\"rId").append(i + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"").append(file).append("\"/>");
                files.put("xl/" + file, sheetXml((List<?>) sh.getOrDefault("rows", List.of())));
            }
            contentTypes.append("</Types>");
            wb.append("</sheets></workbook>");
            wbRels.append("</Relationships>");
            files.put("[Content_Types].xml", contentTypes.toString());
            files.put("_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>""");
            files.put("xl/workbook.xml", wb.toString());
            files.put("xl/_rels/workbook.xml.rels", wbRels.toString());
            writeZip(out, files);
            return ToolResult.ok("已生成 Excel 表格（" + sheets.size() + " 个工作表）：" + out);
        } catch (Exception e) {
            return ToolResult.error("生成 xlsx 失败：" + e.getMessage());
        }
    }

    private static String sheetXml(List<?> rows) {
        StringBuilder sb = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
                """);
        int r = 1;
        for (Object rowObj : rows) {
            sb.append("<row r=\"").append(r).append("\">");
            int c = 0;
            for (Object v : (List<?>) rowObj) {
                sb.append("<c r=\"").append(colName(c++)).append(r).append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                  .append(escape(asString(v))).append("</t></is></c>");
            }
            sb.append("</row>");
            r++;
        }
        return sb.append("</sheetData></worksheet>").toString();
    }

    private static String colName(int idx) {
        StringBuilder s = new StringBuilder();
        int n = idx;
        do { s.insert(0, (char) ('A' + n % 26)); n = n / 26 - 1; } while (n >= 0);
        return s.toString();
    }

    // ================= PPTX（最小可用单主题结构）=================
    public ToolResult createPpt(Map<String, Object> a) {
        String out = norm(asString(a.get("path")));
        if (out.isEmpty() || !out.toLowerCase(Locale.ROOT).endsWith(".pptx")) return ToolResult.error("路径必须以 .pptx 结尾。");
        List<Map<String, Object>> slides = slides(a);
        if (slides.isEmpty()) slides.add(Map.of("title", "演示文稿", "bullets", List.of("（请补充内容）")));
        // 危险内容检查（与 createDocx/createXlsx/editPpt 对齐，防止提示注入借 PPT 落地危险文本）
        StringBuilder danger = new StringBuilder();
        for (var s : slides) {
            danger.append(asString(s.get("title"))).append('\n');
            for (Object b : (List<?>) s.getOrDefault("bullets", List.of())) danger.append(asString(b)).append('\n');
        }
        var issues = DocSafety.detect(danger.toString());
        if (!issues.isEmpty())
            return ToolResult.error("幻灯片包含危险内容（" + String.join("、", issues) + "），禁止生成。");
        try {
            if (slides.size() > 200) slides = slides.subList(0, 200);
            StringBuilder ct = new StringBuilder("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
                    <Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>
                    <Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>
                    <Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
                    """);
            StringBuilder pres = new StringBuilder("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst><p:sldIdLst>
                    """);
            StringBuilder presRels = new StringBuilder("""
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>
                    """);
            for (int i = 0; i < slides.size(); i++) {
                ct.append("<Override PartName=\"/ppt/slides/slide").append(i + 1).append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>");
                pres.append("<p:sldId id=\"").append(256 + i).append("\" r:id=\"rId").append(i + 2).append("\"/>");
                presRels.append("<Relationship Id=\"rId").append(i + 2).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide").append(i + 1).append(".xml\"/>");
            }
            pres.append("</p:sldIdLst></p:presentation>");
            presRels.append("</Relationships>");
            Map<String, String> files = new LinkedHashMap<>();
            files.put("[Content_Types].xml", ct.append("</Types>").toString());
            files.put("_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
                    </Relationships>""");
            files.put("ppt/presentation.xml", pres.toString());
            files.put("ppt/_rels/presentation.xml.rels", presRels.toString());
            files.put("ppt/slideMasters/slideMaster1.xml", MASTER);
            files.put("ppt/slideMasters/_rels/slideMaster1.xml.rels", MASTER_RELS);
            files.put("ppt/slideLayouts/slideLayout1.xml", LAYOUT);
            files.put("ppt/theme/theme1.xml", THEME);
            for (int i = 0; i < slides.size(); i++)
                files.put("ppt/slides/slide" + (i + 1) + ".xml", slideXml(slides.get(i)));
            writeZip(out, files);
            return ToolResult.ok("已生成 PowerPoint（" + slides.size() + " 页）：" + out);
        } catch (Exception e) {
            return ToolResult.error("生成 pptx 失败：" + e.getMessage());
        }
    }

    private static String slideXml(Map<String, Object> slide) {
        String title = escape(asString(slide.get("title")));
        StringBuilder body = new StringBuilder();
        for (Object b : (List<?>) slide.getOrDefault("bullets", List.of()))
            body.append("<a:p><a:r><a:t>").append(escape(asString(b))).append("</a:t></a:r></a:p>");
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">"
                + "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>"
                + "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Title\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr sz=\"2800\" b=\"1\"/><a:t>" + title + "</a:t></a:r></a:p></p:txBody></p:sp>"
                + "<p:sp><p:nvSpPr><p:cNvPr id=\"3\" name=\"Body\"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr><p:spPr/><p:txBody><a:bodyPr/><a:lstStyle/>" + body + "</p:txBody></p:sp>"
                + "</p:spTree></p:cSld></p:sld>";
    }

    private static final String MASTER = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"><p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld><p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/><p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst></p:sldMaster>""";
    private static final String MASTER_RELS = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
            <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
            <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
            </Relationships>""";
    private static final String LAYOUT = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="title"><p:cSld name="Title Slide"><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/></p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>""";
    private static final String THEME = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office"><a:themeElements><a:clrScheme name="Office"><a:dk1><a:srgbClr val="000000"/></a:dk1><a:lt1><a:srgbClr val="FFFFFF"/></a:lt1><a:dk2><a:srgbClr val="1F497D"/></a:dk2><a:lt2><a:srgbClr val="EEECE1"/></a:lt2><a:accent1><a:srgbClr val="4F81BD"/></a:accent1><a:accent2><a:srgbClr val="C0504D"/></a:accent2><a:accent3><a:srgbClr val="9BBB59"/></a:accent3><a:accent4><a:srgbClr val="8064A2"/></a:accent4><a:accent5><a:srgbClr val="4BACC6"/></a:accent5><a:accent6><a:srgbClr val="F79646"/></a:accent6><a:hlink><a:srgbClr val="0000FF"/></a:hlink><a:folHlink><a:srgbClr val="800080"/></a:folHlink></a:clrScheme><a:fontScheme name="Office"><a:majorFont><a:latin typeface="Calibri"/></a:majorFont><a:minorFont><a:latin typeface="Calibri"/></a:minorFont></a:fontScheme><a:fmtScheme name="Office"><a:fillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:fillStyleLst><a:lnStyleLst><a:ln w="9525"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln></a:lnStyleLst><a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme></a:themeElements></a:theme>""";

    // ================= 读取 =================
    public ToolResult readOffice(Map<String, Object> a) {
        String p = norm(asString(a.get("path")));
        if (p.isEmpty()) return ToolResult.error("路径无效。");
        String ext = p.toLowerCase(Locale.ROOT);
        if (!(ext.endsWith(".pptx") || ext.endsWith(".docx") || ext.endsWith(".xlsx")))
            return ToolResult.error("仅支持读取 .pptx / .docx / .xlsx。");
        try {
            File f = new File(p);
            if (!f.exists()) return ToolResult.error("文件不存在：" + p);
            if (f.length() > MAX_FILE) return ToolResult.error("文件超过 50MB 解析上限。");
            String kind = ext.substring(ext.lastIndexOf('.'));
            String text = switch (kind) {
                case ".docx" -> readDocx(p);
                case ".xlsx" -> readXlsx(p);
                default -> readPptx(p);
            };
            if (text.length() > 20000) text = text.substring(0, 20000);
            var issues = DocSafety.detect(text);
            if (!issues.isEmpty())
                return ToolResult.ok("[安全拦截] 该文档包含危险内容（" + String.join("、", issues) + "），已阻止提供原文。");
            String label = switch (kind) { case ".docx" -> "Word 文档内容"; case ".xlsx" -> "Excel 内容"; default -> "PowerPoint 内容"; };
            return ToolResult.ok(label + "：\n" + (text.isBlank() ? "（无文本）" : text));
        } catch (Exception e) {
            return ToolResult.error("解析失败：" + e.getMessage());
        }
    }

    private String readDocx(String p) throws IOException {
        byte[] xml = readZipEntry(p, "word/document.xml");
        return stripXml(new String(xml, StandardCharsets.UTF_8));
    }

    private String readPptx(String p) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (ZipFile zf = new ZipFile(p)) {
            var names = entriesBounded(zf).stream()
                    .map(ZipEntry::getName).filter(n -> n.matches("ppt/slides/slide\\d+\\.xml"))
                    .sorted(Comparator.comparingInt(Office::slideNum)).toList();
            int i = 1;
            long total = 0;
            for (String n : names) {
                byte[] b = readEntry(zf, n);
                total += b.length;
                if (total > MAX_TOTAL)
                    throw new IOException("解压后总大小超过 100MB 上限，疑似压缩炸弹。");
                sb.append("第").append(i++).append("页：").append(stripXml(new String(b, StandardCharsets.UTF_8))).append('\n');
            }
        }
        return sb.toString();
    }

    private String readXlsx(String p) throws IOException {
        List<String> shared = new ArrayList<>();
        try (ZipFile zf = new ZipFile(p)) {
            long total = 0;
            ZipEntry se = zf.getEntry("xl/sharedStrings.xml");
            if (se != null) {
                byte[] b = readEntry(zf, "xl/sharedStrings.xml");
                total += b.length;
                if (total > MAX_TOTAL)
                    throw new IOException("解压后总大小超过 100MB 上限，疑似压缩炸弹。");
                Matcher m = Pattern.compile("<si>([\\s\\S]*?)</si>").matcher(new String(b, StandardCharsets.UTF_8));
                while (m.find()) shared.add(stripXml(m.group(1)));
            }
            var names = entriesBounded(zf).stream().map(ZipEntry::getName)
                    .filter(n -> n.matches("xl/worksheets/sheet\\d+\\.xml")).sorted().toList();
            StringBuilder out = new StringBuilder();
            for (String n : names) {
                byte[] b = readEntry(zf, n);
                total += b.length;
                if (total > MAX_TOTAL)
                    throw new IOException("解压后总大小超过 100MB 上限，疑似压缩炸弹。");
                String xml = new String(b, StandardCharsets.UTF_8);
                Matcher rows = Pattern.compile("<row[^>]*>([\\s\\S]*?)</row>").matcher(xml);
                while (rows.find()) {
                    Matcher cells = Pattern.compile("<c[^>]*>([\\s\\S]*?)</c>").matcher(rows.group(1));
                    List<String> line = new ArrayList<>();
                    while (cells.find()) {
                        String cxml = cells.group(1);
                        String v = "";
                        Matcher vm = Pattern.compile("<v>([\\s\\S]*?)</v>").matcher(cxml);
                        Matcher im = Pattern.compile("<t[^>]*>([\\s\\S]*?)</t>").matcher(cxml);
                        if (vm.find()) v = vm.group(1);
                        else if (im.find()) v = im.group(1);
                        line.add(v);
                    }
                    out.append(String.join(" | ", line)).append('\n');
                }
                out.append('\n');
            }
            return out.toString();
        }
    }

    // ================= 编辑已有文档（读包 -> 改 XML -> 备份原子回写）=================

    /**
     * 编辑已有 Word 文档：在文末追加段落，并/或做文本替换。
     * 执行逻辑：校验路径与操作项 -> 读 word/document.xml -> 替换限定在单个
     * &lt;w:t&gt; 文本节点内（对本工具生成的段落精确生效）-> 段落插到 sectPr 之前 ->
     * 备份原文件为 .bak 后原子覆盖。
     * @param a 参数：path 必填；paragraphs 同 create_docx 的段落数组；
     *          find/replace 成对出现时做文本替换；backup 默认 true（保留一份 .bak）
     * @return 成功时汇总追加段数与替换次数；结构不符/危险内容/公式类风险时返回错误
     */
    public ToolResult editDocx(Map<String, Object> a) {
        String path = norm(asString(a.get("path")));
        if (path.isEmpty() || !path.toLowerCase(Locale.ROOT).endsWith(".docx"))
            return ToolResult.error("路径必须指向已有的 .docx 文件。");
        List<Map<String, Object>> append = paras(a);
        String find = asString(a.get("find"));
        String replace = asString(a.get("replace"));
        boolean doReplace = a.containsKey("find") && a.containsKey("replace") && !find.isEmpty();
        if (append.isEmpty() && !doReplace)
            return ToolResult.error("请提供要追加的 paragraphs，或成对的 find/replace。");
        StringBuilder danger = new StringBuilder();
        for (var p : append) danger.append(asString(p.get("text"))).append('\n');
        if (doReplace) danger.append(replace).append('\n');
        var issues = DocSafety.detect(danger.toString());
        if (!issues.isEmpty())
            return ToolResult.error("写入内容包含危险内容（" + String.join("、", issues) + "），禁止写入。");
        try {
            Map<String, byte[]> zip = readAllEntries(path);
            byte[] docBytes = zip.get("word/document.xml");
            if (docBytes == null) return ToolResult.error("不是有效的 docx：缺少 word/document.xml。");
            String xml = new String(docBytes, StandardCharsets.UTF_8);
            int replaced = 0;
            if (doReplace) {
                // 仅在独立文本节点 <w:t...>...</w:t> 内替换，避免改动任何标签/属性；
                // find 做字面匹配（Pattern.quote），不接受正则
                String literal = Pattern.quote(escape(find));
                Matcher tm = Pattern.compile("<w:t(\\s[^>]*)?>([\\s\\S]*?)</w:t>").matcher(xml);
                StringBuilder nx = new StringBuilder();
                while (tm.find()) {
                    String attrs = tm.group(1) == null ? "" : tm.group(1);
                    String inner = tm.group(2);
                    String[] cnt = inner.split(literal, -1);
                    if (cnt.length > 1) {
                        replaced += cnt.length - 1;
                        inner = String.join(escape(replace), cnt);
                    }
                    tm.appendReplacement(nx, Matcher.quoteReplacement("<w:t" + attrs + ">" + inner + "</w:t>"));
                }
                tm.appendTail(nx);
                xml = nx.toString();
                if (replaced == 0)
                    return ToolResult.error("未在文档中找到要替换的文本：" + truncate(find, 40) + "（跨格式/跨文本节点的文字无法匹配）。");
            }
            if (!append.isEmpty()) {
                StringBuilder add = new StringBuilder();
                for (var p : append) {
                    String type = asString(p.getOrDefault("type", "para"));
                    int size = "title".equals(type) ? 32 : "heading1".equals(type) ? 28 : 22;
                    add.append(paraXml(escape(asString(p.get("text"))),
                            "title".equals(type) || "heading1".equals(type), size));
                }
                // sectPr 必须是 body 的最后一个元素，新段落只能插在它前面；
                // 没有 sectPr 时退化为插在 </w:body> 前
                int sect = xml.lastIndexOf("<w:sectPr");
                if (sect >= 0) xml = xml.substring(0, sect) + add + xml.substring(sect);
                else {
                    int end = xml.lastIndexOf("</w:body>");
                    if (end < 0) return ToolResult.error("document.xml 结构异常，找不到 w:body。");
                    xml = xml.substring(0, end) + add + xml.substring(end);
                }
            }
            zip.put("word/document.xml", xml.getBytes(StandardCharsets.UTF_8));
            boolean backup = !"false".equals(asString(a.getOrDefault("backup", "true")));
            rewriteZip(path, zip, backup);
            return ToolResult.ok("已更新 Word 文档：" + path
                    + "（追加 " + append.size() + " 段" + (doReplace ? "，替换 " + replaced + " 处" : "")
                    + (backup ? "，原文件备份为同目录 .bak" : "") + "）。");
        } catch (Exception e) {
            return ToolResult.error("编辑 docx 失败：" + e.getMessage());
        }
    }

    /**
     * 编辑已有 Excel：向工作表追加多行，并/或按 A1 引用写入指定单元格。
     * 执行逻辑：经 workbook.xml + 关系文件定位目标 worksheet -> 解析现有
     * 行/单元格（保留原始单元格 XML）-> 合并新增内容并按列排序 -> 替换 sheetData、
     * 更新 dimension -> 备份后原子回写。
     * @param a 参数：path 必填；sheet 为工作表名（不传用第一个）；appendRows 为二维
     *          文本数组；cells 为 {ref:"B3", value:"x"} 列表；backup 默认 true
     * @return 成功汇总追加行数与写入单元格数；纯数字自动按数值类型，=/+/-/@ 开头拒绝
     */
    public ToolResult editXlsx(Map<String, Object> a) {
        String path = norm(asString(a.get("path")));
        if (path.isEmpty() || !path.toLowerCase(Locale.ROOT).endsWith(".xlsx"))
            return ToolResult.error("路径必须指向已有的 .xlsx 文件。");
        List<List<String>> appendRows = stringRows(a.get("appendRows"));
        List<Map<String, Object>> cells = cellWrites(a.get("cells"));
        if (appendRows.isEmpty() && cells.isEmpty())
            return ToolResult.error("请提供 appendRows（追加行）或 cells（按引用写入单元格）。");
        for (List<String> row : appendRows)
            for (String v : row) if (badCell(v)) return ToolResult.error("单元格内容存在安全风险：" + truncate(v, 30));
        for (Map<String, Object> c : cells)
            if (badCell(asString(c.get("value"))))
                return ToolResult.error("单元格内容存在安全风险：" + truncate(asString(c.get("value")), 30));
        try {
            Map<String, byte[]> zip = readAllEntries(path);
            String sheetEntry = resolveWorksheetEntry(zip, asString(a.get("sheet")));
            if (sheetEntry == null) return ToolResult.error("未找到工作表"
                    + (asString(a.get("sheet")).isEmpty() ? "（工作簿似乎没有工作表）。"
                    : "：" + asString(a.get("sheet")) + "（请用 read_office 查看现有工作表名）。"));
            String xml = new String(zip.get(sheetEntry), StandardCharsets.UTF_8);

            // 行号 -> {列名 -> 原始/新单元格 XML}，保留未修改单元格的原始 XML
            TreeMap<Integer, LinkedHashMap<String, String>> rows = new TreeMap<>();
            Matcher rm = Pattern.compile("<row\\b[^>]*\\br=\"(\\d+)\"[^>]*(?:/>|>([\\s\\S]*?)</row>)").matcher(xml);
            while (rm.find()) {
                int rn = Integer.parseInt(rm.group(1));
                LinkedHashMap<String, String> cs = new LinkedHashMap<>();
                String body = rm.group(2);
                if (body != null) {
                    Matcher cm = Pattern.compile("<c\\b[^>]*\\br=\"([A-Z]+)\\d+\"[^>]*(?:/>|>[\\s\\S]*?</c>)").matcher(body);
                    while (cm.find()) cs.put(cm.group(1), cm.group(0));
                }
                rows.put(rn, cs);
            }
            int writes = 0;
            for (Map<String, Object> c : cells) {
                int[] rc = parseRef(asString(c.get("ref")));
                if (rc == null) return ToolResult.error("单元格引用无效（应为如 B3 的形式）：" + asString(c.get("ref")));
                rows.computeIfAbsent(rc[0], k -> new LinkedHashMap<>())
                        .put(colName(rc[1] - 1), cellXml(asString(c.get("ref")), asString(c.get("value"))));
                writes++;
            }
            int maxExisting = rows.isEmpty() ? 0 : rows.lastKey();
            int appended = 0;
            int next = maxExisting + 1;
            for (List<String> row : appendRows) {
                LinkedHashMap<String, String> cs = rows.computeIfAbsent(next, k -> new LinkedHashMap<>());
                int col = 0;
                for (String v : row) cs.put(colName(col++), cellXml(colName(col - 1) + next, v));
                next++; appended++;
            }
            int maxRow = rows.isEmpty() ? 1 : Math.max(1, rows.lastKey());
            int maxCol = 1;
            for (var cs : rows.values())
                for (String col : cs.keySet()) maxCol = Math.max(maxCol, colIndex(col) + 1);
            StringBuilder sd = new StringBuilder("<sheetData>");
            for (var e : rows.entrySet()) {
                sd.append("<row r=\"").append(e.getKey()).append("\">");
                // 单元格按列顺序输出，符合 OOXML 对行内单元格升序的要求
                new ArrayList<>(e.getValue().entrySet()).stream()
                        .sorted(Comparator.comparingInt(en -> colIndex(en.getKey())))
                        .forEach(en -> sd.append(en.getValue()));
                sd.append("</row>");
            }
            sd.append("</sheetData>");
            // 用重建后的 sheetData 替换原块（兼容自闭合的空 sheetData）
            Matcher sdMatcher = Pattern.compile("<sheetData\\b[^>]*(?:/>|>[\\s\\S]*?</sheetData>)").matcher(xml);
            if (!sdMatcher.find()) return ToolResult.error("worksheet XML 缺少 sheetData，文件可能损坏。");
            xml = sdMatcher.replaceFirst(Matcher.quoteReplacement(sd.toString()));
            // dimension 必须位于 sheetData 之前：先删除旧 dimension，再在 sheetData 前插入
            xml = xml.replaceFirst("<dimension\\b[^>]*/>", "");
            String dim = "<dimension ref=\"A1:" + colName(maxCol - 1) + maxRow + "\"/>";
            xml = xml.replaceFirst("<sheetData", Matcher.quoteReplacement(dim) + "<sheetData");
            zip.put(sheetEntry, xml.getBytes(StandardCharsets.UTF_8));
            boolean backup = !"false".equals(asString(a.getOrDefault("backup", "true")));
            rewriteZip(path, zip, backup);
            return ToolResult.ok("已更新 Excel：" + path + "（追加 " + appended + " 行，写入 " + writes
                    + " 个指定单元格" + (backup ? "，原文件备份为 .bak" : "") + "）。");
        } catch (Exception e) {
            return ToolResult.error("编辑 xlsx 失败：" + e.getMessage());
        }
    }

    /**
     * 编辑已有 PPT：在演示文稿末尾追加幻灯片。
     * 执行逻辑：读取内容清单/演示文稿/关系三件套 -> 计算现有最大 slide 序号、
     * r:id 与 sldId -> 追加 slideN.xml 并同步三处引用 -> 备份后原子回写。
     * @param a 参数：path 必填；slides 与 create_ppt 相同（title+bullets）；backup 默认 true
     * @return 成功汇总追加页数；缺少关键结构或含危险内容时返回错误
     */
    public ToolResult editPpt(Map<String, Object> a) {
        String path = norm(asString(a.get("path")));
        if (path.isEmpty() || !path.toLowerCase(Locale.ROOT).endsWith(".pptx"))
            return ToolResult.error("路径必须指向已有的 .pptx 文件。");
        List<Map<String, Object>> slides = slides(a);
        if (slides.isEmpty()) return ToolResult.error("请通过 slides 提供要追加的幻灯片（title+bullets）。");
        StringBuilder danger = new StringBuilder();
        for (var s : slides) {
            danger.append(asString(s.get("title"))).append('\n');
            for (Object b : (List<?>) s.getOrDefault("bullets", List.of())) danger.append(asString(b)).append('\n');
        }
        var issues = DocSafety.detect(danger.toString());
        if (!issues.isEmpty())
            return ToolResult.error("幻灯片包含危险内容（" + String.join("、", issues) + "），禁止写入。");
        try {
            if (slides.size() > 200) slides = slides.subList(0, 200);
            Map<String, byte[]> zip = readAllEntries(path);
            String ctPath = "[Content_Types].xml";
            String presPath = "ppt/presentation.xml";
            String relsPath = "ppt/_rels/presentation.xml.rels";
            if (zip.get(presPath) == null || zip.get(relsPath) == null || zip.get(ctPath) == null)
                return ToolResult.error("不是有效的 pptx：缺少 presentation.xml / 关系文件 / 内容清单。");
            String ct = new String(zip.get(ctPath), StandardCharsets.UTF_8);
            String pres = new String(zip.get(presPath), StandardCharsets.UTF_8);
            String rels = new String(zip.get(relsPath), StandardCharsets.UTF_8);
            if (!pres.contains("<p:sldIdLst"))
                return ToolResult.error("presentation.xml 缺少幻灯片列表，无法追加。");
            int maxSlide = 0, maxRid = 1, maxSldId = 255;
            for (String n : zip.keySet()) {
                Matcher m = Pattern.compile("ppt/slides/slide(\\d+)\\.xml$").matcher(n);
                if (m.find()) maxSlide = Math.max(maxSlide, Integer.parseInt(m.group(1)));
            }
            Matcher ridm = Pattern.compile("\\bId=\"rId(\\d+)\"").matcher(rels);
            while (ridm.find()) maxRid = Math.max(maxRid, Integer.parseInt(ridm.group(1)));
            Matcher idm = Pattern.compile("<p:sldId\\b[^>]*\\bid=\"(\\d+)\"").matcher(pres);
            while (idm.find()) maxSldId = Math.max(maxSldId, Integer.parseInt(idm.group(1)));

            StringBuilder ctAdd = new StringBuilder(), presAdd = new StringBuilder(), relsAdd = new StringBuilder();
            for (int k = 0; k < slides.size(); k++) {
                int slideNo = maxSlide + 1 + k;
                int rid = maxRid + 1 + k;
                int sldId = maxSldId + 1 + k;
                String entry = "ppt/slides/slide" + slideNo + ".xml";
                if (zip.containsKey(entry)) continue;
                zip.put(entry, slideXml(slides.get(k)).getBytes(StandardCharsets.UTF_8));
                ctAdd.append("<Override PartName=\"/ppt/slides/slide").append(slideNo)
                        .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>");
                presAdd.append("<p:sldId id=\"").append(sldId).append("\" r:id=\"rId").append(rid).append("\"/>");
                relsAdd.append("<Relationship Id=\"rId").append(rid)
                        .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\"")
                        .append(" Target=\"slides/slide").append(slideNo).append(".xml\"/>");
            }
            ct = ct.replace("</Types>", ctAdd + "</Types>");
            pres = pres.replace("</p:sldIdLst>", presAdd + "</p:sldIdLst>");
            rels = rels.replace("</Relationships>", relsAdd + "</Relationships>");
            zip.put(ctPath, ct.getBytes(StandardCharsets.UTF_8));
            zip.put(presPath, pres.getBytes(StandardCharsets.UTF_8));
            zip.put(relsPath, rels.getBytes(StandardCharsets.UTF_8));
            boolean backup = !"false".equals(asString(a.getOrDefault("backup", "true")));
            rewriteZip(path, zip, backup);
            return ToolResult.ok("已在 PPT 末尾追加 " + slides.size() + " 页：" + path
                    + (backup ? "（原文件备份为 .bak）" : "") + "。");
        } catch (Exception e) {
            return ToolResult.error("编辑 pptx 失败：" + e.getMessage());
        }
    }

    // ---- 编辑辅助 ----
    /**
     * 读取 zip 全部条目到有序映射（键=条目名，值=原始字节），保留包内顺序。
     * 执行逻辑：有界枚举条目数 -> 逐条目流式严格读取（解压超过 20MB 即拒，
     * 不先全量入内存）-> 累计解压量超过 100MB 即拒，三重闸门防 zip 炸弹 OOM。
     * @param path OOXML 文件路径
     * @return 保持遍历顺序的条目映射
     * @throws IOException 文件不存在/超过 50MB/条目数超 1 万/单条目超 20MB/累计超 100MB 时抛出
     */
    private static Map<String, byte[]> readAllEntries(String path) throws IOException {
        File f = new File(path);
        if (!f.exists()) throw new IOException("文件不存在：" + path);
        if (f.length() > MAX_FILE) throw new IOException("文件超过 50MB 编辑上限。");
        Map<String, byte[]> map = new LinkedHashMap<>();
        try (ZipFile zf = new ZipFile(path)) {
            long total = 0;
            for (ZipEntry e : entriesBounded(zf)) {
                if (e.isDirectory()) continue;
                byte[] b = readEntryStrict(zf, e);
                total += b.length;
                if (total > MAX_TOTAL)
                    throw new IOException("解压后总大小超过 100MB 上限，疑似压缩炸弹。");
                map.put(e.getName(), b);
            }
        }
        return map;
    }

    /**
     * 备份后把全部条目原子重写到目标 OOXML 文件。
     * 执行逻辑：先复制原文件为 .bak（覆盖旧备份）-> 写临时文件 -> 移动覆盖原文件，
     * 任何中途失败都不会破坏原文件。
     * @param path   目标文件
     * @param files  完整条目集合（未修改条目也必须包含，原样回写）
     * @param backup true=在同目录保留一份 .bak 备份
     * @throws IOException 临时文件写入或移动失败时抛出
     */
    private static void rewriteZip(String path, Map<String, byte[]> files, boolean backup) throws IOException {
        Path target = Paths.get(path);
        if (backup) Files.copy(target, Paths.get(path + ".bak"),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Path tmp = Paths.get(path + ".tmp");
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp.toFile()), StandardCharsets.UTF_8)) {
            for (var e : files.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        moveReplaceWithRetry(tmp, target);
    }

    /**
     * 移动临时文件覆盖目标（Windows 友好）。
     * 执行逻辑：优先 ATOMIC_MOVE+REPLACE_EXISTING，文件系统不支持原子移动时
     * 退化为普通移动；Windows 上 Defender 实时扫描/搜索索引器常对刚生成的
     * .tmp 或目标文档产生几十至几百毫秒的句柄占用，导致 AccessDeniedException，
     * 因此对 IO 失败做最多 3 次、每次 150ms 的退避重试
     * （与回归测试 deleteRecursively 的既有重试策略一致）。
     * @param tmp    已写好的临时文件
     * @param target 被覆盖的目标文件
     * @throws IOException 3 次重试后仍失败时抛出最后一次 IO 异常；线程被中断时抛 IOException 包装
     */
    private static void moveReplaceWithRetry(Path tmp, Path target) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AtomicMoveNotSupportedException am) {
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                } catch (IOException e) {
                    last = e;
                }
            } catch (IOException e) {
                // 覆盖 Windows 文件锁导致的 AccessDeniedException 等瞬时失败，退避后重试
                last = e;
            }
            try {
                Thread.sleep(150);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("覆盖目标文件时线程被中断：" + target, ie);
            }
        }
        throw last;
    }

    /** 单元格内容安全判定：危险指令或公式注入（=/+/-/@ 开头）。 */
    private static boolean badCell(String v) {
        return !DocSafety.detect(v).isEmpty() || v.matches("^[=+\\-@].+");
    }

    /** 生成一个单元格 XML：纯整数/小数按数值类型，其余按 inlineStr 文本。 */
    private static String cellXml(String ref, String value) {
        if (value.matches("-?\\d+(\\.\\d+)?"))
            return "<c r=\"" + ref + "\"><v>" + value + "</v></c>";
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">"
                + escape(value) + "</t></is></c>";
    }

    /**
     * 解析 A1 引用为 {行号(1基), 列号(1基)}；非法返回 null。
     * @param ref 如 "B3"
     * @return int[]{3, 2} 或 null
     */
    private static int[] parseRef(String ref) {
        Matcher m = Pattern.compile("^([A-Z]{1,3})(\\d{1,7})$").matcher(ref == null ? "" : ref.trim().toUpperCase(Locale.ROOT));
        if (!m.find()) return null;
        return new int[]{Integer.parseInt(m.group(2)), colIndex(m.group(1)) + 1};
    }

    /** 列字母（A、B…AA）转 0 基列序号。 */
    private static int colIndex(String letters) {
        int n = 0;
        for (int i = 0; i < letters.length(); i++) n = n * 26 + (letters.charAt(i) - 'A' + 1);
        return n - 1;
    }

    /**
     * 经 workbook.xml 与其关系文件定位目标工作表的 zip 条目名。
     * @param zip  全部条目
     * @param name 工作表名；空白时取第一个工作表
     * @return 如 xl/worksheets/sheet1.xml；找不到返回 null
     */
    private static String resolveWorksheetEntry(Map<String, byte[]> zip, String name) {
        byte[] wbBytes = zip.get("xl/workbook.xml");
        byte[] relBytes = zip.get("xl/_rels/workbook.xml.rels");
        if (wbBytes == null || relBytes == null) return null;
        String wb = new String(wbBytes, StandardCharsets.UTF_8);
        String rels = new String(relBytes, StandardCharsets.UTF_8);
        Matcher sm = Pattern.compile("<sheet\\b[^>]*\\bname=\"([^\"]*)\"[^>]*\\br:id=\"([^\"]+)\"")
                .matcher(wb);
        String wantRid = null;
        String firstRid = null;
        while (sm.find()) {
            if (firstRid == null) firstRid = sm.group(2);
            if (!name.isEmpty() && sm.group(1).equals(name)) wantRid = sm.group(2);
        }
        String rid = wantRid != null ? wantRid : (name.isEmpty() ? firstRid : null);
        if (rid == null) return null;
        Matcher rm = Pattern.compile("<Relationship\\b[^>]*\\bId=\"" + Pattern.quote(rid)
                + "\"[^>]*\\bTarget=\"([^\"]+)\"").matcher(rels);
        if (!rm.find()) return null;
        String target = rm.group(1).replace('\\', '/');
        String entry = target.startsWith("/") ? target.substring(1)
                : target.startsWith("xl/") ? target : "xl/" + target;
        return zip.containsKey(entry) ? entry : null;
    }

    private static List<List<String>> stringRows(Object o) {
        List<List<String>> out = new ArrayList<>();
        if (o instanceof List<?> rows)
            for (Object r : rows)
                if (r instanceof List<?> cells) {
                    List<String> line = new ArrayList<>();
                    for (Object c : cells) line.add(asString(c));
                    out.add(line);
                }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> cellWrites(Object o) {
        return o instanceof List ? (List<Map<String, Object>>) o : List.of();
    }

    // ---- zip 工具 ----
    private static void writeZip(String outPath, Map<String, String> files) throws IOException {
        Files.createDirectories(Paths.get(outPath).getParent());
        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outPath), StandardCharsets.UTF_8)) {
            for (var e : files.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        }
    }

    private static byte[] readZipEntry(String zipPath, String entry) throws IOException {
        try (ZipFile zf = new ZipFile(zipPath)) { return readEntry(zf, entry); }
    }

    /**
     * 读取类场景的有界条目读取：最多返回 {@link #MAX_ENTRY} 字节，超出部分在
     * 流式阶段直接停止读取（ZipFile 关闭条目时丢弃残余），保持"截断读取"语义，
     * 高压缩比条目不会先撑爆堆内存。
     * @param zf   已打开的 zip 文件
     * @param name 条目名
     * @return 条目解压字节，长度不超过 {@link #MAX_ENTRY}
     * @throws IOException 条目缺失或解压 IO 失败时抛出
     */
    private static byte[] readEntry(ZipFile zf, String name) throws IOException {
        ZipEntry e = zf.getEntry(name);
        if (e == null) throw new IOException("压缩包缺少 " + name);
        try (InputStream in = zf.getInputStream(e)) {
            return readCapped(in, MAX_ENTRY);
        }
    }

    /**
     * 编辑类场景的严格有界条目读取：条目必须完整，解压字节超过
     * {@link #MAX_ENTRY} 立即抛错（截断会损坏文档结构），上限在流式读取阶段生效。
     * @param zf 已打开的 zip 文件
     * @param e  目标条目
     * @return 完整条目字节
     * @throws IOException 条目解压后超过 20MB 或 IO 失败时抛出
     */
    private static byte[] readEntryStrict(ZipFile zf, ZipEntry e) throws IOException {
        try (InputStream in = zf.getInputStream(e)) {
            // 多读 1 字节用于探测超限，避免把超长条目完整读入后再判断
            byte[] b = readCapped(in, MAX_ENTRY + 1);
            if (b.length > MAX_ENTRY) throw new IOException("条目过大（超过 20MB）：" + e.getName());
            return b;
        }
    }

    /**
     * 边解压边计数的流式拷贝：达到上限立即停止，杜绝 transferTo 全量入内存
     * 导致的 zip 炸弹 OOM。
     * @param in  zip 条目解压输入流
     * @param cap 最多读取字节数
     * @return 实际读取的字节（长度 ≤ cap）
     * @throws IOException 读取过程发生 IO 错误时抛出
     */
    private static byte[] readCapped(InputStream in, int cap) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.min(cap, 64 * 1024));
        byte[] buf = new byte[16 * 1024];
        int remaining = cap;
        int n;
        while (remaining > 0 && (n = in.read(buf, 0, Math.min(buf.length, remaining))) != -1) {
            bos.write(buf, 0, n);
            remaining -= n;
        }
        return bos.toByteArray();
    }

    /**
     * 有界枚举 zip 条目：条目数超过 {@link #MAX_ENTRIES} 立即判定为恶意包，
     * 避免 {@code Collections.list} 把海量中央目录项全部物化进内存。
     * @param zf 已打开的 zip 文件
     * @return 全部条目（数量 ≤ {@link #MAX_ENTRIES}）
     * @throws IOException 条目数超过上限时抛出
     */
    private static List<ZipEntry> entriesBounded(ZipFile zf) throws IOException {
        List<ZipEntry> out = new ArrayList<>();
        Enumeration<? extends ZipEntry> en = zf.entries();
        while (en.hasMoreElements()) {
            out.add(en.nextElement());
            if (out.size() > MAX_ENTRIES)
                throw new IOException("压缩包条目数超过上限（" + MAX_ENTRIES + "），疑似压缩炸弹。");
        }
        return out;
    }

    private static int slideNum(String n) {
        Matcher m = Pattern.compile("slide(\\d+)\\.xml").matcher(n);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    private static String stripXml(String xml) {
        return xml.replaceAll("(?s)<w:tab\\s*/>", "\t")
                .replaceAll("(?s)</w:p>", "\n").replaceAll("(?s)</a:p>", "\n").replaceAll("(?s)<br\\s*/>", "\n")
                .replaceAll("<[^>]+>", "").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&apos;", "'").trim();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
    private static String escapeAttr(String s) { return escape(s).replace("\"", "&quot;"); }
    private static String truncate(String s, int n) { return s.length() > n ? s.substring(0, n) + "…" : s; }
    private static String asString(Object o) { return o == null ? "" : String.valueOf(o); }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> paras(Map<String, Object> a) {
        Object o = a.get("paragraphs");
        return o instanceof List ? (List<Map<String, Object>>) o : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sheets(Map<String, Object> a) {
        Object o = a.get("sheets");
        return o instanceof List ? (List<Map<String, Object>>) o : List.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> slides(Map<String, Object> a) {
        Object o = a.get("slides");
        return o instanceof List ? (List<Map<String, Object>>) o : List.of();
    }
}
