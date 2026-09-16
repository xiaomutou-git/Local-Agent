package com.localagent.tools;

import com.localagent.toolkit.Proc;
import com.localagent.toolkit.ToolDef;
import com.localagent.toolkit.ToolResult;
import com.localagent.config.Config;
import com.localagent.knowledge.Knowledge;
import com.localagent.memory.MemoryStore;
import com.localagent.nativelib.NativeBridge;
import com.localagent.ollama.OllamaClient;
import com.localagent.office.Office;
import com.localagent.scheduler.ReminderScheduler;
import com.localagent.safety.Safety;
import com.localagent.tts.Tts;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 工具注册表与执行器（移植 tools.js 30+ 工具）。
 *
 * 执行链路：Agent -> Safety.checkTool（分级/阻止）-> Tools.execute（实际动作）。
 * 安全语义与 Electron 版对齐：shell:false 参数数组、read_file 体积预检、
 * 截图落盘走写入检查、APP_MAP 不含 cmd/powershell、remember 需确认。
 */
public class Tools {
    private final OllamaClient ollama;
    private final MemoryStore memory;
    private final Knowledge knowledge;
    private final ReminderScheduler scheduler;
    private final Tts tts;
    private final Office office = new Office();

    public Tools(OllamaClient ollama, MemoryStore memory, Knowledge knowledge,
                 ReminderScheduler scheduler, Tts tts) {
        this.ollama = ollama;
        this.memory = memory;
        this.knowledge = knowledge;
        this.scheduler = scheduler;
        this.tts = tts;
    }

    // ---- 工具元数据（提供给模型）----
    public List<ToolDef> list() { return DEFS; }
    public ToolDef get(String name) {
        for (ToolDef d : DEFS) if (d.name().equals(name)) return d;
        return null;
    }
    public boolean isMemoryTool(String n) { return Set.of("remember", "forget", "recall").contains(n); }
    public boolean isKnowledgeTool(String n) { return n.equals("search_knowledge"); }

    /**
     * 构造工具定义并按工具名自动绑定参数 Schema（Schema 集中维护于 {@link ToolSchemas}）。
     * @param name        工具名
     * @param description 面向模型的功能描述
     * @param risk        安全分级 auto/confirm/dangerous
     * @return 携带参数 Schema 的工具定义
     */
    private static ToolDef td(String name, String description, String risk) {
        return new ToolDef(name, description, risk, ToolSchemas.of(name));
    }

