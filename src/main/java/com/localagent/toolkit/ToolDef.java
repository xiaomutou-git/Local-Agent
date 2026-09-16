package com.localagent.toolkit;

import java.util.Map;

/**
 * 工具定义元数据（提供给 LLM 的 function 描述 + 参数 JSON Schema + 安全分级）。
 *
 * 放置于无业务依赖的 toolkit 叶子包：office/scheduler/tts/mcp 等实现模块只依赖
 * 本契约与 {@link com.localagent.toolkit.ToolResult}，从而不反向依赖工具门面
 * com.localagent.tools.Tools，避免包级循环。
 *
 * @param name        工具名（与 Tools 分发分支一致）
 * @param description 面向模型的中文描述（含使用约束）
 * @param risk        auto=自动执行；confirm=需用户确认（可配置）；dangerous=必须确认
 * @param parameters  参数的 JSON Schema 片段（OpenAI/Ollama function-calling 协议），
 *                    无参工具为空对象 Schema，由 com.localagent.tools.ToolSchemas 统一提供
 */
public record ToolDef(String name, String description, String risk, Map<String, Object> parameters) {}
