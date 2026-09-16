<#
===============================================================================
.SYNOPSIS
  本机助手 JVM 版构建脚本（零 Maven/零网络，使用本机 JBR + 本地 lib jar）

.DESCRIPTION
  1) 用 Android Studio 自带 JBR 21 的 javac 编译 src\main\java -> out\classes
  2) 编译测试源码到 out\test（可选）并运行全部回归（安全/Office/数据层）
  3) 组装 dist\本机助手\（classes + lib + 启动器 + 可选 native DLL）

  Native DLL（localagent_native.dll）为可选项：需安装 Windows SDK 后执行
  build-native.ps1；无 DLL 时前台窗口标题/清空回收站两个功能自动降级，
  其余功能不受影响。

.PARAMETER SkipTests
  跳过回归测试，仅编译打包。
===============================================================================
#>
[CmdletBinding()]
param([switch]$SkipTests)

$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
# JDK 解析：本项目使用文本块/record 等 Java 21 特性，必须挑到主版本号 >= 17
# 的 JDK；依次探测 JAVA_HOME、Android Studio 内置 JBR、PATH 上的 java，
# 第一个版本达标的候选生效（避免旧 JAVA_HOME 如 JDK14 导致编译失败）（P0-7）
function Get-JavaMajor {
    param([string]$Home2)
    # 返回指定 JDK 主版本号；不存在或无法解析时返回 0
    $exe = Join-Path $Home2 'bin\java.exe'
    if (-not (Test-Path $exe)) { return 0 }
    # java -version 输在 stderr，全局 EAP=Stop 时会被包装成终止错误，
    # 这里显式切到 Continue 收集输出
    $prevEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $line = (& $exe -version 2>&1 | Select-Object -First 1 | Out-String)
        if ($line -match 'version "(\d+)(?:\.(\d+))?') {
            $first = [int]$Matches[1]
            # 旧式 1.8 报 8；新式直接返回主版本号
            if ($first -eq 1 -and $Matches[2]) { return [int]$Matches[2] }
            return $first
        }
    } catch {
        return 0
    } finally {
        $ErrorActionPreference = $prevEap
    }
    return 0
}
$candidates = @()
if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
$candidates += 'D:\Android Studio\jbr'
$pathJava = (Get-Command java.exe -ErrorAction SilentlyContinue | Select-Object -First 1).Source
if ($pathJava) { $candidates += (Resolve-Path (Join-Path (Split-Path -Parent $pathJava) '..')).Path }
$Jbr = $null
foreach ($c in $candidates) {
    if ($c -and (Test-Path (Join-Path $c 'bin\javac.exe')) -and (Get-JavaMajor $c) -ge 17) { $Jbr = $c; break }
}
if (-not $Jbr) { throw '未找到 JDK 17+（需要 javac，且支持 record/文本块）。请设置 JAVA_HOME 指向 JDK 17 及以上版本。' }
Write-Host "使用 JDK：$Jbr（版本 $(Get-JavaMajor $Jbr)）" -ForegroundColor DarkGray
$Java = "$Jbr\bin\javac.exe"
$JavaRun = "$Jbr\bin\java.exe"
$JLink = "$Jbr\bin\jlink.exe"

Write-Host '[1/5] 编译 Java 源码…' -ForegroundColor Cyan
$Classes = Join-Path $Root 'out\classes'
if (Test-Path $Classes) { Remove-Item $Classes -Recurse -Force }
New-Item -ItemType Directory -Force -Path $Classes | Out-Null
$srcs = Get-ChildItem -Recurse (Join-Path $Root 'src\main\java') -Filter *.java | Select-Object -ExpandProperty FullName
$nativeErr = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$jout = & $Java -Xlint:all -encoding UTF-8 -cp "$Root\lib\*" -d $Classes $srcs 2>&1
$jcode = $LASTEXITCODE
$ErrorActionPreference = $nativeErr
if ($jcode -ne 0) { $jout | Out-Host; throw 'Java 编译失败' }
Write-Host '  编译通过' -ForegroundColor Green

