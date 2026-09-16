package com.localagent.safety;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 安全判定核心（完整移植并对齐 Electron 版 safety.js 第二轮复测后的最终形态）。
 *
 * 覆盖：
 * 1) 路径保护：系统关键目录/文件硬阻止，junction/符号链接 realpath 识别；
 * 2) 命令执行：黑名单硬阻止 + cmd/powershell 代理程序二级解析 + 完整命令行展示；
 * 3) 读取保护：敏感目录（.ssh/.aws 等）/凭据文件升级为需确认；
 * 4) 危险扩展名：hta/chm/jse 等打开即代码执行的类型；
 * 5) 移动/复制源校验、文档生成/截图落盘统一走写入检查。
 *
 * 本类无状态、纯函数式判定，便于单元回归。
 */
public final class Safety {
    private Safety() {}

    // ---- 路径常量（按本机系统盘/系统目录动态解析，系统盘非 C: 时保护同样生效）----
    /** 系统盘符（取 SystemDrive 环境变量，非法/缺失时回退 C:），如 "C:"。 */
    private static final String SYSTEM_DRIVE = initSystemDrive();
    /** Windows 目录（取 SystemRoot/windir，缺失时回退 <系统盘>\Windows）。 */
    private static final String WINDOWS_DIR = initWindowsDir();

    private static final List<String> HARD_DENY_DIRS = List.of(
            lowerPath(SYSTEM_DRIVE + "\\boot"),
            lowerPath(WINDOWS_DIR + "\\System32"),
            lowerPath(WINDOWS_DIR + "\\SysWOW64"),
            lowerPath(WINDOWS_DIR + "\\WinSxS"),
            lowerPath(WINDOWS_DIR + "\\assembly"),
            lowerPath(WINDOWS_DIR + "\\CSC"),
            lowerPath(WINDOWS_DIR + "\\drivers"),
            lowerPath(SYSTEM_DRIVE + "\\$Recycle.Bin"));
    private static final List<String> PROTECTED_DIRS = List.of(
            lowerPath(WINDOWS_DIR),
            envPath("ProgramFiles", SYSTEM_DRIVE + "\\Program Files"),
            envPath("ProgramFiles(x86)", SYSTEM_DRIVE + "\\Program Files (x86)"),
            envPath("ProgramData", SYSTEM_DRIVE + "\\ProgramData"));

    /**
     * 解析系统盘符。
     * @return 形如 "C:" 的大写盘符；SystemDrive 缺失或非法时回退 "C:"
     */
    private static String initSystemDrive() {
        String d = System.getenv("SystemDrive");
        if (d != null && d.matches("(?i)^[A-Za-z]:$"))
            return d.substring(0, 1).toUpperCase(Locale.ROOT) + ":";
        return "C:";
    }

    /**
     * 解析 Windows 系统目录。
     * @return 规范化的系统目录绝对路径（如 C:\Windows）；环境变量缺失时回退系统盘默认路径
     */
    private static String initWindowsDir() {
        String root = System.getenv("SystemRoot");
        if (root == null || root.isBlank()) root = System.getenv("windir");
        if (root == null || root.isBlank()) root = SYSTEM_DRIVE + "\\Windows";
        return Paths.get(root).normalize().toString();
    }

    /**
     * 路径统一小写并把斜杠归一为反斜杠（与 isInside 的比较形态一致）。
     * @param p 原始路径
     * @return 小写、反斜杠分隔的路径
     */
    private static String lowerPath(String p) {
        return p.toLowerCase(Locale.ROOT).replace('/', '\\');
    }

    /**
     * 取环境变量指向的目录并归一化；缺失/空白时使用回退值。
     * @param env      环境变量名（如 ProgramFiles）
     * @param fallback 环境变量不可用时的默认路径
     * @return 小写、反斜杠分隔的目录路径
     */
    private static String envPath(String env, String fallback) {
        String v = System.getenv(env);
        return lowerPath((v == null || v.isBlank()) ? fallback : v);
    }
    private static final Set<String> HARD_DENY_FILES = new HashSet<>(List.of(
            "bootmgr", "BOOTNXT", "ntldr", "ntdetect.com", "boot.ini", "autoexec.bat", "config.sys",
            "pagefile.sys", "hiberfil.sys", "swapfile.sys", "config"));

