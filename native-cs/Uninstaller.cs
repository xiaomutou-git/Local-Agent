using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Text;
using System.Windows.Forms;
using Microsoft.Win32;

namespace LocalAgent.Uninstaller
{
    /// <summary>
    /// 本机助手卸载程序（C# / .NET Framework 4，系统自带 csc.exe 编译，零第三方依赖）。
    ///
    /// 设计思路（绿色软件卸载，无 MSI/安装器注册表产品项）：
    /// 1) 卸载范围分两级，个人数据默认【保留】，必须显式勾选才删除，防止误删聊天记录：
    ///    - 必删：程序目录（本 exe 所在目录）、开机自启注册表值
    ///      HKCU\Software\Microsoft\Windows\CurrentVersion\Run\LocalAgent、
    ///      %TEMP%\local-agent 临时脚本目录；
    ///    - 勾选才删：%APPDATA%\本机助手（SQLite 会话库、配置、知识库索引、锁文件）；
    ///    - 永不自动删：图片\本机助手截图、用户配置的知识库原文目录（属用户内容）。
    /// 2) 卸载前检测运行中的应用（按可执行路径前缀匹配内置 runtime 的 javaw 与启动器），
    ///    经用户同意后终止；拒绝终止则中止卸载，避免文件占用导致删一半。
    /// 3) 程序目录不能由运行中的卸载程序自删：在 %TEMP% 生成随机名 PowerShell 脚本，
    ///    卸载器退出后轮询其 PID，再重试删除整个程序目录并清理脚本自身。
    ///
    /// 适用场景：用户双击分发目录中的“卸载.exe”完成交互式卸载。
    /// 创建时间：2026-09-16。核心用途：与“本机助手.exe”启动器配套，补齐卸载入口。
    /// </summary>
    internal static class Program
    {
        /// <summary>开机自启注册表 Run 键路径（HKCU，与 Java 侧 AutoStart 完全一致）。</summary>
        private const string RunKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Run";

        /// <summary>开机自启注册表值名（与 Java 侧 AutoStart.VALUE_NAME 一致）。</summary>
        private const string RunValueName = "LocalAgent";

        /// <summary>APPDATA 下应用数据目录名（与 Java 侧 Main.dataDir 一致）。</summary>
        private const string AppDataDirName = "本机助手";

        /// <summary>TEMP 下应用临时目录名（与 Java 侧 Tts 使用的 local-agent 一致）。</summary>
        private const string TempDirName = "local-agent";

