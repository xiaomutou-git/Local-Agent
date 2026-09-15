package com.localagent.agent;

import com.localagent.db.Db;
import com.localagent.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.sql.*;
import java.util.*;

/**
 * 会话持久化（移植 sessions.js）：历史/统计整体序列化为 JSON blob。
 */
public class SessionStore {
    public record Conv(String id, String title, long createdAt, long updatedAt,
                       ObjectNode data) {}

    public String create(String id, String title) {
        long now = System.currentTimeMillis();
        ObjectNode data = Json.mapper().createObjectNode();
        data.set("history", Json.mapper().createArrayNode());
        data.set("stats", Json.mapper().createObjectNode());
        try (PreparedStatement ps = Db.get().prepareStatement(
                "INSERT INTO conversations(id,title,data,created_at,updated_at) VALUES(?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, title); ps.setString(3, Json.stringify(data));
            ps.setLong(4, now); ps.setLong(5, now); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
        return id;
    }

    public void save(String id, String title, Object history, java.util.Map<String, Long> stats) {
        ObjectNode data = Json.mapper().createObjectNode();
        data.set("history", Json.mapper().valueToTree(history));
        data.set("stats", Json.mapper().valueToTree(stats == null ? Map.of() : stats));
        try (PreparedStatement ps = Db.get().prepareStatement(
                "UPDATE conversations SET title=?,data=?,updated_at=? WHERE id=?")) {
            ps.setString(1, title); ps.setString(2, Json.stringify(data));
            ps.setLong(3, System.currentTimeMillis()); ps.setString(4, id); ps.executeUpdate();
        } catch (SQLException e) { throw new RuntimeException(e); }
    }

    public Optional<Conv> get(String id) {
        try (PreparedStatement ps = Db.get().prepareStatement("SELECT * FROM conversations WHERE id=?")) {
            ps.setString(1, id);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                ObjectNode data = (ObjectNode) Json.mapper().readTree(rs.getString("data"));
                return Optional.of(new Conv(rs.getString("id"), rs.getString("title"),
                        rs.getLong("created_at"), rs.getLong("updated_at"), data));
            }
        } catch (Exception ignored) {}
        return Optional.empty();
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        try (Statement st = Db.get().createStatement();
             ResultSet rs = st.executeQuery("SELECT id,title,updated_at FROM conversations ORDER BY updated_at DESC")) {
            while (rs.next()) out.add(Map.of("id", rs.getString(1), "title", rs.getString(2), "updatedAt", rs.getLong(3)));
        } catch (SQLException ignored) {}
        return out;
    }

    public int remove(String id) {
        try (PreparedStatement ps = Db.get().prepareStatement("DELETE FROM conversations WHERE id=?")) {
            ps.setString(1, id); return ps.executeUpdate();
        } catch (SQLException e) { return 0; }
    }

    /**
     * 旧版历史回填：把旧库 messages 表中的逐条消息聚合为 data JSON blob。
     *
     * 执行逻辑：检测 messages 表是否存在 -> 找出 data 仍为 '{}' 的会话 ->
     * 按消息 id 顺序读取并映射为当前 history 结构 -> 回写 conversations.data。
     * 映射规则：
     * - user/system：{role, content}；
     * - assistant：{role, content}，tool_calls 字段可解析为 JSON 时原样附带；
     *   纯思考消息（content 空且无 tool_calls）跳过，避免空消息影响后续请求；
     * - tool：{role:"tool", tool_name, content}。
     *
     * @return 成功回填的会话数量；messages 表不存在（全新库）时返回 0
     * @throws 无显式抛出：单条消息解析失败仅跳过该条，保证迁移不阻断启动
     */
    public int backfillLegacyIfNeeded() {
        try (Statement st = Db.get().createStatement()) {
            boolean messagesExists = false;
            try (ResultSet rs = st.executeQuery(
                    "SELECT 1 FROM sqlite_master WHERE type='table' AND name='messages'")) {
                messagesExists = rs.next();
            }
            if (!messagesExists) return 0;

            // 按会话分组保序收集消息
            Map<String, List<Map<String, Object>>> byConv = new LinkedHashMap<>();
            try (ResultSet rs = st.executeQuery(
                    "SELECT conversation_id, role, content, tool_calls, tool_name "
                            + "FROM messages ORDER BY id")) {
                while (rs.next()) {
                    String convId = rs.getString(1);
                    String role = rs.getString(2);
                    String content = rs.getString(3) == null ? "" : rs.getString(3);
                    String toolCallsRaw = rs.getString(4);
                    String toolName = rs.getString(5);
                    Map<String, Object> msg = mapLegacyMessage(role, content, toolCallsRaw, toolName);
                    if (msg == null) continue;
                    byConv.computeIfAbsent(convId, k -> new ArrayList<>()).add(msg);
                }
            }

            int done = 0;
            for (var e : byConv.entrySet()) {
                String convId = e.getKey();
                List<Map<String, Object>> history = e.getValue();
                if (history.isEmpty()) continue;
                // 仅回填尚未有历史数据的会话，避免覆盖新版本写入
                ObjectNode data = Json.mapper().createObjectNode();
                data.set("history", Json.mapper().valueToTree(history));
                data.set("stats", Json.mapper().createObjectNode());
                try (PreparedStatement ps = Db.get().prepareStatement(
                        "UPDATE conversations SET data=? WHERE id=? AND data='{}'")) {
                    ps.setString(1, Json.stringify(data));
                    ps.setString(2, convId);
                    done += ps.executeUpdate();
                }
            }
            return done;
        } catch (SQLException e) {
            // 迁移失败不阻断启动：会话功能退化为空历史，主流程仍可用
            System.err.println("[migrate] 旧消息回填失败: " + e.getMessage());
            return 0;
        }
    }

    /**
     * 将旧 messages 表的一行映射为当前 history 消息结构。
     * @param role         消息角色（user/assistant/system/tool）
     * @param content      消息文本（可能为空串）
     * @param toolCallsRaw assistant 携带的工具调用 JSON 文本（其他角色为 null）
     * @param toolName     tool 角色消息对应的工具名（其他角色为 null）
     * @return 可直接序列化进 history 的有序 Map；无需保留的纯思考消息返回 null
     */
    private Map<String, Object> mapLegacyMessage(String role, String content,
                                                  String toolCallsRaw, String toolName) {
        if (role == null) return null;
        Map<String, Object> msg = new LinkedHashMap<>();
        switch (role) {
            case "tool" -> {
                msg.put("role", "tool");
                msg.put("tool_name", toolName == null ? "工具" : toolName);
                msg.put("content", content);
                return msg;
            }
            case "user", "system" -> {
                msg.put("role", role);
                msg.put("content", content);
                return msg;
            }
            case "assistant" -> {
                JsonNode calls = null;
                if (toolCallsRaw != null && !toolCallsRaw.isBlank()) {
                    try {
                        JsonNode n = Json.mapper().readTree(toolCallsRaw);
                        if (n.isArray() && !n.isEmpty()) calls = n;
                    } catch (Exception ignored) { /* 损坏的工具调用 JSON 忽略 */ }
                }
                if (content.isBlank() && calls == null) return null; // 纯思考消息
                msg.put("role", "assistant");
                msg.put("content", content);
                if (calls != null) msg.put("tool_calls", Json.mapper().convertValue(calls, Object.class));
                return msg;
            }
            default -> {
                return null;
            }
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> historyOf(Conv c) {
        JsonNode h = c.data().get("history");
        return h == null ? new ArrayList<>() : Json.mapper().convertValue(h, List.class);
    }
}