if (-not $SkipTests) {
  Write-Host '[2/5] 回归测试…' -ForegroundColor Cyan
  $Test = Join-Path $Root 'out\test'
  if (Test-Path $Test) { Remove-Item $Test -Recurse -Force }
  New-Item -ItemType Directory -Force -Path $Test | Out-Null
  $tsrcs = Get-ChildItem -Recurse (Join-Path $Root 'src\test\java') -Filter *.java | Select-Object -ExpandProperty FullName
  $nativeErr2 = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  $tout = & $Java -Xlint:all -encoding UTF-8 -cp "$Classes;$Root\lib\*" -d $Test $tsrcs 2>&1
  $tcode = $LASTEXITCODE
  $ErrorActionPreference = $nativeErr2
  if ($tcode -ne 0) { $tout | Out-Host; throw '测试代码编译失败' }
  foreach ($t in 'SecurityVerify','OfficeVerify','DbVerify','McpMergeVerify','McpStdioVerify','LocalToolchainVerify','ProcVerify','TimeToolVerify','SessionExportVerify','KnowledgeIndexVerify','SingleInstanceVerify','OllamaBootstrapVerify') {
    $rout = & $JavaRun '-Dfile.encoding=UTF-8' -cp "$Classes;$Test;$Root\lib\*" $t 2>&1
    $rcode = $LASTEXITCODE
    $rout | Select-Object -Last 2 | Out-Host
    if ($rcode -ne 0) { $rout | Out-Host; throw "$t 回归失败" }
  }
  Write-Host '  全部回归通过（安全 39 / Office 30 / 数据层 7 / MCP 合并 35 / MCP stdio 49 / 本地环境 25 / 进程管道 6 / 时间工具 10 / 会话导出 8 / 知识索引 9 / 单实例 3 / Ollama 引导 42，共 263）' -ForegroundColor Green
} else {
  Write-Host '[2/5] 跳过测试' -ForegroundColor Yellow
}

Write-Host '[3/5] 组装分发目录…' -ForegroundColor Cyan
$Dist = Join-Path $Root 'dist\本机助手'
if (Test-Path $Dist) { Remove-Item $Dist -Recurse -Force }
$AppDir = Join-Path $Dist 'app'
New-Item -ItemType Directory -Force -Path $AppDir | Out-Null
Copy-Item (Join-Path $Classes '*') $AppDir -Recurse -Force
New-Item -ItemType Directory -Force -Path (Join-Path $Dist 'lib') | Out-Null
Copy-Item (Join-Path $Root 'lib\*.jar') (Join-Path $Dist 'lib')
# 启动器：优先使用随包自带的 jlink 运行时（javaw 静默无黑窗），
# 不存在时回退 JAVA_HOME / PATH，不再硬编码开发机路径（P0-7）
$bat = @'
@echo off
chcp 65001 >nul
setlocal
set "JAVA_EXE="
if exist "%~dp0runtime\bin\javaw.exe" set "JAVA_EXE=%~dp0runtime\bin\javaw.exe"
if not defined JAVA_EXE if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\javaw.exe"
if not defined JAVA_EXE set "JAVA_EXE=javaw"
start "" "%JAVA_EXE%" -Dfile.encoding=UTF-8 "-Djava.library.path=%~dp0app" -cp "%~dp0app;%~dp0lib\*" com.localagent.Main
endlocal
'@
Set-Content -Path (Join-Path $Dist '本机助手.bat') -Value $bat -Encoding Default

