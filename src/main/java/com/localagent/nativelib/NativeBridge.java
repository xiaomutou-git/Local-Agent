package com.localagent.nativelib;

/**
 * JNI 原生能力桥（对应架构中的「C 极小 DLL」层）。
 *
 * 仅承载 Java 标准库无法完成的 Win32 能力，保持 C 面最小、无外部输入解析：
 * - 前台窗口标题（GetForegroundWindow + GetWindowText）
 * - 清空回收站（SHEmptyRecycleBin）
 *
 * 加载失败（DLL 未编译/非 Windows）时降级为空实现，不阻断主流程。
 */
public final class NativeBridge {
    private static final boolean AVAILABLE;
    static {
        boolean ok = false;
        try {
            System.loadLibrary("localagent_native");
            ok = true;
        } catch (UnsatisfiedLinkError e) {
            ok = false; // 未构建 native 库时静默降级
        }
        AVAILABLE = ok;
    }

    private NativeBridge() {}

    public static boolean available() { return AVAILABLE; }

    /** 当前前台窗口标题；native 不可用时返回空串。 */
    public static String foregroundWindowTitle() {
        try { return AVAILABLE ? nativeForegroundTitle() : ""; } catch (Throwable t) { return ""; }
    }

    /** 清空回收站；成功或 native 不可用时均不抛异常（返回是否真正执行）。 */
    public static boolean emptyRecycleBin() {
        try { return AVAILABLE && nativeEmptyRecycleBin(); } catch (Throwable t) { return false; }
    }

    private static native String nativeForegroundTitle();
    private static native boolean nativeEmptyRecycleBin();
}
