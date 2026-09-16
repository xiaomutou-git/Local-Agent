package com.localagent.toolkit;

import java.util.Map;

/**
 * 工具执行结果。
 * @param ok      是否成功
 * @param message 成功时的文本输出（进入 LLM 上下文与审计）
 * @param error   失败时的错误信息（ok=false）
 * @param extra   附加结构化数据（如截图路径等），可为 null
 */
public record ToolResult(boolean ok, String message, String error, Map<String, Object> extra) {
    public static ToolResult ok(String message) { return new ToolResult(true, message, null, null); }
    public static ToolResult ok(String message, Map<String, Object> extra) { return new ToolResult(true, message, null, extra); }
    public static ToolResult error(String error) { return new ToolResult(false, null, error, null); }
}
