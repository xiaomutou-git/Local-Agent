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
$Jbr = 'D:\Android Studio\jbr'
if (-not (Test-Path "$Jbr\bin\javac.exe")) { throw "未找到 JBR：$Jbr（请修改脚本中的 Jbr 路径，或设置 JAVA_HOME）" }
$Java = "$Jbr\bin\javac.exe"
$JavaRun = "$Jbr\bin\java.exe"

Write-Host '[1/4] 编译 Java 源码…' -ForegroundColor Cyan
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
  Write-Host '[2/4] 回归测试…' -ForegroundColor Cyan
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
  foreach ($t in 'SecurityVerify','OfficeVerify','DbVerify','McpMergeVerify','McpStdioVerify','LocalToolchainVerify') {
    $rout = & $JavaRun '-Dfile.encoding=UTF-8' -cp "$Classes;$Test;$Root\lib\*" $t 2>&1
    $rcode = $LASTEXITCODE
    $rout | Select-Object -Last 2 | Out-Host
    if ($rcode -ne 0) { $rout | Out-Host; throw "$t 回归失败" }
  }
  Write-Host '  全部回归通过（安全 24 / Office 7 / 数据层 7 / MCP 合并 35 / MCP stdio 49 / 本地环境 25，共 147）' -ForegroundColor Green
} else {
  Write-Host '[2/4] 跳过测试' -ForegroundColor Yellow
}

Write-Host '[3/4] 组装分发目录…' -ForegroundColor Cyan
$Dist = Join-Path $Root 'dist\本机助手'
if (Test-Path $Dist) { Remove-Item $Dist -Recurse -Force }
$AppDir = Join-Path $Dist 'app'
New-Item -ItemType Directory -Force -Path $AppDir | Out-Null
Copy-Item (Join-Path $Classes '*') $AppDir -Recurse -Force
New-Item -ItemType Directory -Force -Path (Join-Path $Dist 'lib') | Out-Null
Copy-Item (Join-Path $Root 'lib\*.jar') (Join-Path $Dist 'lib')
# 启动器
$bat = @'
@echo off
chcp 65001 >nul
setlocal
set "APP=%~dp0"
set "JAVA_EXE="
if exist "D:\Android Studio\jbr\bin\java.exe" set "JAVA_EXE=D:\Android Studio\jbr\bin\java.exe"
if not defined JAVA_EXE if defined JAVA_HOME set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
if not defined JAVA_EXE set "JAVA_EXE=java"
start "" "%JAVA_EXE%" -Dfile.encoding=UTF-8 "-Djava.library.path=%~dp0app" -cp "%~dp0app;%~dp0lib\*" com.localagent.Main
endlocal
'@
Set-Content -Path (Join-Path $Dist '本机助手.bat') -Value $bat -Encoding Default
Write-Host '  分发目录就绪' -ForegroundColor Green

Write-Host '[4/4] Native DLL 检查…' -ForegroundColor Cyan
$dll = Join-Path $Classes 'localagent_native.dll'
if (Test-Path $dll) {
  Copy-Item $dll (Join-Path $AppDir 'localagent_native.dll')
  Write-Host '  已包含 localagent_native.dll（前台窗口/回收站原生能力启用）' -ForegroundColor Green
} else {
  Write-Host '  未找到 native DLL：前台窗口/回收站功能降级（其余正常）。装 Windows SDK 后执行 build-native.ps1 补构建' -ForegroundColor Yellow
}

Write-Host ''
Write-Host "构建完成：$Dist" -ForegroundColor Green
Write-Host '双击「本机助手.bat」即可启动（需本机已运行 Ollama）。'