        /// <summary>
        /// 卸载程序入口（单线程单元，使用系统文件对话框样式）。
        /// </summary>
        /// <param name="args">命令行参数（当前不解析，保留扩展位；传入任意参数不影响行为）。</param>
        /// <returns>进程退出码：0=卸载流程已发起；其余为未处理异常（默认对话框已提示）。</returns>
        [STAThread]
        private static int Main(string[] args)
        {
            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            try
            {
                Application.Run(new UninstallerForm());
                return 0;
            }
            catch (Exception ex)
            {
                MessageBox.Show("卸载程序发生未预期异常：\n" + ex.Message,
                    "本机助手 - 卸载失败", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
        }

        /// <summary>
        /// 卸载主窗口：说明卸载范围、个人数据勾选项，执行卸载并展示结果。
        /// 该类不可继承（固定窗口，无派生需求）。
        /// </summary>
        private sealed class UninstallerForm : Form
        {
            /// <summary>“同时删除个人数据”勾选框（默认不勾，删除需用户显式授权）。</summary>
            private readonly CheckBox dataCheckBox;

            /// <summary>卸载执行按钮（执行中禁用，防止重复点击）。</summary>
            private readonly Button uninstallButton;

            /// <summary>取消按钮（执行中禁用）。</summary>
            private readonly Button cancelButton;

            /// <summary>状态行（执行步骤与错误摘要）。</summary>
            private readonly Label statusLabel;

            /// <summary>
            /// 构造窗口并初始化控件布局（纯代码布局，无设计器依赖文件）。
            /// </summary>
            public UninstallerForm()
            {
                Text = "卸载本机助手";
                FormBorderStyle = FormBorderStyle.FixedDialog;
                MaximizeBox = false;
                MinimizeBox = false;
                StartPosition = FormStartPosition.CenterScreen;
                ClientSize = new Size(480, 300);
                Font = new Font("Microsoft YaHei UI", 9F);

                string installDir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
                string dataDir = Path.Combine(
                    Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), AppDataDirName);

                Label title = new Label();
                title.Text = "即将从本机卸载「本机助手」";
                title.Font = new Font(Font.FontFamily, 11F, FontStyle.Bold);
                title.Location = new Point(18, 16);
                title.AutoSize = true;

                Label scope = new Label();
                scope.Text =
                    "将删除：\n" +
                    "  • 程序目录：" + Compact(installDir, 52) + "\n" +
                    "  • 开机自启注册表项（HKCU\\...\\Run\\" + RunValueName + "）\n" +
                    "  • 临时文件（%TEMP%\\" + TempDirName + "）";
                scope.Location = new Point(18, 48);
                scope.Size = new Size(444, 70);

                dataCheckBox = new CheckBox();
                dataCheckBox.Text = "同时删除个人数据（聊天记录、配置、知识库索引）：\n"
                    + Compact(dataDir, 46) + "\n删除后不可恢复。";
                dataCheckBox.Location = new Point(18, 124);
                dataCheckBox.Size = new Size(444, 58);
                dataCheckBox.Checked = false;

                Label keep = new Label();
                keep.Text = "截图（图片\\本机助手截图）与知识库原文目录将保留，如需清理请手动删除。";
                keep.ForeColor = Color.DimGray;
                keep.Location = new Point(18, 188);
                keep.Size = new Size(444, 34);

                uninstallButton = new Button();
                uninstallButton.Text = "卸载";
                uninstallButton.Size = new Size(96, 30);
                uninstallButton.Location = new Point(270, 250);
                uninstallButton.Click += OnUninstallClick;

                cancelButton = new Button();
                cancelButton.Text = "取消";
                cancelButton.Size = new Size(96, 30);
                cancelButton.Location = new Point(372, 250);
                cancelButton.Click += delegate { Close(); };

                statusLabel = new Label();
                statusLabel.Location = new Point(18, 256);
                statusLabel.Size = new Size(240, 24);
                statusLabel.ForeColor = Color.DimGray;
                statusLabel.Text = "";

                Controls.Add(title);
                Controls.Add(scope);
                Controls.Add(dataCheckBox);
                Controls.Add(keep);
                Controls.Add(statusLabel);
                Controls.Add(uninstallButton);
                Controls.Add(cancelButton);
                AcceptButton = uninstallButton;
                CancelButton = cancelButton;
            }

            /// <summary>
            /// 卸载按钮事件：最终确认 -> 关进程 -> 删注册表/临时/数据 -> 发起程序目录自删除。
            /// </summary>
            /// <param name="sender">事件发送者（卸载按钮），本方法不使用。</param>
            /// <param name="e">事件参数，本方法不使用。</param>
            private void OnUninstallClick(object sender, EventArgs e)
            {
                string installDir = AppDomain.CurrentDomain.BaseDirectory.TrimEnd('\\');
                bool deleteData = dataCheckBox.Checked;

                // 最终确认：对话框文本明确区分“保留/删除”个人数据
                string confirmText = deleteData
                    ? "确认卸载并【删除全部个人数据】吗？\n\n聊天记录、配置与知识索引将被永久删除，无法恢复。"
                    : "确认卸载吗？\n\n个人数据（聊天记录、配置、知识索引）将保留在 APPDATA 中，"
                      + "以后重装可继续使用。";
                DialogResult yes = MessageBox.Show(confirmText, "确认卸载",
                    MessageBoxButtons.YesNo, MessageBoxIcon.Warning, MessageBoxDefaultButton.Button2);
                if (yes != DialogResult.Yes) return;

                uninstallButton.Enabled = false;
                cancelButton.Enabled = false;

                // 1) 终止运行中的应用（拒绝则中止卸载）
                statusLabel.Text = "正在检查运行中的程序…";
                Application.DoEvents();
                List<Process> running = FindAppProcesses(installDir);
                if (running.Count > 0)
                {
                    DialogResult kill = MessageBox.Show(
                        "检测到本机助手正在运行，需要先关闭它才能继续卸载。\n是否立即关闭？",
                        "程序运行中", MessageBoxButtons.YesNo, MessageBoxIcon.Question);
                    if (kill != DialogResult.Yes)
                    {
                        uninstallButton.Enabled = true;
                        cancelButton.Enabled = true;
                        statusLabel.Text = "已取消（程序仍在运行）。";
                        return;
                    }
                    if (!TerminateProcesses(running))
                    {
                        MessageBox.Show("无法关闭运行中的程序，请手动退出后再运行卸载程序。",
                            "本机助手 - 卸载中止", MessageBoxButtons.OK, MessageBoxIcon.Error);
                        uninstallButton.Enabled = true;
                        cancelButton.Enabled = true;
                        statusLabel.Text = "卸载中止：无法关闭运行中的进程。";
                        return;
                    }
                }

                List<string> warnings = new List<string>();

                // 2) 删除开机自启注册表值（HKCU，不需要管理员；失败不阻断文件卸载）
                statusLabel.Text = "正在移除开机自启项…";
                Application.DoEvents();
                string regError = RemoveAutoStartValue();
                if (regError != null) warnings.Add("自启注册表项移除失败：" + regError);

                // 3) 删除临时脚本目录
                TryDeleteDirectory(Path.Combine(Path.GetTempPath(), TempDirName));

                // 4) 按需删除个人数据目录
                if (deleteData)
                {
                    statusLabel.Text = "正在删除个人数据…";
                    Application.DoEvents();
                    string dataDir = Path.Combine(
                        Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), AppDataDirName);
                    if (!TryDeleteDirectory(dataDir))
                        warnings.Add("个人数据目录被占用或无权限，未能完全删除：" + dataDir);
                }

                // 5) 程序目录只能在卸载器退出后删除：生成随机名 PowerShell 清理脚本并拉起
                statusLabel.Text = "正在完成清理…";
                Application.DoEvents();
                try
                {
                    SpawnDirectoryCleanup(installDir);
                }
                catch (Exception ex)
                {
                    warnings.Add("自动清理脚本生成失败，需手动删除程序目录：" + installDir + "（" + ex.Message + "）");
                }

                string summary = "本机助手已卸载完成。"
                    + (deleteData ? "个人数据已删除。" : "个人数据已保留。")
                    + (warnings.Count == 0 ? "" : "\n\n以下项目需要你手动处理：\n- " + string.Join("\n- ", warnings));
                MessageBox.Show(summary, "本机助手 - 卸载完成",
                    MessageBoxButtons.OK, MessageBoxIcon.Information);

                // 清理脚本在本进程退出后删除程序目录；此处直接结束进程
                Environment.Exit(0);
            }

            /// <summary>
            /// 枚举所有可执行路径位于程序目录内的相关进程（内置 javaw 与启动器）。
            /// 执行逻辑：遍历系统进程 -> 仅对 javaw 与启动器名读取 MainModule 路径 ->
            /// 忽略大小写判断是否以程序目录为前缀。
            /// </summary>
            /// <param name="installDir">程序安装目录（卸载器所在目录，不带结尾反斜杠）。</param>
            /// <returns>需要终止的进程列表；无运行实例时为空表（不为 null）。</returns>
            private static List<Process> FindAppProcesses(string installDir)
            {
                List<Process> hit = new List<Process>();
                string prefix = installDir.ToLowerInvariant();
                foreach (Process p in Process.GetProcesses())
                {
                    Process keep = p;
                    try
                    {
                        string name = p.ProcessName;
                        bool nameMatch = name.Equals("javaw", StringComparison.OrdinalIgnoreCase)
                            || name.Equals("本机助手", StringComparison.OrdinalIgnoreCase)
                            || name.Equals("java", StringComparison.OrdinalIgnoreCase);
                        if (!nameMatch) { p.Dispose(); continue; }
                        string exe = p.MainModule.FileName;
                        if (exe != null && exe.ToLowerInvariant().StartsWith(prefix, StringComparison.Ordinal))
                            hit.Add(p);
                        else
                            p.Dispose();
                    }
                    catch
                    {
                        // 访问受保护进程/位数不匹配时 MainModule 抛异常：不属本应用，释放枚举对象
                        try { keep.Dispose(); } catch { }
                    }
                }
                return hit;
            }

            /// <summary>
            /// 终止给定进程及其子进程树。
            /// 执行逻辑：先 CloseMainWindow 请求 GUI 优雅退出 -> 等待 2.5 秒 ->
            /// 仍存活则 Kill 强制结束 -> 再等待 3 秒确认退出。
            /// </summary>
            /// <param name="procs">待终止进程列表（由 FindAppProcesses 取得），不允许为 null。</param>
            /// <returns>true=全部已退出；false=超过等待时间仍有进程存活。</returns>
            private static bool TerminateProcesses(List<Process> procs)
            {
                foreach (Process p in procs)
                {
                    try { if (!p.HasExited) p.CloseMainWindow(); } catch { }
                }
                System.Threading.Thread.Sleep(2500);
                foreach (Process p in procs)
                {
                    try
                    {
                        if (!p.HasExited) p.Kill();
                    }
                    catch { }
                }
                System.Threading.Thread.Sleep(800);
                foreach (Process p in procs)
                {
                    try { if (!p.HasExited) return false; }
                    catch { }
                }
                return true;
            }

            /// <summary>
            /// 删除 HKCU Run 键下的本应用自启值（直接用 .NET 注册表 API，不启动 reg.exe）。
            /// </summary>
            /// <returns>null=删除成功或该值本就不存在；否则为异常消息（调用方按警告展示）。</returns>
            private static string RemoveAutoStartValue()
            {
                try
                {
                    using (RegistryKey runKey = Registry.CurrentUser.OpenSubKey(RunKeyPath, true))
                    {
                        // false=值不存在时不抛异常；键整体不存在（极特殊精简系统）按成功处理
                        if (runKey != null) runKey.DeleteValue(RunValueName, false);
                    }
                    return null;
                }
                catch (Exception ex)
                {
                    return ex.Message;
                }
            }

            /// <summary>
            /// 递归删除目录（Windows 文件锁友好：最多 6 次、每次 400ms 退避重试）。
            /// </summary>
            /// <param name="path">目标目录；为 null 或不存在时直接返回 true。</param>
            /// <returns>true=目录最终不存在；false=重试耗尽仍有残留。</returns>
            private static bool TryDeleteDirectory(string path)
            {
                if (string.IsNullOrEmpty(path) || !Directory.Exists(path)) return true;
                for (int i = 0; i < 6; i++)
                {
                    try
                    {
                        Directory.Delete(path, true);
                        break;
                    }
                    catch
                    {
                        System.Threading.Thread.Sleep(400);
                    }
                }
                return !Directory.Exists(path);
            }

            /// <summary>
            /// 在 %TEMP% 生成随机名 PowerShell 清理脚本并隐藏拉起；脚本等待本进程退出后
            /// 重试删除程序目录，最后删除脚本自身。
            /// 执行逻辑：脚本含本 PID 与程序目录（单引号转义）-> UTF-8 BOM 落盘
            /// （PowerShell 5.1 无 BOM 会按 GBK 误读中文路径）-> 无窗口启动 powershell.exe。
            /// </summary>
            /// <param name="installDir">待整体删除的程序目录绝对路径，不允许为 null。</param>
            /// <exception cref="Exception">脚本写入或进程启动失败时向上抛出，由调用方提示手动删除。</exception>
            private static void SpawnDirectoryCleanup(string installDir)
            {
                int myPid = Process.GetCurrentProcess().Id;
                string scriptPath = Path.Combine(Path.GetTempPath(),
                    "localagent-uninstall-" + Guid.NewGuid().ToString("N") + ".ps1");
                // PowerShell 单引号字符串中唯一需要转义的是单引号本身（加倍）
                string dirLiteral = installDir.Replace("'", "''");

                string script =
                    "$ErrorActionPreference = 'SilentlyContinue'\r\n" +
                    "$targetPid = " + myPid + "\r\n" +
                    "$dir = '" + dirLiteral + "'\r\n" +
                    "$self = $MyInvocation.MyCommand.Path\r\n" +
                    "while (Get-Process -Id $targetPid -ErrorAction SilentlyContinue) {\r\n" +
                    "  Start-Sleep -Milliseconds 200\r\n" +
                    "}\r\n" +
                    "Start-Sleep -Milliseconds 600\r\n" +
                    "for ($i = 0; $i -lt 12; $i++) {\r\n" +
                    "  Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue\r\n" +
                    "  if (-not (Test-Path -LiteralPath $dir)) { break }\r\n" +
                    "  Start-Sleep -Milliseconds 500\r\n" +
                    "}\r\n" +
                    "Remove-Item -LiteralPath $self -Force -ErrorAction SilentlyContinue\r\n";

                File.WriteAllText(scriptPath, script, new UTF8Encoding(true));

                ProcessStartInfo psi = new ProcessStartInfo();
                psi.FileName = "powershell.exe";
                psi.Arguments = "-NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File \""
                    + scriptPath + "\"";
                psi.UseShellExecute = false;
                psi.CreateNoWindow = true;
                psi.WorkingDirectory = Path.GetTempPath();
                Process.Start(psi);
            }

            /// <summary>
            /// 路径超长时保留首尾、中间以省略号折叠，保证窗口文字不被撑破。
            /// </summary>
            /// <param name="s">原始路径文本；允许为 null（按空串处理）。</param>
            /// <param name="max">允许的最大字符数，允许取值 12..200；过小按 12 处理。</param>
            /// <returns>不超过 max 字符的显示文本；短路径原样返回。</returns>
            private static string Compact(string s, int max)
            {
                if (s == null) return "";
                if (max < 12) max = 12;
                if (s.Length <= max) return s;
                int head = max - 7;
                return s.Substring(0, head) + "…" + s.Substring(s.Length - 6);
            }
        }
    }
}
