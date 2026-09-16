using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;

namespace LocalAgent.Launcher
{
    /// <summary>
    /// 本机助手 Windows 启动器（C# / .NET Framework 4，由系统自带 csc.exe 编译，
    /// 零第三方依赖、零网络下载）。
    ///
    /// 设计思路：
    /// 1) 定位本 exe 所在分发目录，按“内置 runtime → JAVA_HOME → PATH”顺序查找 javaw.exe；
    /// 2) 以无控制台（javaw）、无黑窗方式拉起 com.localagent.Main，工作目录固定为分发根；
    /// 3) 启动后做 2 秒早失败探测：JVM 立即异常退出时弹出错误提示，避免“双击无反应”；
    ///    正常存活则启动器自身退出，JVM 作为独立 GUI 进程继续运行；
    /// 4) 任何致命异常均以 MessageBox 兜底提示，返回非零退出码，不静默吞错。
    ///
    /// 适用场景：双击“本机助手.exe”免安装启动（分发目录已内置 JBR 运行时）。
    /// 创建时间：2026-09-16。核心用途：替代同目录 .bat 启动器，消除黑窗并改善错误可见性。
    /// </summary>
    internal static class Launcher
    {
        /// <summary>应用主类全限定名（JVM 入口）。</summary>
        private const string MainClass = "com.localagent.Main";

        /// <summary>启动后对 JVM 早失败的探测时长（毫秒）：该时间内异常退出才判定为启动失败。</summary>
        private const int EarlyFailWaitMs = 2000;

