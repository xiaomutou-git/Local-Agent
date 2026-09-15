package com.localagent.tools;

import java.util.Map;

/**
 * 工具定义元数据（提供给 LLM 的 function 描述 + 参数 JSON Schema + 安全分级）。
 *
 * @param name        工具名（与 Tools 分发分支一致）
 * @param description 面向模型的中文描述（含使用约束）
 * @param risk        auto=自动执行；confirm=需用户确认（可配置）；dangerous=必须确认
 * @param parameters  参数的 JSON Schema 片段（OpenAI/Ollama function-calling 协议），
 *                    无参工具为空对象 Schema，由 {@link ToolSchemas#of(String)} 统一提供
 */
public record ToolDef(String name, String description, String risk, Map<String, Object> parameters) {}
