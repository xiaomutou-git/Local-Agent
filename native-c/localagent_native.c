/*=============================================================================
 * localagent_native.c —— 本机助手 JNI 原生能力层（C，极小 DLL）
 *
 * 设计原则（对应架构决策：C 仅做 Java 无法完成的 Win32 能力）：
 *  - 无业务逻辑、无外部输入解析、无内存长度处理风险（使用固定缓冲 + 系统 API）；
 *  - 仅两个函数：前台窗口标题、清空回收站；
 *  - 编译：cl /LD localagent_native.c /I<jni.h> /I<jni_md.h>
 *          user32.lib shell32.lib ole32.lib /Fe:localagent_native.dll
 *  包 com.localagent.nativelib -> JNI 符号直接拼接（无下划线转义）
 *===========================================================================*/
#include <jni.h>
#include <windows.h>
#include <shellapi.h>

/* 取当前前台窗口标题，返回 MUTF-8 字符串给 Java 层 */
JNIEXPORT jstring JNICALL
Java_com_localagent_nativelib_NativeBridge_nativeForegroundTitle(JNIEnv *env, jclass cls) {
    (void)cls;
    HWND hwnd = GetForegroundWindow();
    if (hwnd == NULL) {
        return (*env)->NewStringUTF(env, "");
    }
    wchar_t wbuf[512];
    int n = GetWindowTextW(hwnd, wbuf, (int)(sizeof(wbuf) / sizeof(wchar_t)) - 1);
    if (n <= 0) {
        return (*env)->NewStringUTF(env, "");
    }
    /* 宽字符转 UTF-8，固定缓冲（窗口标题上限 511 个宽字符，UTF-8 最多约 1533 字节） */
    char buf[2048];
    int len = WideCharToMultiByte(CP_UTF8, 0, wbuf, n, buf, (int)sizeof(buf) - 1, NULL, NULL);
    if (len <= 0) {
        return (*env)->NewStringUTF(env, "");
    }
    buf[len] = '\0';
    return (*env)->NewStringUTF(env, buf);
}

/* 清空回收站（不弹确认/进度/声音）；回收站为空时部分系统返回 E_FAIL，也视为成功 */
JNIEXPORT jboolean JNICALL
Java_com_localagent_nativelib_NativeBridge_nativeEmptyRecycleBin(JNIEnv *env, jclass cls) {
    (void)env;
    (void)cls;
    HRESULT hr = SHEmptyRecycleBinW(NULL, NULL,
                                    SHERB_NOCONFIRMATION | SHERB_NOPROGRESSUI | SHERB_NOSOUND);
    if (hr == S_OK || hr == 0x80004005L /* E_FAIL：回收站已空 */) {
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

/* DllMain：最小实现，无需在加载/卸载时做任何事 */
BOOL WINAPI DllMain(HINSTANCE hinst, DWORD reason, LPVOID reserved) {
    (void)hinst;
    (void)reason;
    (void)reserved;
    return TRUE;
}
