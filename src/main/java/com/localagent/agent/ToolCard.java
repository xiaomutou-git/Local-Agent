package com.localagent.agent;

import java.util.Map;

/**
 * 工具执行卡片（供 UI 渲染与用户审批）。
 * 记录：id、工具名、参数、风险级、状态、结果/错误。
 */
public class ToolCard {
    public final String id;
    public final String tool;
    public final Map<String, Object> args;
    public String risk;
    public String status;   // pending / running / done / blocked / rejected / error
    public String result;
    public boolean waiting;
    public long ts;

    public ToolCard(String id, String tool, Map<String, Object> args) {
        this.id = id; this.tool = tool; this.args = args;
        this.risk = "unknown"; this.status = "pending"; this.ts = System.currentTimeMillis();
    }

    public ToolCard snapshot() {
        ToolCard c = new ToolCard(id, tool, args);
        c.risk = risk; c.status = status; c.result = result; c.waiting = waiting; c.ts = ts;
        return c;
    }
}