    private static final List<ToolDef> DEFS = List.of(
            td("list_directory", "列出某个目录下的文件和子目录。参数 path 必须是绝对路径。", "auto"),
            td("get_file_info", "查看文件或目录的类型、大小、修改时间。", "auto"),
            td("read_file", "以 UTF-8 读取文本文件（50MB 以内）。读取敏感目录（如 .ssh）或凭据文件时需确认。", "auto"),
            td("list_drives", "列出电脑所有磁盘分区及可用空间。", "auto"),
            td("get_system_info", "获取操作系统、CPU 核数、内存、用户名等信息。", "auto"),
            td("get_current_time", "获取当前系统日期与时间（含星期、时区、与 UTC 的偏移、Unix 时间戳）。用户提到今天/明天/后天/下周一/几点几分等相对时间，或需要设置时间相关提醒时，应先调用本工具确认基准时间。", "auto"),
            td("write_file", "创建或覆盖写入文本文件（单次最多 10MB）。", "confirm"),
            td("create_directory", "创建目录（含父目录）。", "confirm"),
            td("copy_file", "复制文件。源与目标均校验保护目录。", "confirm"),
            td("move_file", "移动/重命名文件。", "confirm"),
            td("delete_file", "永久删除文件（不可恢复）。", "dangerous"),
            td("delete_directory", "永久删除目录（不可恢复）。", "dangerous"),
            td("run_command", "执行程序或命令（危险）。白名单程序（记事本/只读查询/git 等）可直接审批；cmd/powershell 会二级检查危险操作，删除/下载/持久化等会被硬阻止。args 为参数数组。", "dangerous"),
            td("open_in_explorer", "在资源管理器中打开并选中文件。", "confirm"),
            td("open_file", "用系统默认程序打开文件。.hta/.chm/.exe 等可执行类型按危险处理。", "confirm"),
            td("open_app", "打开常用名应用（记事本/计算器/画图/任务管理器/资源管理器等）；不支持命令提示符/终端。", "confirm"),
            td("take_screenshot", "截取主显示器保存为 PNG。不传 path 时存到图片/本机助手截图。", "confirm"),
            td("analyze_image", "让视觉模型分析本地图片（需 -vl/-vision 模型）。", "auto"),
            td("get_foreground_window", "查看当前前台窗口标题。", "auto"),
            td("list_processes", "列出占用内存最高的进程（只读）。", "auto"),
            td("kill_process", "结束进程（危险）。进程名白名单校验，仅允许普通用户进程。", "dangerous"),
            td("list_installed_apps", "列出已安装软件（注册表卸载项，只读）。", "auto"),
            td("search_files", "在目录中按文件名通配符搜索（默认用户主目录，只读）。", "auto"),
            td("create_ppt", "根据内容生成 .pptx。", "confirm"),
            td("create_docx", "创建 .docx Word 文档。", "confirm"),
            td("create_xlsx", "创建 .xlsx 表格。", "confirm"),
            td("read_office", "提取 .pptx/.docx/.xlsx 文本（50MB 以内，含危险内容拦截）。", "auto"),
            td("edit_docx", "编辑已有 .docx：文末追加段落，或在同一文本节点内做 find/replace 替换；自动备份 .bak。", "confirm"),
            td("edit_xlsx", "编辑已有 .xlsx：按工作表追加多行或按 A1 引用写入单元格；自动备份 .bak。", "confirm"),
            td("edit_ppt", "编辑已有 .pptx：在演示文稿末尾追加幻灯片（title+bullets）；自动备份 .bak。", "confirm"),
            td("search_knowledge", "在本地知识库中检索资料片段（BM25）。", "auto"),
            td("schedule_reminder", "设定提醒（at=ISO 时间 或 delayMinutes=分钟，repeat=once/hourly/daily/weekly）。", "auto"),
            td("cancel_reminder", "取消提醒。", "confirm"),
            td("list_reminders", "查看未触发的提醒。", "auto"),
            td("remember", "把用户明确要求长期记住的信息保存为记忆（保存前需确认，含危险指令过滤）。", "confirm"),
            td("forget", "删除一条记忆。", "confirm"),
            td("recall", "搜索已有记忆。", "auto"),
            td("list_recycle_bin", "查看回收站内容（只读）。", "auto"),
            td("empty_recycle_bin", "清空回收站（不可恢复）。", "dangerous"),
            td("speak", "用系统语音朗读文本（离线）。", "auto"));