# exe 启动器 + 卸载程序：用系统自带 .NET Framework 4 的 csc 编译 native-cs 下两个 C# 源
# （均为 winexe 子系统，全程无黑窗；零网络零第三方依赖）。
# - 本机助手.exe：定位内置 runtime 的 javaw 拉起主程序，含早失败提示；
# - 卸载.exe：交互式卸载，个人数据默认保留，程序目录经随机名 PS 脚本在退出后自删。
# csc 不存在时降级为仅保留 .bat，不中断构建。
$LauncherSrc = Join-Path $Root 'native-cs\Launcher.cs'
$UninstallerSrc = Join-Path $Root 'native-cs\Uninstaller.cs'
$CscCandidates = @(
  Join-Path $env:windir 'Microsoft.NET\Framework64\v4.0.30319\csc.exe'
  Join-Path $env:windir 'Microsoft.NET\Framework\v4.0.30319\csc.exe'
)
$Csc = $CscCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
if ($Csc -and (Test-Path $LauncherSrc)) {
    $prevEapCsc = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'

    # 启动器（仅依赖 WinForms）
    $LauncherExe = Join-Path $Dist '本机助手.exe'
    $cout1 = & $Csc /nologo /target:winexe /codepage:65001 /r:System.Windows.Forms.dll `
        "/out:$LauncherExe" "$LauncherSrc" 2>&1
    $ccode1 = $LASTEXITCODE
    if ($ccode1 -ne 0) { $ErrorActionPreference = $prevEapCsc; $cout1 | Out-Host; throw 'exe 启动器编译失败' }
    Write-Host '  exe 启动器就绪（本机助手.exe，无黑窗）' -ForegroundColor Green

    # 卸载程序（WinForms + Drawing + mscorlib 内注册表 API）
    if (Test-Path $UninstallerSrc) {
        $UninstallerExe = Join-Path $Dist '卸载.exe'
        $cout2 = & $Csc /nologo /target:winexe /codepage:65001 `
            /r:System.Windows.Forms.dll /r:System.Drawing.dll `
            "/out:$UninstallerExe" "$UninstallerSrc" 2>&1
        $ccode2 = $LASTEXITCODE
        if ($ccode2 -ne 0) { $ErrorActionPreference = $prevEapCsc; $cout2 | Out-Host; throw '卸载程序编译失败' }
        Write-Host '  卸载程序就绪（卸载.exe）' -ForegroundColor Green
    } else {
        Write-Host '  未找到 native-cs\Uninstaller.cs：跳过卸载程序' -ForegroundColor Yellow
    }
    $ErrorActionPreference = $prevEapCsc
} else {
    Write-Host '  未找到 .NET Framework csc.exe 或启动器源码：跳过 exe，仅保留 .bat 启动器' -ForegroundColor Yellow
}
Write-Host '  分发目录就绪' -ForegroundColor Green

Write-Host '[4/5] 生成内置运行时（免安装 JDK）…' -ForegroundColor Cyan
$Runtime = Join-Path $Dist 'runtime'
if (Test-Path $Runtime) { Remove-Item $Runtime -Recurse -Force }
$linkErr = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
if (Test-Path (Join-Path $Jbr 'jmods')) {
    # 完整 JDK（含 jmods）：用 jlink 裁剪出最小运行时
    # 模块清单由 jdeps -R 对 classes+lib 静态分析得出：
    # java.desktop=Swing 界面/托盘，java.net.http=Ollama 流式接口，java.sql=JDBC
    $JModules = 'java.base,java.desktop,java.net.http,java.sql'
    $lout = & $JLink --no-header-files --no-man-pages --compress=2 `
        --module-path (Join-Path $Jbr 'jmods') --add-modules $JModules --output $Runtime 2>&1
    $lcode = $LASTEXITCODE
    if ($lcode -ne 0) { $lout | Out-Host; throw 'jlink 运行时生成失败' }
    Write-Host '  内置运行时就绪（jlink 裁剪）' -ForegroundColor Green
} else {
    # 无 jmods 的运行时（典型：Android Studio 自带 JBR）：直接整体复制，
    # 跳过 legal 文档与调试符号；JBR 基于 GPLv2+CE，允许随应用再分发，
    # 体积大于裁剪版但同样实现「目标机器零 JDK 安装」（P0-7）
    Write-Host '  当前 JDK 无 jmods，改用完整运行时复制方案' -ForegroundColor Yellow
    New-Item -ItemType Directory -Force -Path $Runtime | Out-Null
    foreach ($item in 'bin','conf','lib','release') {
        $src = Join-Path $Jbr $item
        if (Test-Path $src) { Copy-Item $src (Join-Path $Runtime $item) -Recurse -Force }
    }
    Get-ChildItem $Runtime -Recurse -File -Include *.pdb,*.map | Remove-Item -Force -ErrorAction SilentlyContinue
    Write-Host '  内置运行时就绪（完整复制 JBR）' -ForegroundColor Green
}
$ErrorActionPreference = $linkErr
$rtSize = [math]::Round(((Get-ChildItem $Runtime -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 1)
Write-Host "  运行时体积：$rtSize MB" -ForegroundColor DarkGray
# 用内置运行时跑一遍数据层回归，验证运行时完整可用（sqlite JDBC/JNI 正常）；
# -SkipTests 且无历史编译产物时跳过本项
$DbVerifyClass = Join-Path $Root 'out\test\DbVerify.class'
if (Test-Path $DbVerifyClass) {
  $smoke = & "$Runtime\bin\java.exe" '-Dfile.encoding=UTF-8' -cp "$AppDir;$Dist\lib\*;$Root\out\test" DbVerify 2>&1
  if ($LASTEXITCODE -ne 0) { $smoke | Out-Host; throw '内置运行时冒烟验证（DbVerify）失败' }
  Write-Host '  内置运行时冒烟验证通过（DbVerify）' -ForegroundColor Green
} else {
  Write-Host '  跳过内置运行时冒烟验证（无测试产物，使用 -SkipTests 构建）' -ForegroundColor Yellow
}

Write-Host '[5/5] Native DLL 检查…' -ForegroundColor Cyan
$dll = Join-Path $Classes 'localagent_native.dll'
if (Test-Path $dll) {
  Copy-Item $dll (Join-Path $AppDir 'localagent_native.dll')
  Write-Host '  已包含 localagent_native.dll（前台窗口/回收站原生能力启用）' -ForegroundColor Green
} else {
  Write-Host '  未找到 native DLL：前台窗口/回收站功能降级（其余正常）。装 Windows SDK 后执行 build-native.ps1 补构建' -ForegroundColor Yellow
}

Write-Host ''
Write-Host "构建完成：$Dist" -ForegroundColor Green
if (Test-Path (Join-Path $Dist '本机助手.exe')) {
  Write-Host '双击「本机助手.exe」即可启动（无黑窗；.bat 为带控制台输出的备用启动器，需本机已运行 Ollama）。'
  if (Test-Path (Join-Path $Dist '卸载.exe')) { Write-Host '卸载请双击同目录「卸载.exe」（个人数据默认保留，卸载时可勾选删除）。' }
} else {
  Write-Host '双击「本机助手.bat」即可启动（需本机已运行 Ollama）。'
}
