package com.localagent.system;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * 开机自启管理（P0-4 提醒闭环配套）。
 *
 * 实现方式：Windows 下写当前用户注册表
 * HKCU\Software\Microsoft\Windows\CurrentVersion\Run 的 LocalAgent 值，
 * 登录后由系统以当前用户身份静默拉起 javaw（无控制台黑窗），不需要管理员权限，
 * 也不在启动目录落 .lnk 文件。注册表读写通过 reg.exe（参数不经 shell 解析，
 * 路径空格用 ProcessBuilder 原样传参，杜绝命令注入）。
 *
 * 启动命令按当前运行位置自动还原：
 * - 正式分发：&lt;dist&gt;/app 类目录 + 同级 lib/* + jlink 自带 runtime 的 javaw；
 * - 开发态：out/classes + 项目 lib/* + 当前 JAVA_HOME 的 javaw。
 *
 * 创建时间：2026-09，核心用途：设置面板「开机自动启动」开关的实际落盘执行。
 */
public final class AutoStart {
    /** 注册表 Run 键下本应用使用的值名（固定，便于重复设置时覆盖而非叠加）。 */
    private static final String VALUE_NAME = "LocalAgent";
    private static final String RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";

    private AutoStart() {}

    /**
     * 当前平台是否支持开机自启（仅 Windows）。
     * @return true=可调用 setEnabled/isEnabled；false=调用方应禁用相关开关
     */
    public static boolean isSupported() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /**
     * 查询注册表中是否已存在本应用的自启项。
     * @return true=已开启；false=未开启或查询失败（按未开启处理，不影响使用）
     */
    public static boolean isEnabled() {
        if (!isSupported()) return false;
        try {
            Process p = new ProcessBuilder(
                    "reg", "query", RUN_KEY, "/v", VALUE_NAME).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(5, TimeUnit.SECONDS);
            return out.contains(VALUE_NAME);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 开启或关闭开机自启。
     * @param enabled true=写入/覆盖自启命令；false=删除自启值
     * @throws Exception reg.exe 执行失败或非 0 退出时抛出，消息可直接展示给用户
     */
    public static void setEnabled(boolean enabled) throws Exception {
        if (!isSupported()) {
            if (enabled) throw new IllegalStateException("当前系统不支持开机自启（仅支持 Windows）。");
            return;
        }
        if (!enabled) {
            runReg("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f");
            return;
        }
        runReg("reg", "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ",
                "/d", buildLaunchCommand(), "/f");
    }

    /**
     * 执行 reg.exe 并校验退出码（删除不存在的值 reg 返回 1，视为关闭成功）。
     * @param cmd 完整命令参数（reg 子命令 + 参数）
     * @throws Exception 进程启动失败、超时或退出码非 0 时抛出
     */
    private static void runReg(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes());
        boolean finished = p.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("注册表操作超时。");
        }
        // delete 目标不存在时 reg 返回 1 且输出 "Unable to find..."，语义上等同已关闭
        boolean missingTarget = cmd[1].equals("delete")
                && (out.contains("Unable to find") || out.contains("找不到"));
        if (p.exitValue() != 0 && !missingTarget) {
            throw new IllegalStateException("注册表操作失败（退出码 " + p.exitValue() + "）：" + out.trim());
        }
    }

    /**
     * 按当前代码与 JRE 位置还原可直接放入注册表 Run 值的静默启动命令。
     * 执行逻辑：定位本类所在类目录 -> 推断 lib 目录（分发态为同级 lib，
     * 开发态为上两级项目 lib）-> 取 java.home 下的 javaw.exe（jlink 包内
     * 即自带运行时）-> 组装带引号的 -cp 命令。
     * @return 可写进 REG_SZ 的完整命令行（含必要双引号）
     * @throws Exception 类位置无法定位或 javaw 不存在时抛出
     */
    static String buildLaunchCommand() throws Exception {
        Path classesDir = Paths.get(AutoStart.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path libDir = classesDir.resolveSibling("lib");
        if (!Files.isDirectory(libDir)) {
            // 开发布局：out/classes 的上两级是项目根，lib 在项目根下
            Path devLib = classesDir.resolve("../../lib").normalize();
            if (Files.isDirectory(devLib)) libDir = devLib;
        }
        Path javaw = Paths.get(System.getProperty("java.home"), "bin", "javaw.exe");
        if (!Files.isRegularFile(javaw)) {
            throw new IllegalStateException("未找到 javaw.exe：" + javaw);
        }
        String cp = quote(classesDir.toString()) + File.pathSeparator + quote(libDir.resolve("*").toString());
        return quote(javaw.toString()) + " -Dfile.encoding=UTF-8 "
                + "\"-Djava.library.path=" + classesDir + "\""
                + " -cp " + cp + " com.localagent.Main";
    }

    /**
     * 为注册表命令片段包裹双引号（REG_SZ 由 reg.exe 原样存储，不做 shell 转义）。
     * @param s 路径或 classpath 片段
     * @return 两端带双引号的字符串
     */
    private static String quote(String s) {
        return "\"" + s + "\"";
    }
}
