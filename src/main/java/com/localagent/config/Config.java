package com.localagent.config;

import com.localagent.db.Db;
import com.localagent.util.Json;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 配置管理（移植自 Electron 版 config.js）。
 *
 * 安全设计（对应报告 M-11）：
 * - 仅允许写入 DEFAULTS 中预定义的 key，未知 key（含 __proto__/constructor
 *   之类污染尝试）一律丢弃，防止 IPC 通道 mass-assignment 污染
 *   （例如被攻破的界面写入 requireConfirm=false 关闭审批）。
 * - baseUrl 变更由 OllamaClient.assertLoopback 校验，此处只存取。
 */
public final class Config {
    /** 配置默认值；同时作为合法 key 白名单来源。 */
    public static final ObjectNode DEFAULTS = Json.mapper().createObjectNode();
    static {
        DEFAULTS.put("baseUrl", "http://127.0.0.1:11434");
        DEFAULTS.put("model", "");
        DEFAULTS.put("memoryEnabled", false);
        DEFAULTS.put("knowledgeEnabled", false);
        // MCP 外部工具总开关：默认关闭。开启后 Agent 才会合并并下发 mcp__* 外部工具，
        // 外部工具统一按 confirm 分级，执行前必须经用户确认（配置白名单外的 key 会被丢弃）
        DEFAULTS.put("mcpEnabled", false);
        // MCP 本地服务配置（JSON 字符串）：仅允许 stdio 形态——
        // {"短名":{"command":"可执行文件","args":["..."],"env":{"K":"V"},"enabled":true}}。
        // 出现 type=http(sse/streamable-http) 或 url 字段的条目会被 McpManager 拒绝，
        // 从设计上锁死"不能联网"：Agent 与 MCP 之间只走本机子进程管道
        DEFAULTS.put("mcpServers", "{}");
        DEFAULTS.put("knowledgeDir", "D:/知识库");
        DEFAULTS.put("memoryDir", "");
        DEFAULTS.put("requireConfirm", true);
        DEFAULTS.put("thinking", false);
        DEFAULTS.put("ttsEnabled", false);
        DEFAULTS.put("planMode", false);
        DEFAULTS.put("ctxEnabled", false);
        DEFAULTS.put("ctxDir", "");
        DEFAULTS.put("ctxMaxTokens", 6000);
        DEFAULTS.put("ctxKeepRounds", 8);
        DEFAULTS.put("numCtx", 8192);
        // 模型保活时长（Ollama keep_alive，单位毫秒）。0=用完立即卸载，下轮需冷启动
        // 重新载入（8B 模型数秒）；默认 30 分钟，连续对话不再重复加载，显著降低首字延迟
        DEFAULTS.put("keepAlive", 1800000);
        DEFAULTS.put("cmdTimeout", 120000);
        DEFAULTS.put("maxSteps", 20);
        DEFAULTS.put("readLimit", 32000);
        // 配置结构版本：用于对历史用户做一次性默认值升级（升级后尊重用户后续手动修改）
        DEFAULTS.put("configVersion", 2);
        DEFAULTS.putNull("darkMode");
    }

    private static final Set<String> ALLOWED_KEYS = new HashSet<>();
    static {
        var it = DEFAULTS.fieldNames();
        while (it.hasNext()) ALLOWED_KEYS.add(it.next());
    }

    private static ObjectNode cfg;

    private Config() {}

    /** 从 SQLite 加载配置并与默认值合并（非法存储值回退默认）。 */
    public static synchronized void init() {
        ObjectMapper m = Json.mapper();
        cfg = DEFAULTS.deepCopy();
        for (Map.Entry<String, String> e : Db.loadSettings().entrySet()) {
            if (!ALLOWED_KEYS.contains(e.getKey())) continue;
            try {
                com.fasterxml.jackson.databind.JsonNode v = m.readTree(e.getValue());
                if (v != null) cfg.set(e.getKey(), v);
            } catch (Exception ignored) { /* 损坏值忽略 */ }
        }
    }

    /** 返回配置快照（深拷贝，避免外部直接改内部状态）。 */
    public static synchronized ObjectNode get() { return cfg.deepCopy(); }

    public static synchronized String getString(String k, String dflt) {
        return cfg.hasNonNull(k) ? cfg.get(k).asText() : dflt;
    }

    public static synchronized boolean getBool(String k, boolean dflt) {
        return cfg.hasNonNull(k) ? cfg.get(k).asBoolean() : dflt;
    }

    public static synchronized int getInt(String k, int dflt) {
        return cfg.hasNonNull(k) ? cfg.get(k).asInt() : dflt;
    }

    /**
     * 读取数据库中实际存储的整数值（不经 DEFAULTS 合并）。
     * 用途：配置版本号判断——默认值合并会让"老用户未存该 key"与"新用户默认值"
     * 无法区分，必须以存储中是否真实存在为准。
     * @param k    配置键
     * @param dflt 存储中不存在或无法解析时的返回值
     * @return 存储的原始整数，或 dflt
     */
    public static int getStoredInt(String k, int dflt) {
        String raw = Db.loadSettings().get(k);
        if (raw == null) return dflt;
        try { return Integer.parseInt(raw.trim()); }
        catch (NumberFormatException e) { return dflt; }
    }

    /**
     * 合并写入配置：仅白名单 key 生效；写内存后整体持久化（事务式逐条 upsert）。
     * @param partial 待合并字段
     */
    public static synchronized void set(ObjectNode partial) {
        partial.fields().forEachRemaining(en -> {
            if (ALLOWED_KEYS.contains(en.getKey())) cfg.set(en.getKey(), en.getValue());
        });
        // 持久化全部字段
        cfg.fields().forEachRemaining(en -> Db.upsertSetting(en.getKey(), Json.stringify(en.getValue())));
    }
}
