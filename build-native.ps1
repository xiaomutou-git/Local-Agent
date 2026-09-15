<#
===============================================================================
.SYNOPSIS
  编译 JNI 原生 DLL（localagent_native.dll）—— 可选组件

.DESCRIPTION
  通过 VS 2022 Developer PowerShell 环境调用 MSVC cl 编译 native-c\localagent_native.c。
  前置：Visual Studio 2022 已安装「使用 C++ 的桌面开发」工作负载（含 Windows SDK，
        提供 UCRT 的 stdio.h / shell32.lib 等）。
  编译产物输出到 out\classes，执行 build.ps1 打包时会自动带入分发目录。

  无 Windows SDK 时本脚本会报 C1083（找不到 stdio.h）——请在 Visual Studio
  Installer 中补装 Windows 10/11 SDK 后重试。
===============================================================================
#>
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$VsDev = 'C:\Program Files\Microsoft Visual Studio\2022\Community\Common7\Tools\Launch-VsDevShell.ps1'
$JniInclude = 'C:\Program Files\Java\jdk-14.0.2\include'
if (-not (Test-Path $VsDev)) { throw "未找到 VS DevShell：$VsDev" }

& $VsDev -Arch amd64 -HostArch amd64 -SkipAutomaticLocation | Out-Null
if (-not $env:INCLUDE -or $env:INCLUDE -notmatch 'Windows Kits') {
  throw 'MSVC 环境缺少 Windows SDK 路径（INCLUDE 中无 Windows Kits）。请在 VS Installer 中安装「Windows 10/11 SDK」后重试。'
}

New-Item -ItemType Directory -Force -Path (Join-Path $Root 'out\classes') | Out-Null
Push-Location $Root
try {
  & cl /nologo /LD /O2 /utf-8 `
    "/I$JniInclude" "/I$JniInclude\win32" `
    native-c\localagent_native.c `
    '/Fe:out\classes\localagent_native.dll' `
    '/Fo:out\classes\localagent_native.obj' `
    user32.lib shell32.lib ole32.lib
  if ($LASTEXITCODE -ne 0) { throw 'cl 编译失败' }
  Remove-Item out\classes\localagent_native.obj, out\classes\localagent_native.exp, out\classes\localagent_native.lib -ErrorAction SilentlyContinue
  Write-Host 'NATIVE_BUILD_OK -> out\classes\localagent_native.dll' -ForegroundColor Green
} finally {
  Pop-Location
}