    // ---- 执行分发 ----
    public ToolResult execute(String name, Map<String, Object> args) {
        try {
            return switch (name) {
                case "list_directory" -> listDirectory(args);
                case "get_file_info" -> fileInfo(args);
                case "read_file" -> readFile(args);
                case "list_drives" -> listDrives(args);
                case "get_system_info" -> systemInfo(args);
                case "get_current_time" -> currentTime(args);
                case "write_file" -> writeFile(args);
                case "create_directory" -> createDirectory(args);
                case "copy_file" -> copyFile(args, false);
                case "move_file" -> copyFile(args, true);
                case "delete_file" -> deleteFile(args);
                case "delete_directory" -> deleteDirectory(args);
                case "run_command" -> runCommand(args);
                case "open_in_explorer" -> openInExplorer(args);
                case "open_file" -> openFile(args);
                case "open_app" -> openApp(args);
                case "take_screenshot" -> screenshot(args);
                case "analyze_image" -> analyzeImage(args);
                case "get_foreground_window" -> ToolResult.ok("当前前台窗口：" + (NativeBridge.foregroundWindowTitle().isEmpty() ? "(无)" : NativeBridge.foregroundWindowTitle()));
                case "list_processes" -> listProcesses(args);
                case "kill_process" -> killProcess(args);
                case "list_installed_apps" -> listInstalledApps(args);
                case "search_files" -> searchFiles(args);
                case "create_ppt" -> office.createPpt(args);
                case "create_docx" -> office.createDocx(args);
                case "create_xlsx" -> office.createXlsx(args);
                case "read_office" -> office.readOffice(args);
                case "edit_docx" -> office.editDocx(args);
                case "edit_xlsx" -> office.editXlsx(args);
                case "edit_ppt" -> office.editPpt(args);
                case "search_knowledge" -> searchKnowledge(args);
                case "schedule_reminder" -> scheduler.schedule(args);
                case "cancel_reminder" -> scheduler.remove(str(args.get("id")));
                case "list_reminders" -> scheduler.listTool();
                case "remember" -> remember(args);
                case "forget" -> memory.remove(str(args.get("id"))).isPresent()
                        ? ToolResult.ok("已删除记忆。") : ToolResult.error("没有找到该记忆。");
                case "recall" -> recall(args);
                case "list_recycle_bin" -> ToolResult.ok("回收站内容查询（JVM 版）：请通过系统回收站查看。");
                case "empty_recycle_bin" -> NativeBridge.emptyRecycleBin()
                        ? ToolResult.ok("回收站已清空。") : ToolResult.error("清空回收站失败或 native 库不可用。");
                case "speak" -> speak(args);
                default -> ToolResult.error("不存在的工具：" + name);
            };
        } catch (Exception e) {
            return ToolResult.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    // ================= 文件类 =================
    private ToolResult listDirectory(Map<String, Object> a) throws Exception {
        String p = Safety.normalizePath(str(a.get("path")));
        if (p == null) return ToolResult.error("路径无效。");
        File d = new File(p);
        if (!d.isDirectory()) return ToolResult.error("这不是目录：" + p);
        File[] es = d.listFiles();
        if (es == null) return ToolResult.error("无法列目录。");
        StringBuilder sb = new StringBuilder(p).append('\n');
        int n = 0;
        for (File e : es) {
            if (n++ >= 500) { sb.append("\n… 还有更多项未显示"); break; }
            sb.append(e.isDirectory() ? "[目录] " : "[文件] ").append(e.getName());
            if (e.isFile()) sb.append(' ').append(fmtBytes(e.length()));
            sb.append('\n');
        }
        return ToolResult.ok(sb.toString().trim());
    }

    private ToolResult fileInfo(Map<String, Object> a) {
        String p = Safety.normalizePath(str(a.get("path")));
        if (p == null) return ToolResult.error("路径无效。");
        File f = new File(p);
        if (!f.exists()) return ToolResult.error("路径不存在：" + p);
        DateTimeFormatter f2 = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return ToolResult.ok("""
                %s
                类型：%s
                大小：%s
                修改时间：%s
                创建时间：%s""".formatted(p, f.isDirectory() ? "目录" : "文件",
                f.isDirectory() ? "-" : fmtBytes(f.length()),
                f2.format(java.time.Instant.ofEpochMilli(f.lastModified()).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()),
                f2.format(java.time.Instant.ofEpochMilli(f.lastModified()).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime())));
    }

    private ToolResult readFile(Map<String, Object> a) throws Exception {
        String p = Safety.normalizePath(str(a.get("path")));
        if (p == null) return ToolResult.error("路径无效。");
        File f = new File(p);
        if (!f.exists()) return ToolResult.error("无法读取文件（不存在）：" + p);
        if (f.length() > 50L * 1024 * 1024) return ToolResult.error("文件过大（" + fmtBytes(f.length()) + "），仅支持 50MB 以内文本。");
        int limit = Math.max(1024, Math.min(intArg(a.get("limit"), Config.getInt("readLimit", 32000)), 500000));
        byte[] buf = Files.readAllBytes(f.toPath());
        if (containsNul(buf)) return ToolResult.ok("[二进制文件，大小 " + buf.length + " 字节，无法以文本显示]");
        String text = new String(buf, StandardCharsets.UTF_8);
        if (buf.length > limit) text = text.substring(0, limit) + "\n……（内容过长，仅显示前 " + limit + " 字节）";
        return ToolResult.ok(text);
    }

    private static boolean containsNul(byte[] b) {
        for (byte x : b) if (x == 0) return true;
        return false;
    }

    private ToolResult writeFile(Map<String, Object> a) throws Exception {
        String p = Safety.normalizePath(str(a.get("path")));
        if (p == null) return ToolResult.error("路径无效。");
        String content = str(a.get("content"));
        if (content == null) content = "";
        if (content.getBytes(StandardCharsets.UTF_8).length > 10 * 1024 * 1024)
            return ToolResult.error("内容过大，单次最多 10MB。");
        Files.createDirectories(Paths.get(p).getParent());
        Files.writeString(Paths.get(p), content, StandardCharsets.UTF_8);
        return ToolResult.ok("已写入文件（" + content.length() + " 字符）：" + p);
    }

    private ToolResult createDirectory(Map<String, Object> a) {
        String p = Safety.normalizePath(str(a.get("path")));
        if (p == null) return ToolResult.error("路径无效。");
        try { Files.createDirectories(Paths.get(p)); return ToolResult.ok("已创建目录：" + p); }
        catch (Exception e) { return ToolResult.error("创建目录失败：" + e.getMessage()); }
    }

    private ToolResult copyFile(Map<String, Object> a, boolean move) throws Exception {
        String s = Safety.normalizePath(str(a.get("source")));
        String t = Safety.normalizePath(str(a.get("target")));
        if (s == null || t == null) return ToolResult.error("路径无效。");
        Files.createDirectories(Paths.get(t).getParent());
        if (move) Files.move(Paths.get(s), Paths.get(t), StandardCopyOption.REPLACE_EXISTING);
        else Files.copy(Paths.get(s), Paths.get(t), StandardCopyOption.REPLACE_EXISTING);
        return ToolResult.ok((move ? "已移动：" : "已复制：") + s + " → " + t);
    }

    private ToolResult deleteFile(Map<String, Object> a) {
        String p = Safety.normalizePath(str(a.get("path")));
        try { Files.deleteIfExists(Paths.get(p)); return ToolResult.ok("已删除文件：" + p); }
        catch (Exception e) { return ToolResult.error("删除失败：" + e.getMessage()); }
    }

    private ToolResult deleteDirectory(Map<String, Object> a) {
        String p = Safety.normalizePath(str(a.get("path")));
        if ("\\".equals(p) || Paths.get(p).getRoot() != null && Paths.get(p).getNameCount() == 0)
            return ToolResult.error("禁止删除磁盘根目录。");
        try {
            Path pp = Paths.get(p);
            if (!Files.exists(pp)) return ToolResult.error("目录不存在。");
            try (var walk = Files.walk(pp)) { walk.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.deleteIfExists(x); } catch (IOException ignored) {} }); }
            return ToolResult.ok("已删除目录：" + p);
        } catch (Exception e) { return ToolResult.error("删除目录失败：" + e.getMessage()); }
    }

    // ================= 执行类 =================
    private ToolResult runCommand(Map<String, Object> a) throws Exception {
        String program = str(a.get("program"));
        if (program == null || program.isBlank()) return ToolResult.error("缺少 program。");
        List<String> argv = strList(a.get("args"));
        String cwd = Safety.normalizePath(str(a.get("cwd")));
        long timeout = Math.min(intArg(a.get("timeout"), Config.getInt("cmdTimeout", 120000)), 600000);
        Proc.Result r = Proc.exec(program, argv, cwd == null ? System.getProperty("user.home") : cwd, timeout, null);
        StringBuilder msg = new StringBuilder("命令已执行，退出码：").append(r.code()).append('\n');
        String out = r.out();
        String err = r.err();
        if (out != null && !out.isBlank()) msg.append("标准输出：\n").append(out.length() > 40000 ? out.substring(0, 40000) : out);
        if (err != null && !err.isBlank()) msg.append("\n错误输出：\n").append(err.length() > 15000 ? err.substring(0, 15000) : err);
        return ToolResult.ok(msg.toString());
    }

    private ToolResult openInExplorer(Map<String, Object> a) throws Exception {
        String p = Safety.normalizePath(str(a.get("path")));
        if (p == null || !new File(p).exists()) return ToolResult.error("路径不存在。");
        Desktop.getDesktop().open(new File(p).isDirectory() ? new File(p) : new File(p).getParentFile());
        return ToolResult.ok("已在资源管理器打开。");
    }

    private ToolResult openFile(Map<String, Object> a) throws Exception {
        String p = Safety.normalizePath(str(a.get("path")));
        if (p == null || !new File(p).exists()) return ToolResult.error("路径不存在。");
        Desktop.getDesktop().open(new File(p));
        return ToolResult.ok("已调用系统默认程序打开：" + p);
    }

    private static final Map<String, String> APP_MAP = Map.ofEntries(
            Map.entry("notepad", "notepad"), Map.entry("记事本", "notepad"),
            Map.entry("calc", "calc"), Map.entry("计算器", "calc"),
            Map.entry("mspaint", "mspaint"), Map.entry("画图", "mspaint"),
            Map.entry("taskmgr", "taskmgr"), Map.entry("任务管理器", "taskmgr"),
            Map.entry("explorer", "explorer"), Map.entry("资源管理器", "explorer"),
            Map.entry("control", "control"), Map.entry("控制面板", "control"),
            Map.entry("write", "write"), Map.entry("写字板", "write"), Map.entry("winver", "winver"));

    private ToolResult openApp(Map<String, Object> a) throws Exception {
        String raw = str(a.get("path"));
        if (raw != null && !raw.isBlank()) {
            String p = Safety.normalizePath(raw);
            if (p != null && new File(p).exists()) { Desktop.getDesktop().open(new File(p)); return ToolResult.ok("已打开：" + p); }
            return ToolResult.error("路径不存在。");
        }
        String nm = str(a.get("name") != null ? a.get("name") : a.get("app"));
        if (nm == null) return ToolResult.error("缺少 name/path。");
        String app = APP_MAP.get(nm.toLowerCase(Locale.ROOT));
        if (app == null) return ToolResult.error("不认识这个应用：" + nm + "。命令提示符/终端请改用 run_command。");
        new ProcessBuilder(app).start();
        return ToolResult.ok("已打开应用：" + app);
    }

    // ================= 屏幕/图像 =================
    private ToolResult screenshot(Map<String, Object> a) throws Exception {
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        BufferedImage img = new Robot().createScreenCapture(screen);
        String outPath;
        Object op = a.get("path");
        if (op != null && !String.valueOf(op).isBlank()) {
            outPath = Safety.normalizePath(str(op));
        } else {
            Path base = Paths.get(System.getProperty("user.home"), "Pictures", "本机助手截图");
            Files.createDirectories(base);
            outPath = base.resolve("截图-" + System.currentTimeMillis() + ".png").toString();
        }
        Files.createDirectories(Paths.get(outPath).getParent());
        ImageIO.write(img, "png", new File(outPath));
        return ToolResult.ok("已截图：" + outPath, Map.of("path", outPath, "size", img.getWidth() + "x" + img.getHeight()));
    }

    /**
     * 图像分析单文件体积上限：20MB。
     * 依据：读入后需做 Base64 编码（体积膨胀约 1.33 倍）并随请求驻留内存，
     * 较 readFile 的 50MB 文本上限更严格，避免超大图造成堆压力与请求体超限。
     */
    private static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;

    /**
     * 读取本地图片转 Base64 后交视觉模型问答。
     * 执行逻辑：路径规范化 -> 存在性与 20MB 体积预检（拒绝后不读入内存）->
     * Base64 编码 -> 调用 Ollama 视觉接口。
     * @param a 工具参数：path（图片绝对路径，必填）、question（提问文本）
     * @return 模型回答；路径无效/文件缺失/超限时返回 error
     * @throws Exception 读取文件或模型请求 IO 失败时向上抛出（由调用方统一兜底）
     */
    private ToolResult analyzeImage(Map<String, Object> a) throws Exception {
        String p = Safety.normalizePath(str(a.get("path")));
        if (p == null) return ToolResult.error("路径无效。");
        File f = new File(p);
        if (!f.exists()) return ToolResult.error("无法读取图片（不存在）：" + p);
        if (f.length() > MAX_IMAGE_BYTES)
            return ToolResult.error("图片过大（" + fmtBytes(f.length()) + "），仅支持 20MB 以内图像。");
        byte[] buf = Files.readAllBytes(f.toPath());
        String b64 = Base64.getEncoder().encodeToString(buf);
        String text = ollama.askImage(Config.getString("model", ""), str(a.get("question")), b64);
        return text.isBlank() ? ToolResult.error("模型没有返回内容。") : ToolResult.ok(text);
    }

    // ================= 进程/软件 =================
    private ToolResult listProcesses(Map<String, Object> a) {
        int n = Math.max(1, Math.min(intArg(a.get("limit"), 15), 50));
        String ps = """
                Get-Process | Sort-Object WorkingSet64 -Descending | Select-Object -First %d | ForEach-Object {
                  '{0,-30} PID={1,-7} Mem={2}MB CPU={3}s' -f $_.Name, $_.Id, [math]::Round($_.WorkingSet64/1MB,1), [math]::Round($_.CPU,1)
                }""" .formatted(n);
        String out = Proc.pwsh(ps, 20);
        return ToolResult.ok("内存占用最高的 " + n + " 个进程：\n" + (out.isBlank() ? "(无输出)" : out));
    }

    private static final Pattern PROC_NAME_OK = Pattern.compile("^[a-zA-Z0-9_\\- ]+$");
    private ToolResult killProcess(Map<String, Object> a) {
        Integer pid = a.get("pid") == null ? null : Integer.valueOf(String.valueOf(a.get("pid")));
        String name = str(a.get("name"));
        if (pid != null) {
            String r = Proc.pwsh("(Get-Process -Id " + pid + " -ErrorAction SilentlyContinue) | Stop-Process -Force -ErrorAction SilentlyContinue; if(Get-Process -Id " + pid + " -ErrorAction SilentlyContinue){'still'}else{'done'}", 20);
            return ToolResult.ok(r.contains("done") ? "已结束进程 PID=" + pid : "未结束（可能需要更高权限）。");
        }
        if (name == null || !PROC_NAME_OK.matcher(name.trim()).matches()) return ToolResult.error("进程名格式无效。");
        Proc.pwsh("Get-Process -Name '" + name.trim().replace("'", "") + "' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue; 'ok'", 20);
        return ToolResult.ok("已尝试结束进程：" + name);
    }

    private ToolResult listInstalledApps(Map<String, Object> a) {
        int limit = Math.max(1, Math.min(intArg(a.get("limit"), 80), 200));
        String kw = str(a.get("keyword"));
        String filter = (kw == null || kw.isBlank()) ? "" : " | Where-Object { $_.DisplayName -like '*" + kw.replace("'", "") + "*' }";
        String ps = "$paths='HKLM:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*','HKLM:\\Software\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*','HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\*'; Get-ItemProperty $paths -ErrorAction SilentlyContinue | Where-Object DisplayName" + filter + " | Select-Object -First " + limit + " -ExpandProperty DisplayName | Sort-Object -Unique";
        String out = Proc.pwsh(ps, 25);
        return ToolResult.ok(out.isBlank() ? "未读取到已安装软件。" : out);
    }

    // ================= 搜索 =================
    private ToolResult searchFiles(Map<String, Object> a) {
        String name = str(a.get("name"));
        if (name == null || name.isBlank()) return ToolResult.error("请提供文件名模式。");
        String root = Safety.normalizePath(str(a.get("root")));
        if (root == null) root = System.getProperty("user.home");
        int limit = Math.max(1, Math.min(intArg(a.get("limit"), 50), 500));
        List<String> results = new ArrayList<>();
        try (var walk = Files.walk(Paths.get(root), 8)) {
            var it = walk.filter(p -> p.getFileName() != null && globMatch(p.getFileName().toString(), name)).iterator();
            while (it.hasNext() && results.size() < limit) results.add(it.next().toString());
        } catch (Exception e) { return ToolResult.error("搜索出错：" + e.getMessage()); }
        return ToolResult.ok(results.isEmpty() ? "未找到匹配「" + name + "」的文件。" : "找到 " + results.size() + " 个：\n" + String.join("\n", results));
    }

    private static boolean globMatch(String fileName, String glob) {
        StringBuilder rx = new StringBuilder("(?i)^");
        for (char c : glob.toCharArray()) {
            switch (c) { case '*' -> rx.append(".*"); case '?' -> rx.append('.'); case '.', '\\', '+', '(', ')', '[', ']', '{', '}', '$', '^', '|' -> rx.append('\\').append(c); default -> rx.append(c); }
        }
        return fileName.matches(rx.toString());
    }

    // ================= 知识/记忆/语音 =================
    private ToolResult searchKnowledge(Map<String, Object> a) throws Exception {
        String q = str(a.get("query"));
        if (q == null || q.isBlank()) return ToolResult.error("缺少 query。");
        int limit = Math.max(1, Math.min(intArg(a.get("limit"), 5), 20));
        // 索引后台构建期间不阻塞等待：明确告知模型稍后重试，而不是误报"没有相关内容"
        if (!knowledge.isReady()) {
            return ToolResult.ok(knowledge.isIndexing()
                    ? "知识库正在后台建立索引，请稍后（约十几秒）再次检索。"
                    : "知识库索引尚未就绪，请稍后再试；若持续如此请在设置中点击「重建索引」。");
        }
        var results = knowledge.search(q, limit);
        if (results.isEmpty()) return ToolResult.ok("知识库中没有找到相关内容。");
        StringBuilder sb = new StringBuilder("知识库检索结果：\n");
        for (var c : results) sb.append("[来源：").append(c.source()).append("]\n").append(c.text()).append("\n\n");
        return ToolResult.ok(sb.toString().trim());
    }

    private ToolResult remember(Map<String, Object> a) {
        String text = str(a.get("text"));
        String cat = str(a.get("category"));
        var r = memory.add(text, cat, "assistant");
        return r.error() != null ? ToolResult.error(r.error()) : ToolResult.ok("已记住：" + r.text());
    }

    private ToolResult recall(Map<String, Object> a) {
        String q = str(a.get("query"));
        int limit = Math.max(1, Math.min(intArg(a.get("limit"), 10), 20));
        var items = memory.search(q, limit);
        if (items.isEmpty()) return ToolResult.ok("没有找到相关记忆。");
        StringBuilder sb = new StringBuilder("记忆：\n");
        int i = 0;
        for (var m : items) sb.append(++i).append(". [").append(m.category()).append("] ").append(m.text()).append('\n');
        return ToolResult.ok(sb.toString().trim());
    }

    private ToolResult speak(Map<String, Object> a) {
        String text = str(a.get("text"));
        if (text == null || text.isBlank()) return ToolResult.error("没有可朗读的内容。");
        return tts.speak(text) ? ToolResult.ok("已朗读。") : ToolResult.error("本机没有可用语音或朗读失败。");
    }

    // ================= 辅助 =================
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static int intArg(Object o, int dflt) {
        if (o == null) return dflt;
        try { return (int) Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return dflt; }
    }
    private static List<String> strList(Object o) {
        if (o instanceof List<?> l) { List<String> r = new ArrayList<>(); for (Object x : l) r.add(String.valueOf(x)); return r; }
        return new ArrayList<>();
    }
    static String fmtBytes(long n) {
        if (n >= 1024 * 1024) return String.format(Locale.ROOT, "%.1fMB", n / 1048576.0);
        if (n >= 1024) return Math.round(n / 1024) + "KB";
        return n + "B";
    }

    private ToolResult listDrives(Map<String, Object> a) {
        StringBuilder sb = new StringBuilder("磁盘分区：\n");
        for (Path root : FileSystems.getDefault().getRootDirectories()) {
            try {
                FileStore store = Files.getFileStore(root);
                sb.append(root).append(" 总 ").append(fmtBytes(store.getTotalSpace()))
                  .append(" 可用 ").append(fmtBytes(store.getUsableSpace())).append('\n');
            } catch (Exception e) { sb.append(root).append('\n'); }
        }
        return ToolResult.ok(sb.toString().trim());
    }

    /**
     * 返回当前系统时间的多格式描述。
     * 执行逻辑：直接读取系统默认时区的当前时刻，格式化为本地文本、ISO-8601 与
     * Unix 秒，供模型解析"明天/下周一"等相对时间表达并换算提醒时刻。
     * @param a 工具参数（本工具无参数，传入空 Map 即可）
     * @return 始终 ok；包含本地日期时间、中文星期、时区 ID、UTC 偏移、Unix 秒
     */
    private ToolResult currentTime(Map<String, Object> a) {
        java.time.ZonedDateTime now = java.time.ZonedDateTime.now();
        String[] weekdays = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        String week = weekdays[now.getDayOfWeek().getValue() - 1];
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String offset = now.getOffset().getId();
        return ToolResult.ok("""
                当前系统时间：%s %s
                时区：%s（UTC%s）
                ISO-8601：%s
                Unix 时间戳（秒）：%d
                说明：用户提到的今天/明天/后天/星期几/几点几分等相对时间，均以上述时间为基准换算。""".formatted(
                f.format(now), week, now.getZone().getId(),
                "+00:00".equals(offset) ? "+00:00" : offset,
                now.toInstant().toString(), now.toEpochSecond()));
    }

    private ToolResult systemInfo(Map<String, Object> a) {
        var rt = Runtime.getRuntime();
        return ToolResult.ok("""
                操作系统：%s %s（%s）
                电脑名：%s
                用户名：%s
                CPU 核数：%d
                内存：已用 %.1fGB / 总 %.1fGB
                Java：%s""".formatted(System.getProperty("os.name"), System.getProperty("os.version"), System.getProperty("os.arch"),
                tryHostname(), System.getProperty("user.name"), rt.availableProcessors(),
                (rt.totalMemory() - rt.freeMemory()) / 1073741824.0, rt.totalMemory() / 1073741824.0,
                System.getProperty("java.version")));
    }
    private static String tryHostname() {
        try { return java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception e) { return "未知"; }
    }
}