    // 会被系统直接执行的文件扩展名（打开/运行均按危险处理）
    private static final Set<String> EXEC_EXT = new HashSet<>(List.of(
            ".exe", ".com", ".bat", ".cmd", ".ps1", ".msi", ".scr", ".jar", ".lnk", ".vbs", ".reg",
            ".hta", ".chm", ".jse", ".vbe", ".wsf", ".wsh", ".url", ".msh", ".inf", ".psc1", ".diagcab"));

    // 禁止执行的程序（审批无法通过）
    private static final Set<String> DENY_PROGRAMS = new HashSet<>(List.of(
            "format", "fdisk", "mbr2gpt", "diskpart", "debug", "edlin",
            "del", "erase", "rm", "rmdir", "rd", "deltree",
            "shutdown", "restart", "reboot",
            "fsutil", "cipher", "reg", "regedit", "psexec", "wmic", "mklink",
            "certutil", "bitsadmin", "ftp", "tftp", "curl", "wget", "nircmd",
            "mshta", "rundll32", "regsvr32", "wscript", "cscript", "schtasks", "at", "sc",
            "installutil", "msbuild", "csc", "vbc", "jsc", "bcdedit", "wsl", "bash", "sh"));

    private static final Set<String> PROXY_PROGRAMS = new HashSet<>(
            List.of("cmd", "cmd.exe", "powershell", "powershell.exe", "pwsh", "pwsh.exe"));

    // 允许直接执行的低风险白名单（不含代码解释器：防 cmd /c python -c 绕过）
    private static final Set<String> ALLOW_PROGRAMS = new HashSet<>(List.of(
            "notepad", "calc", "mspaint", "write", "snippingtool", "taskmgr", "control", "explorer", "winver",
            "ping", "ipconfig", "systeminfo", "tasklist", "hostname", "whoami", "ver", "vol", "where", "tree", "git"));

    private static final Set<String> CMD_INTERNAL = new HashSet<>(List.of(
            "dir", "type", "echo", "cd", "chdir", "cls", "ver", "vol", "set", "title", "prompt",
            "mkdir", "md", "copy", "xcopy", "robocopy", "move", "ren", "rename", "replace",
            "more", "find", "findstr", "sort", "attrib", "date", "time", "path", "pause", "rem", "exit"));

    // PowerShell 危险模式（删除/格式化/关机/动态执行/下载/持久化/Start-Process 等）
    private static final Pattern PS_DANGER_RE = Pattern.compile(String.join("|",
            "\\bremove-item\\b", "\\bremove-itemproperty\\b", "\\bri\\b", "\\bdel\\b", "\\berase\\b", "\\brd\\b", "\\brm\\b",
            "\\bclear-disk\\b", "\\bformat-volume\\b", "\\bformat\\b",
            "\\bstop-computer\\b", "\\brestart-computer\\b",
            "\\bset-executionpolicy\\b", "\\binvoke-expression\\b", "\\biex\\b",
            "\\binvoke-webrequest\\b", "\\biwr\\b", "\\binvoke-restmethod\\b", "\\birm\\b",
            "\\bstart-bitstransfer\\b", "\\bdownloadstring\\b", "\\bdownloadfile\\b", "\\buploadfile\\b", "\\bnet\\.webclient\\b",
            "\\bnew-scheduledtask\\b", "\\bregister-scheduledtask\\b", "\\bschtasks\\b",
            "\\bnew-service\\b", "\\bnet\\s+user\\b", "\\bnet\\s+localgroup\\b",
            "\\bstart-process\\b", "\\badd-type\\b.*\\bdllimport\\b",
            "\\bset-itemproperty\\b", "\\bnew-itemproperty\\b", "\\bregedit\\b", "\\breg\\.exe\\b",
            "-\\benc\\b", "-\\bencodedcommand\\b"), Pattern.CASE_INSENSITIVE);

    private static final Set<String> CMD_DANGER = new HashSet<>(List.of(
            "del", "erase", "rd", "rmdir", "deltree", "format", "diskpart", "shutdown", "restart", "reboot",
            "reg", "regedit", "regsvr32", "rundll32", "wmic", "cipher", "fsutil", "bcdedit", "cacls", "icacls",
            "takeown", "runas", "sc", "net", "schtasks", "at", "mshta", "certutil", "bitsadmin", "ftp", "tftp",
            "curl", "wget", "powershell", "powershell.exe", "pwsh", "wscript", "cscript", "wsl", "bash"));

