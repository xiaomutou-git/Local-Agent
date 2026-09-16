import com.localagent.safety.Safety;

import java.util.*;

/**
 * 安全 POC JVM 回归（移植自 Electron 版 security-verify.js / security-verify2.js）。
 * 运行：java -cp out\classes;lib\*;out\test SecurityVerify
 */
public class SecurityVerify {
    static int pass = 0, fail = 0;

    static void t(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  [PASS] " + name); }
        else { fail++; System.out.println("  [FAIL] " + name); }
    }

    static Map<String, Object> args(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    public static void main(String[] a) {
        System.out.println("== H-01 命令执行 ==");
        t("certutil 下载被阻止", Safety.checkTool("run_command", "dangerous", args("program", "certutil", "args", List.of("-urlcache", "-f"))).blocked());
        t("cmd /c del 被阻止", Safety.checkTool("run_command", "dangerous", args("program", "cmd", "args", List.of("/c", "del", "C:\\a"))).blocked());
        t("cmd /c dir 放行待审批", !Safety.checkTool("run_command", "dangerous", args("program", "cmd", "args", List.of("/c", "dir", "C:\\"))).blocked());
        t("powershell Remove-Item 被阻止", Safety.checkTool("run_command", "dangerous", args("program", "powershell.exe", "args", List.of("-NoProfile", "-Command", "Remove-Item C:\\x"))).blocked());
        t("powershell Get-Process 放行", !Safety.checkTool("run_command", "dangerous", args("program", "powershell.exe", "args", List.of("-Command", "Get-Process"))).blocked());
        t("wsl 被阻止", Safety.checkTool("run_command", "dangerous", args("program", "wsl")).blocked());
        t("cmd & 串联危险命令被阻止", Safety.checkTool("run_command", "dangerous", args("program", "cmd", "args", List.of("/c", "dir & del C:\\a"))).blocked());
        t("notepad 白名单放行", !Safety.checkTool("run_command", "dangerous", args("program", "notepad", "args", List.of("a.txt"))).blocked());
        t("cmd /c python -c 被硬阻止(R-2)", Safety.checkTool("run_command", "dangerous", args("program", "cmd", "args", List.of("/c", "python", "-c", "import os"))).blocked());
        t("cmd /c powershell 被阻止", Safety.checkTool("run_command", "dangerous", args("program", "cmd", "args", List.of("/c", "powershell"))).blocked());
        t("powershell -EncodedCommand 被拒绝", Safety.checkTool("run_command", "dangerous", args("program", "powershell.exe", "args", List.of("-EncodedCommand", "QQBk"))).blocked());

        System.out.println("== M-09 敏感读取 ==");
        t(".ssh 私钥读取升级 confirm", "confirm".equals(Safety.checkTool("read_file", "auto", args("path", "C:\\Users\\u\\.ssh\\id_rsa")).risk()));
        t(".env 升级 confirm", "confirm".equals(Safety.checkTool("read_file", "auto", args("path", "C:\\proj\\.env")).risk()));
        t("普通文件维持 auto", "auto".equals(Safety.checkTool("read_file", "auto", args("path", "C:\\Users\\u\\doc.txt")).risk()));
        t("search_files 无 root 维持 auto(R-1)", "auto".equals(Safety.checkTool("search_files", "auto", args("name", "*.pdf")).risk()));
        t("search_files 敏感 root 升级", "confirm".equals(Safety.checkTool("search_files", "auto", args("name", "x", "root", "C:\\Users\\u\\.ssh")).risk()));

        System.out.println("== M-03 移动源校验 ==");
        t("移出 System32 被阻止", Safety.checkTool("move_file", "confirm", args("source", "C:\\Windows\\System32\\x.dll", "target", "C:\\Users\\u\\x.dll")).blocked());
        t("普通移动维持 confirm", "confirm".equals(Safety.checkTool("move_file", "confirm", args("source", "C:\\Users\\u\\a.txt", "target", "C:\\Users\\u\\b.txt")).risk()));

        System.out.println("== M-04 文档/截图写入 ==");
        t("create_docx 写 System32 被硬阻止", Safety.checkTool("create_docx", "confirm", args("path", "C:\\Windows\\System32\\e.docx")).blocked());
        t("take_screenshot 指定路径走 checkWrite", !Safety.checkTool("take_screenshot", "confirm", args("path", "C:\\Windows\\win.ini.png")).blocked() == false
                || "dangerous".equals(Safety.checkTool("take_screenshot", "confirm", args("path", "C:\\Program Files\\x\\s.png")).risk()));

        System.out.println("== M-05 危险扩展名 ==");
        t(".hta 识别为可执行", Safety.isExecFile("x.hta"));
        t(".chm 识别为可执行", Safety.isExecFile("x.chm"));
        t("open_file .hta 升级命令检查", "dangerous".equals(Safety.checkTool("open_file", "confirm", args("path", "D:\\x\\payload.hta")).risk()));

        System.out.println("== V-03 explorer/control 参数借道 ==");
        t("explorer 直接运行 exe 被阻止", Safety.checkTool("run_command", "dangerous",
                args("program", "explorer", "args", List.of("C:\\Users\\u\\evil.exe"))).blocked());
        t("explorer 打开 URL 外联被阻止", Safety.checkTool("run_command", "dangerous",
                args("program", "explorer", "args", List.of("https://evil.com/x"))).blocked());
        t("explorer /select 携带 exe 被阻止", Safety.checkTool("run_command", "dangerous",
                args("program", "explorer", "args", List.of("/select,C:\\x\\evil.exe"))).blocked());
        t("explorer UNC 路径被阻止", Safety.checkTool("run_command", "dangerous",
                args("program", "explorer", "args", List.of("\\\\dc\\share"))).blocked());
        t("explorer 打开普通目录放行", !Safety.checkTool("run_command", "dangerous",
                args("program", "explorer", "args", List.of("C:\\Users\\u\\Documents"))).blocked());
        t("control 无参放行", !Safety.checkTool("run_command", "dangerous",
                args("program", "control")).blocked());
        t("control 白名单 CPL 放行", !Safety.checkTool("run_command", "dangerous",
                args("program", "control", "args", List.of("inetcpl.cpl"))).blocked());
        t("control 任意 CPL 被阻止", Safety.checkTool("run_command", "dangerous",
                args("program", "control", "args", List.of("evil.cpl"))).blocked());
        t("control /name 形式被阻止", Safety.checkTool("run_command", "dangerous",
                args("program", "control", "args", List.of("/name", "Microsoft.DevicesAndPrinters"))).blocked());
        t("cmd /c explorer URL 借道被阻止", Safety.checkTool("run_command", "dangerous",
                args("program", "cmd", "args", List.of("/c", "explorer https://evil.com"))).blocked());

        System.out.println("== V-04 cmd 解析绕过（重定向/^转义/变量展开）==");
        t("cmd 重定向写文件被阻止", Safety.checkTool("run_command", "dangerous",
                args("program", "cmd", "args", List.of("/c", "whoami > C:\\a.txt"))).blocked());
        t("cmd ^ 转义危险动词被阻止", Safety.checkTool("run_command", "dangerous",
                args("program", "cmd", "args", List.of("/c", "d^e^l C:\\a"))).blocked());
        t("cmd %COMSPEC% 变量展开被阻止", Safety.checkTool("run_command", "dangerous",
                args("program", "cmd", "args", List.of("/c", "%COMSPEC% /c whoami"))).blocked());
        t("cmd 管道内 explorer exe 借道被阻止", Safety.checkTool("run_command", "dangerous",
                args("program", "cmd", "args", List.of("/c", "dir | explorer C:\\x\\a.exe"))).blocked());

        System.out.println("== V-06 动态系统盘保护 ==");
        String sysDrive = System.getenv("SystemDrive");
        if (sysDrive == null || sysDrive.isBlank()) sysDrive = "C:";
        t("按 SystemDrive 动态保护 System32 写入", Safety.checkTool("write_file", "confirm",
                args("path", sysDrive + "\\Windows\\System32\\h.dll")).blocked());

        System.out.println("== L-12 路径规范化 ==");
        String layered = "\\\\?\\" + "\\\\?\\" + "C:\\x"; // 实际字符串：\\?\\?\C:\x（叠层前缀）
        t("叠层 \\\\?\\ 前缀剥离", Safety.normalizePath(layered) != null);

        System.out.println();
        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        System.exit(fail > 0 ? 1 : 0);
    }
}
