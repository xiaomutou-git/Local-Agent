package com.localagent.db;

import com.localagent.util.Json;

import java.sql.*;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * 审计日志（移植 audit.js）：90 天保留期、单字段 4000 字符截断。
 */
public final class Audit {
    private static final int MAX_LEN = 4000;
    private static final int RETENTION_DAYS = 90;

    private Audit() {}

    /** 启动时清理超期记录（表不存在等异常静默忽略）。 */
    public static void init() {
        try (PreparedStatement ps = Db.get().prepareStatement("DELETE FROM audit_logs WHERE created_at < ?")) {
            ps.setString(1, Instant.now().minus(RETENTION_DAYS, ChronoUnit.DAYS).toString());
            ps.executeUpdate();
        } catch (Exception ignored) {}
    }

    /** 写入一条审计（任何失败静默，不阻塞主流程）。 */
    public static void log(String event, String tool, Map<String, Object> args, String risk, Long ms, String error, String reason) {
        try {
            String argsJson = args == null ? null : clip(Json.stringify(args));
            try (PreparedStatement ps = Db.get().prepareStatement(
                    "INSERT INTO audit_logs(event,tool,args,risk,ms,error,reason,created_at) VALUES(?,?,?,?,?,?,?,?)")) {
                ps.setString(1, clip(event));
                ps.setString(2, tool == null ? null : clip(tool));
                ps.setString(3, argsJson);
                ps.setString(4, risk == null ? null : clip(risk));
                if (ms != null) ps.setLong(5, ms); else ps.setNull(5, Types.INTEGER);
                ps.setString(6, error == null ? null : clip(error));
                ps.setString(7, reason == null ? null : clip(reason));
                ps.setString(8, Instant.now().toString());
                ps.executeUpdate();
            }
        } catch (Exception ignored) {}
    }

    /** 最近审计记录（id 倒序）。 */
    public static List<Map<String, Object>> recent(int n) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = Db.get().prepareStatement(
                "SELECT event,tool,args,risk,ms,error,reason,created_at FROM audit_logs ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, n));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("t", rs.getString(8)); m.put("event", rs.getString(1)); m.put("tool", rs.getString(2));
                String args = rs.getString(3);
                m.put("args", args == null ? Map.of() : (Json.parseAny(args) == null ? Map.of() : Json.parseAny(args)));
                m.put("risk", rs.getString(4)); m.put("ms", rs.getObject(5));
                m.put("error", rs.getString(6)); m.put("reason", rs.getString(7));
                out.add(m);
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String clip(String v) {
        if (v == null) return null;
        return v.length() > MAX_LEN ? v.substring(0, MAX_LEN) + "…" : v;
    }
}