    // control.exe 允许打开的系统自带 CPL 白名单（仅文件名精确匹配，禁止带路径；
    // .msc 管理单元不在内——其宿主是 mmc.exe，不在白名单，避免 control evil.cpl 加载任意 DLL）
    private static final Set<String> CONTROL_CPL = new HashSet<>(List.of(
            "inetcpl.cpl", "sysdm.cpl", "appwiz.cpl", "ncpa.cpl", "powercfg.cpl", "timedate.cpl",
            "intl.cpl", "desk.cpl", "mmsys.cpl", "firewall.cpl", "wscui.cpl", "main.cpl",
            "odbccp32.cpl", "sapi.cpl", "tabletpc.cpl", "telephon.cpl", "joy.cpl",
            "color.cpl", "collab.cpl"));

    // 资源管理器参数中的 URL 形态（任意 scheme:// 外联都拒绝：http/https/ftp/file...）
    private static final Pattern URL_SCHEME_RE = Pattern.compile("^[a-z][a-z0-9+.-]*://", Pattern.CASE_INSENSITIVE);
    // cmd 变量展开形态：%VAR%（常规展开）与 !VAR!（延迟展开），可借 %COMSPEC% 绕过首词匹配
    private static final Pattern VAR_EXPANSION_RE = Pattern.compile("%[^%\\s]{1,64}%|![^!\\s]{1,64}!", Pattern.CASE_INSENSITIVE);
    // 从命令段中按空白提取词（引号内容作为一个词）
    private static final Pattern WORD_RE = Pattern.compile("\"([^\"]*)\"|(\\S+)");

    private static final Set<String> SENSITIVE_DIR_PARTS = new HashSet<>(
            List.of(".ssh", ".aws", ".gnupg", ".kube", ".docker", ".azure"));
    private static final Pattern SENSITIVE_FILE_RE = Pattern.compile(
            "(^|[\\\\/])(\\.env(\\..+)?|\\.netrc|\\.npmrc|\\.pem|\\.key|id_rsa|id_dsa|id_ecdsa|id_ed25519|credentials|credentials\\.json|known_hosts)$",
            Pattern.CASE_INSENSITIVE);

