@echo off
REM ===========================================================================
REM build-native.cmd -- 用 MSVC 编译 JNI 原生 DLL（localagent_native.dll）
REM 依赖：Visual Studio 2022（vcvars64）+ JDK 头文件（这里用 JDK14 的 jni.h，
REM       JNI ABI 跨版本稳定，编译产物可被 JBR21 加载）
REM ===========================================================================
call "C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Auxiliary\Build\vcvars64.bat" >nul
cd /d "%~dp0"
cl /nologo /LD /O2 ^
  /I"C:\Program Files\Java\jdk-14.0.2\include" ^
  /I"C:\Program Files\Java\jdk-14.0.2\include\win32" ^
  native-c\localagent_native.c ^
  /Fe:out\classes\localagent_native.dll ^
  /Fo:out\classes\localagent_native.obj ^
  user32.lib shell32.lib ole32.lib
if errorlevel 1 exit /b 1
del /q out\classes\localagent_native.obj out\classes\localagent_native.exp out\classes\localagent_native.lib 2>nul
echo NATIVE_BUILD_OK
