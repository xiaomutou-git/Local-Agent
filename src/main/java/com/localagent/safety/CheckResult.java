package com.localagent.safety;

/**
 * 安全判定结果（对应 JS 版 {blocked, risk, reason}）。
 *
 * @param blocked true=硬性阻止（审批也无法通过）；false=按 risk 走审批或自动执行
 * @param risk    blocked / dangerous（必须确认）/ confirm（可配置确认）/ auto（自动）/ unknown
 * @param reason  面向用户展示的中文说明（命令类含完整命令行）
 */
public record CheckResult(boolean blocked, String risk, String reason) {
    public static CheckResult blocked(String reason) { return new CheckResult(true, "blocked", reason); }
    public static CheckResult of(boolean blocked, String risk, String reason) {
        return new CheckResult(blocked, risk, reason);
    }
}