    // ---- 路径规范化 ----
    /**
     * 规范化路径：循环剥离 \\?\ / \\.\ 前缀，处理 ~、引号、相对路径（相对用户目录）。
     * @return 规范化后的绝对路径；入参非法返回 null
     */
    public static String normalizePath(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        while (s.startsWith("\\\\?\\") || s.startsWith("\\\\.\\")) {
            s = s.substring(4);
        }
        String home = System.getProperty("user.home");
        if (s.equals("~")) s = home;
        else if (s.startsWith("~/") || s.startsWith("~\\")) s = Paths.get(home, s.substring(2)).toString();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) s = s.substring(1, s.length() - 1);
        try {
            Path p = Paths.get(s);
            if (!p.isAbsolute()) p = Paths.get(home, s);
            return p.normalize().toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析到真实路径（识别 junction/符号链接）；不存在时沿父目录递归 realpath。 */
    public static String realResolve(String p) {
        try {
            return Paths.get(p).toRealPath().toString();
        } catch (Exception e) {
            Path cur = Paths.get(p).toAbsolutePath().normalize();
            while (cur.getParent() != null) {
                cur = cur.getParent();
                try { return Paths.get(cur.toRealPath().toString(), Paths.get(p).getFileName().toString()).toString(); }
                catch (Exception ignored) {}
            }
            return p;
        }
    }

    private static String normLower(String p) {
        String n = normalizePath(p);
        return n == null ? null : n.toLowerCase(Locale.ROOT).replace('/', '\\');
    }

    /** target（规范化小写）是否位于 dirs 任一路径内。 */
    private static boolean isInside(String target, List<String> dirs) {
        if (target == null) return false;
        String t = target.endsWith("\\") ? target : target + "\\";
        for (String d : dirs) {
            String dl = d.toLowerCase(Locale.ROOT);
            if (t.equals(dl + "\\") || t.startsWith(dl + "\\")) return true;
        }
        return false;
    }

    private static boolean isHardDenyFile(String t) {
        return HARD_DENY_FILES.contains(new File(t).getName().toLowerCase(Locale.ROOT));
    }

    public static boolean isExecFile(String p) {
        if (p == null) return false;
        String n = p.toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        return i >= 0 && EXEC_EXT.contains(n.substring(i));
    }

    // ---- 写入/删除检查 ----
    public static CheckResult checkWrite(String targetRaw) {
        String t = normalizePath(targetRaw);
        if (t == null) return CheckResult.blocked("路径无效。");
        if (isHardDenyFile(t)) return CheckResult.blocked("该文件属于系统关键文件，禁止修改。");
        if (isInside(normLower(t), HARD_DENY_DIRS))
            return CheckResult.blocked("该目录属于系统保护目录，禁止写入。");
        String real = normLower(realResolve(t));
        if (isInside(real, HARD_DENY_DIRS))
            return CheckResult.blocked("该路径经由链接指向系统保护目录，禁止写入。");
        if (isInside(real, PROTECTED_DIRS))
            return CheckResult.of(false, "dangerous", "目标位于系统保护目录内（含链接指向），修改风险高，需确认。");
        if (isDocFile(t)) return CheckResult.of(false, "dangerous", "目标是文档文件（Word/Excel/PPT 等），覆盖或修改风险高，需确认。");
        return CheckResult.of(false, "confirm", "会修改或创建本地文件。");
    }

    public static CheckResult checkDelete(String targetRaw) {
        String t = normalizePath(targetRaw);
        if (t == null) return CheckResult.blocked("路径无效。");
        if (isHardDenyFile(t)) return CheckResult.blocked("该文件属于系统关键文件，禁止删除。");
        if (isInside(normLower(t), HARD_DENY_DIRS))
            return CheckResult.blocked("该目录属于系统保护目录，禁止删除。");
        String real = normLower(realResolve(t));
        if (isInside(real, HARD_DENY_DIRS))
            return CheckResult.blocked("该路径经由链接指向系统保护目录，禁止删除。");
        if (isInside(real, PROTECTED_DIRS))
            return CheckResult.of(false, "dangerous", "目标位于系统保护目录内（含链接指向），删除风险高。");
        if (isDocFile(t)) return CheckResult.of(false, "dangerous", "目标是文档文件，删除不可恢复，需确认。");
        return CheckResult.of(false, "dangerous", "删除操作不可恢复。");
    }

    private static boolean isDocFile(String t) {
        String n = t.toLowerCase(Locale.ROOT);
        return n.endsWith(".doc") || n.endsWith(".docx") || n.endsWith(".ppt") || n.endsWith(".pptx")
                || n.endsWith(".xls") || n.endsWith(".xlsx") || n.endsWith(".csv") || n.endsWith(".pdf")
                || n.endsWith(".rtf") || n.endsWith(".zip");
    }

    // ---- 命令检查 ----
    private static String[] programKey(String program) {
        String base = program.trim().replaceAll("^[\"']|[\"']$", "").trim();
        String name = base.toLowerCase(Locale.ROOT);
        int slash = Math.max(name.lastIndexOf('\\'), name.lastIndexOf('/'));
        if (slash >= 0) name = name.substring(slash + 1);
        name = name.replaceAll("\\.(exe|com|bat|cmd|ps1)$", "");
        return new String[]{base, name};
    }

    private static String commandLineText(String program, List<String> argv) {
        StringBuilder sb = new StringBuilder(program);
        for (String s : argv == null ? List.<String>of() : argv) {
            sb.append(' ').append(s.matches(".*\\s.*") ? "\"" + s + "\"" : s);
        }
        String full = sb.toString().trim();
        return full.length() > 300 ? full.substring(0, 300) + "…" : full;
    }

    /**
     * 扫描 cmd /c 后各段命令：先做反注入预处理，再校验首词与白名单程序参数。
     * 执行逻辑：按 &amp; / && / | / || 切段 -> 折叠 ^ 转义（c^m^d→cmd）->
     * 拒绝 %VAR%/!VAR! 展开 -> 拒绝未引号包裹的重定向符（>/</>>，防未校验写副作用）
     * -> 首词危险动词/非白名单即阻止 -> explorer/control 段额外做参数白名单校验。
     * @param tokens cmd /c 之后的参数（可能多段拼在一个字符串元素内）
     * @return 命中的危险词/操作描述；null 表示全部通过
     */
    private static String scanCmdTokens(List<String> tokens) {
        String joined = String.join(" ", tokens);
        String[] segs = joined.split("\\s*&&\\s*|\\s*&\\s*|\\s*\\|\\|?\\s*");
        for (String seg0 : segs) {
            String seg = foldCaret(seg0).trim();
            if (seg.isEmpty()) continue;
            // 环境变量展开可用于绕过首词匹配（%COMSPEC% /c ...），白名单只读命令无需变量
            var em = VAR_EXPANSION_RE.matcher(seg);
            if (em.find()) return "环境变量展开 " + em.group();
            // 重定向（> >> <，含 2>、2>&1）产生未校验的文件写入/UNC 强制认证，一律阻止
            if (hasRedirect(seg)) return "输出/输入重定向";
            List<String> words = splitWords(seg);
            if (words.isEmpty()) continue;
            String first = words.get(0);
            if (first.isEmpty()) continue;
            String key = first.toLowerCase(Locale.ROOT).replaceAll("\\.(exe|com)$", "");
            int slash = Math.max(key.lastIndexOf('\\'), key.lastIndexOf('/'));
            if (slash >= 0) key = key.substring(slash + 1);
            if (CMD_DANGER.contains(key)) return key;
            if (!CMD_INTERNAL.contains(key) && !ALLOW_PROGRAMS.contains(key)) return key;
            // cmd /c explorer ... / control ... 与直接调用走同一套参数审查
            if ("explorer".equals(key) || "control".equals(key)) {
                String bad = checkShellLauncherArgs(key, words.subList(1, words.size()));
                if (bad != null) return key + " " + bad;
            }
        }
        return null;
    }

    /**
     * 折叠 cmd 的 ^ 转义符（引号外）。cmd 中 ^ 会取消下一字符的特殊含义，
     * 因此 c^m^d /c 实际等价 cmd /c，可绕过首词匹配；引号内 ^ 为字面量，保留。
     * @param s 原始命令段
     * @return 折叠后的命令段
     */
    private static String foldCaret(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inQuote = !inQuote;
            if (!inQuote && c == '^' && i + 1 < s.length()) {
                // 被转义的字符原样输出；若它是引号，不计入引号配对（cmd 中 ^" 不开启引用段）
                out.append(s.charAt(++i));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * 判断命令段是否存在引号外的重定向符（&lt;、&gt;，覆盖 &gt;&gt;、2&gt;、2&gt;&amp;1）。
     * @param seg 已折叠 ^ 转义的命令段
     * @return 存在未加引号的重定向符返回 true
     */
    private static boolean hasRedirect(String seg) {
        boolean inQuote = false;
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c == '"') inQuote = !inQuote;
            else if (!inQuote && (c == '<' || c == '>')) return true;
        }
        return false;
    }

    /**
     * 按空白拆分命令词，双引号包裹的内容作为一个词并去掉引号。
     * @param seg 命令段
     * @return 有序词列表（无词时为空表）
     */
    private static List<String> splitWords(String seg) {
        List<String> out = new ArrayList<>();
        var m = WORD_RE.matcher(seg);
        while (m.find()) out.add(m.group(1) != null ? m.group(1) : m.group(2));
        return out;
    }

    /**
     * explorer/control 参数白名单审查（二者虽在允许名单，但可被参数借道执行/外联）。
     * explorer：拒绝 URL（任意 scheme://，防外联）、UNC 路径（\\server 防 NTLM 强制认证）、
     * 可执行扩展名目标（explorer 会经 ShellExecute 直接运行 exe/bat/lnk 等）；
     * control：无参允许，参数仅允许 {@link #CONTROL_CPL} 中的纯文件名（防加载任意 CPL/DLL）。
     * @param key  程序键名（explorer 或 control）
     * @param args 除程序名外的参数列表
     * @return 命中的非法参数说明；null 表示通过
     */
    private static String checkShellLauncherArgs(String key, List<String> args) {
        if ("control".equals(key)) {
            for (String a0 : args) {
                String a = a0.trim();
                if (a.isEmpty()) continue;
                if (!CONTROL_CPL.contains(a.toLowerCase(Locale.ROOT)))
                    return "参数「" + a + "」不在系统 CPL 白名单，禁止 control 加载";
            }
            return null;
        }
        // explorer：剥离开关注释（/e,/n,/root,/select,/separate 等，以逗号连接目标）
        for (String a0 : args) {
            String a = a0.trim();
            String low = a.toLowerCase(Locale.ROOT);
            if (low.startsWith("/")) {
                int comma = a.indexOf(',');
                a = comma >= 0 ? a.substring(comma + 1).trim() : "";
            }
            if (a.isEmpty()) continue;
            if (URL_SCHEME_RE.matcher(a).find())
                return "URL/外联参数「" + a + "」被禁止";
            if (a.startsWith("\\") || a.startsWith("//"))
                return "UNC/网络路径参数「" + a + "」被禁止";
            if (isExecFile(a))
                return "可执行文件参数「" + a + "」会被资源管理器直接运行，禁止";
        }
        return null;
    }

    /**
     * 命令执行安全检查。
     * @param program 程序路径或名
     * @param argv    参数数组（代理程序二级解析必需）
     */
    public static CheckResult checkCommand(String program, List<String> argv) {
        if (program == null) return CheckResult.blocked("程序路径无效。");
        String[] pk = programKey(program);
        String base = pk[0], name = pk[1];
        if (DENY_PROGRAMS.contains(name) || DENY_PROGRAMS.contains(
                new File(base).getName().toLowerCase(Locale.ROOT).replaceAll("\\.(exe|com|bat|cmd|ps1)$", ""))) {
            return CheckResult.blocked("程序 " + base + " 被列入禁止名单，不允许执行。");
        }
        // explorer/control 虽在允许名单，但参数可借道执行任意可执行文件/外联/加载任意 CPL，
        // 必须在放行前做参数白名单审查（与 cmd /c 子段扫描共用同一判定）
        if ("explorer".equals(name) || "control".equals(name)) {
            String badArg = checkShellLauncherArgs(name, argv == null ? List.of() : argv);
            if (badArg != null) return CheckResult.blocked(badArg + "。");
        }
        String baseName = new File(base).getName().toLowerCase(Locale.ROOT);
        if (PROXY_PROGRAMS.contains(baseName)) {
            List<String> args = argv == null ? List.of() : argv;
            boolean isCmd = baseName.matches("cmd(\\.exe)?");
            if (isCmd) {
                int idx = -1;
                for (int i = 0; i < args.size(); i++) {
                    if (args.get(i).trim().matches("(?i)^/(c|k)$")) { idx = i; break; }
                }
                List<String> rest = idx >= 0 ? args.subList(idx + 1, args.size()) : args;
                String hit = scanCmdTokens(rest);
                if (hit != null) return CheckResult.blocked("cmd 子命令「" + hit + "」属于危险或未授权操作，已硬性阻止。");
            } else {
                String joined = String.join(" ", args);
                String first = args.isEmpty() ? "" : args.get(0);
                if (first.matches("(?i)^/?(enc|encodedcommand|e)\\b.*"))
                    return CheckResult.blocked("PowerShell 编码命令（-EncodedCommand）被禁止，请改用明文命令。");
                var m = PS_DANGER_RE.matcher(joined);
                if (m.find())
                    return CheckResult.blocked("PowerShell 命令包含危险操作「" + m.group() + "」，已硬性阻止。");
            }
        }
        String line = commandLineText(base, argv);
        return CheckResult.of(false, "dangerous",
                ALLOW_PROGRAMS.contains(name)
                        ? "执行命令（" + line + "），请确认。"
                        : "执行命令会影响系统状态，请确认后再继续。完整命令：" + line);
    }

    /** 读取敏感目录/凭据文件升级 confirm；risk 为空串表示沿用工具自身 risk。 */
    public static CheckResult checkRead(String targetRaw) {
        String t = normalizePath(targetRaw);
        if (t == null) return CheckResult.blocked("路径无效。");
        String[] parts = t.toLowerCase(Locale.ROOT).split("[\\\\/]+");
        for (String seg : parts) if (SENSITIVE_DIR_PARTS.contains(seg))
            return CheckResult.of(false, "confirm", "目标位于敏感目录或疑似凭据/密钥文件，读取需确认。");
        if (SENSITIVE_FILE_RE.matcher(t).find())
            return CheckResult.of(false, "confirm", "目标疑似凭据/密钥文件，读取需确认。");
        return CheckResult.of(false, "", "");
    }

    /** 移动/复制的源按移出语义校验。 */
    public static CheckResult checkMoveSource(String sourceRaw) {
        String t = normalizePath(sourceRaw);
        if (t == null) return CheckResult.blocked("路径无效。");
        if (isHardDenyFile(t)) return CheckResult.blocked("该文件属于系统关键文件，禁止移动。");
        if (isInside(normLower(t), HARD_DENY_DIRS)) return CheckResult.blocked("该目录属于系统保护目录，禁止移出。");
        if (isInside(normLower(realResolve(t)), HARD_DENY_DIRS))
            return CheckResult.blocked("该路径经由链接指向系统保护目录，禁止移出。");
        if (isInside(normLower(realResolve(t)), PROTECTED_DIRS))
            return CheckResult.of(false, "dangerous", "源位于系统保护目录内（含链接指向），移动系统文件风险高。");
        return CheckResult.of(false, "confirm", "会移动/重命名本地文件。");
    }

    private static CheckResult merge(CheckResult a, CheckResult b) {
        if (a.blocked()) return a;
        if (b.blocked()) return b;
        int ra = rank(a.risk()), rb = rank(b.risk());
        CheckResult worse = ra >= rb ? a : b;
        CheckResult other = worse == a ? b : a;
        String reason = (a.reason() + (other.reason().isEmpty() ? "" : "；" + other.reason()));
        return CheckResult.of(false, worse.risk(), reason);
    }

    private static int rank(String r) {
        if ("dangerous".equals(r)) return 2;
        if ("confirm".equals(r)) return 1;
        return 0;
    }

    /**
     * 按工具名与参数做综合安全判定（移植 checkTool 分支）。
     * @param defRisk 工具定义的默认 risk（auto/confirm/dangerous）
     */
    public static CheckResult checkTool(String toolName, String defRisk, Map<String, Object> args) {
        if (args == null) args = Map.of();
        switch (toolName) {
            case "write_file", "create_directory" -> {
                return checkWrite(str(args.get("path")));
            }
            case "copy_file", "move_file" -> {
                return merge(checkMoveSource(str(args.get("source"))), checkWrite(str(args.get("target"))));
            }
            case "delete_file", "delete_directory" -> { return checkDelete(str(args.get("path"))); }
            case "run_command" -> { return checkCommand(str(args.get("program")), strList(args.get("args"))); }
            case "read_file", "analyze_image", "read_office" -> {
                CheckResult cr = checkRead(str(args.get("path")));
                return cr.risk().isEmpty() ? CheckResult.of(false, defRisk, "") : cr;
            }
            case "search_files" -> {
                String root = str(args.get("root"));
                if (root == null || root.isEmpty()) return CheckResult.of(false, defRisk, "");
                CheckResult cr = checkRead(root);
                return cr.risk().isEmpty() ? CheckResult.of(false, defRisk, "") : cr;
            }
            case "create_ppt", "create_docx", "create_xlsx", "take_screenshot" -> {
                if ("take_screenshot".equals(toolName)) {
                    String p = str(args.get("path"));
                    if (p == null || p.isEmpty()) return CheckResult.of(false, defRisk, "");
                }
                return checkWrite(str(args.get("path")));
            }
            case "organize_files" -> {
                CheckResult cw = checkWrite(str(args.get("destDir") != null ? args.get("destDir") : args.get("dir")));
                if (cw.blocked()) return cw;
                return CheckResult.of(false, "dangerous", "批量整理 / 移动多个文件，操作影响面大。");
            }
            case "export_conversation" -> {
                String p = str(args.get("path"));
                if (p == null || p.isEmpty()) return CheckResult.of(false, "confirm", "会写入一个对话导出文件。");
                return checkWrite(p);
            }
            case "open_file" -> {
                String p = normalizePath(str(args.get("path")));
                if (p != null && isExecFile(p)) return checkCommand(p, null);
                return CheckResult.of(false, "confirm", "会调用系统默认程序打开文件。");
            }
            case "open_app" -> {
                String p = normalizePath(str(args.get("path")));
                String nm = str(args.get("name") != null ? args.get("name") : args.get("app"));
                String target = p != null ? p : (nm != null && !nm.isEmpty() ? nm : null);
                if (target != null && isExecFile(target)) return checkCommand(target, null);
                return CheckResult.of(false, "confirm", "会打开一个本机应用。");
            }
            default -> { return CheckResult.of(false, defRisk != null ? defRisk : "confirm", ""); }
        }
    }

    // ---- 参数转换辅助 ----
    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static List<String> strList(Object o) {
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object x : l) out.add(String.valueOf(x));
            return out;
        }
        return List.of();
    }
}
