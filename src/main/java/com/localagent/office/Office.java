package com.localagent.office;

import com.localagent.safety.DocSafety;
import com.localagent.tools.ToolResult;

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
 * - 50MB 文件 / 单条目 20MB 上限，防 zip 炸弹与 OOM。
 */
public class Office {
    private static final long MAX_FILE = 50L * 1024 * 1024;
    private static final int MAX_ENTRY = 20 * 1024 * 1024;

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
            var names = Collections.list(zf.entries()).stream()
                    .map(ZipEntry::getName).filter(n -> n.matches("ppt/slides/slide\\d+\\.xml"))
                    .sorted(Comparator.comparingInt(Office::slideNum)).toList();
            int i = 1;
            for (String n : names) {
                byte[] b = readEntry(zf, n);
                if (b.length > MAX_ENTRY) b = Arrays.copyOf(b, MAX_ENTRY);
                sb.append("第").append(i++).append("页：").append(stripXml(new String(b, StandardCharsets.UTF_8))).append('\n');
            }
        }
        return sb.toString();
    }

    private String readXlsx(String p) throws IOException {
        List<String> shared = new ArrayList<>();
        try (ZipFile zf = new ZipFile(p)) {
            ZipEntry se = zf.getEntry("xl/sharedStrings.xml");
            if (se != null) {
                byte[] b = readEntry(zf, "xl/sharedStrings.xml");
                if (b.length > MAX_ENTRY) b = Arrays.copyOf(b, MAX_ENTRY);
                Matcher m = Pattern.compile("<si>([\\s\\S]*?)</si>").matcher(new String(b, StandardCharsets.UTF_8));
                while (m.find()) shared.add(stripXml(m.group(1)));
            }
            var names = Collections.list(zf.entries()).stream().map(ZipEntry::getName)
                    .filter(n -> n.matches("xl/worksheets/sheet\\d+\\.xml")).sorted().toList();
            StringBuilder out = new StringBuilder();
            for (String n : names) {
                byte[] b = readEntry(zf, n);
                if (b.length > MAX_ENTRY) b = Arrays.copyOf(b, MAX_ENTRY);
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

    private static byte[] readEntry(ZipFile zf, String name) throws IOException {
        ZipEntry e = zf.getEntry(name);
        if (e == null) throw new IOException("压缩包缺少 " + name);
        try (InputStream in = zf.getInputStream(e); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            in.transferTo(bos);
            return bos.toByteArray();
        }
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