        /// <summary>
        /// 启动器入口（单线程单元，兼容系统对话框）。
        /// 执行流程：解析分发目录 → 定位 javaw → 组装 JVM 参数并启动 →
        /// 2 秒早失败探测 → 返回退出码。
        /// </summary>
        /// <param name="args">透传给 Java 主类的命令行参数（允许为空数组；
        /// 当前主类不解析参数，保留以备将来扩展）。</param>
        /// <returns>进程退出码：0=已正常拉起 JVM；1=发生未预期异常；
        /// 2=找不到 Java 运行时；3=进程对象创建失败；其他=JVM 早失败退出码。</returns>
        [STAThread]
        private static int Main(string[] args)
        {
            try
            {
                // BaseDirectory 始终以反斜杠结尾，即分发根目录（exe 所在目录）
                string baseDir = AppDomain.CurrentDomain.BaseDirectory;

                string javaw = ResolveJavaw(baseDir);
                if (javaw == null)
                {
                    MessageBox.Show(
                        "未找到 Java 运行环境，无法启动本机助手。\n\n" +
                        "已依次尝试：\n" +
                        "1) 本程序同目录 runtime\\bin\\javaw.exe（内置运行时缺失或被杀毒软件删除）\n" +
                        "2) JAVA_HOME 环境变量\n" +
                        "3) PATH 中的 javaw.exe\n\n" +
                        "请重新解压完整分发包，或安装 64 位 JDK 17 及以上版本后重试。",
                        "本机助手 - 启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    return 2;
                }

                string arguments = BuildArguments(baseDir, args);
                ProcessStartInfo psi = new ProcessStartInfo();
                psi.FileName = javaw;
                psi.Arguments = arguments;
                psi.WorkingDirectory = baseDir;
                // 不经过 Shell、不新建控制台窗口：配合 javaw 实现全程无黑窗
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;

                Process proc;
                try
                {
                    proc = Process.Start(psi);
                }
                catch (Exception ex)
                {
                    MessageBox.Show(
                        "无法启动 Java 进程：\n" + javaw + "\n\n原因：" + ex.Message,
                        "本机助手 - 启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    return 3;
                }

                if (proc == null)
                {
                    MessageBox.Show("Java 进程未能创建（Process.Start 返回空）。",
                        "本机助手 - 启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    return 3;
                }

                // 早失败探测：class 缺失、运行时损坏等会让 javaw 在 2 秒内退出
                if (proc.WaitForExit(EarlyFailWaitMs))
                {
                    int code = SafeExitCode(proc);
                    if (code != 0)
                    {
                        MessageBox.Show(
                            "程序启动后立即退出（退出码 " + code + "）。\n\n" +
                            "可运行同目录“本机助手.bat”查看详细错误输出；\n" +
                            "若反复失败，请确认分发包完整且未被杀毒软件拦截。",
                            "本机助手 - 启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
                        return code == 0 ? 1 : code;
                    }
                }
                // JVM 正常存活：启动器退出，GUI 进程独立运行
                return 0;
            }
            catch (Exception ex)
            {
                MessageBox.Show("启动器发生未预期异常：\n" + ex.Message,
                    "本机助手 - 启动失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
        }

        /// <summary>
        /// 按固定优先级查找 javaw.exe。
        /// 执行逻辑：分发目录内置 runtime → JAVA_HOME 环境变量 → PATH 环境变量目录。
        /// </summary>
        /// <param name="baseDir">分发根目录（exe 所在目录，以反斜杠结尾）。</param>
        /// <returns>首个存在的 javaw.exe 绝对路径；全部不存在时返回 null。</returns>
        private static string ResolveJavaw(string baseDir)
        {
            // 1) 随包内置运行时（jlink 裁剪版或完整复制 JBR）
            string bundled = Path.Combine(baseDir, "runtime", "bin", "javaw.exe");
            if (File.Exists(bundled)) return bundled;

            // 2) JAVA_HOME
            string javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
            if (!string.IsNullOrEmpty(javaHome))
            {
                string fromHome = Path.Combine(javaHome.Trim(), "bin", "javaw.exe");
                if (File.Exists(fromHome)) return fromHome;
            }

            // 3) PATH 逐目录探测（仅取第一个命中，不依赖 where 命令，避免额外进程与黑窗）
            string pathEnv = Environment.GetEnvironmentVariable("PATH");
            if (!string.IsNullOrEmpty(pathEnv))
            {
                char sep = Path.PathSeparator;
                foreach (string dir in pathEnv.Split(sep))
                {
                    if (string.IsNullOrEmpty(dir)) continue;
                    try
                    {
                        string candidate = Path.Combine(dir.Trim(), "javaw.exe");
                        if (File.Exists(candidate)) return candidate;
                    }
                    catch (ArgumentException)
                    {
                        // PATH 中存在非法路径字符时跳过该段，继续探测其余目录
                    }
                }
            }
            return null;
        }

        /// <summary>
        /// 组装 JVM 启动参数：UTF-8、native DLL 搜索路径、classpath、主类及透传参数。
        /// </summary>
        /// <param name="baseDir">分发根目录（app 与 lib 目录的父目录）。</param>
        /// <param name="args">需透传给主类的命令行参数；允许为空数组。</param>
        /// <returns>可直接赋给 <see cref="ProcessStartInfo.Arguments"/> 的参数字符串；
        /// 所有含空格路径均已加双引号。</returns>
        private static string BuildArguments(string baseDir, string[] args)
        {
            string appDir = Path.Combine(baseDir, "app");
            string libWildcard = Path.Combine(baseDir, "lib", "*");
            string classPath = appDir + Path.PathSeparator + libWildcard;

            string arguments =
                "-Dfile.encoding=UTF-8 " +
                Quote("-Djava.library.path=" + appDir) + " " +
                "-cp " + Quote(classPath) + " " +
                MainClass;

            if (args != null)
            {
                foreach (string a in args)
                {
                    if (a == null) continue;
                    arguments += " " + Quote(a);
                }
            }
            return arguments;
        }

        /// <summary>
        /// 安全读取已退出进程的退出码。
        /// </summary>
        /// <param name="proc">已调用过 WaitForExit 的进程对象，不允许为 null。</param>
        /// <returns>进程退出码；读取被拒绝或进程尚未退出时返回 -1。</returns>
        private static int SafeExitCode(Process proc)
        {
            try
            {
                return proc.ExitCode;
            }
            catch (InvalidOperationException)
            {
                // 进程尚未退出或句柄不可用：按未知失败处理
                return -1;
            }
        }

        /// <summary>
        /// 为命令行参数加双引号并转义内部引号，保证含空格/特殊字符的路径安全。
        /// </summary>
        /// <param name="s">原始参数文本，允许包含空格与双引号；不允许为 null。</param>
        /// <returns>形如 "value" 的引号包裹字符串（内部 " 转义为 \"）。</returns>
        private static string Quote(string s)
        {
            return "\"" + s.Replace("\"", "\\\"") + "\"";
        }
    }
}
