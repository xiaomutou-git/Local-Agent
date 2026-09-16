package com.localagent.scheduler;

import com.localagent.db.Db;
import com.localagent.toolkit.ToolResult;

import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * 提醒调度器（移植 scheduler.js）。
 * 每 10 秒扫描到期提醒，触发通知回调，并按 repeat 规则续期/入历史。
 */
public class ReminderScheduler {
    private static final Set<String> REPEATS = Set.of("once", "hourly", "daily", "weekly");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "reminder-tick"); t.setDaemon(true); return t;
    });
    private Consumer<Reminder> onFire;
    /** 启动后首轮扫描的过期提醒汇总回调（P0-4：关机期间积压的提醒合并成一条通知，避免逐条刷屏）。 */
    private Consumer<List<Reminder>> onCatchUp;
    /** 是否尚未执行首轮扫描（首轮触发汇总补发，之后恢复逐条实时提醒）。 */
    private volatile boolean firstScan = true;

    public record Reminder(String id, String text, long due, String repeat, long createdAt) {}

    /**
     * 初始化调度（无汇总回调重载，首轮过期项仍逐条触发）。
     * @param onFire 单条提醒到期回调
     */
    public void init(Consumer<Reminder> onFire) {
        init(onFire, null);
    }

    /**
     * 初始化调度。首轮扫描（initialDelay=0，应用启动即执行）若发现关机期间
     * 积压的到期提醒，整体交给 onCatchUp 汇总成一条通知；其后恢复逐条 onFire。
     * @param onFire    单条提醒实时到期回调
     * @param onCatchUp 启动补发汇总回调（可为 null，null 时首轮也逐条触发）
     */
    public void init(Consumer<Reminder> onFire, Consumer<List<Reminder>> onCatchUp) {
        this.onFire = onFire;
        this.onCatchUp = onCatchUp;
        exec.scheduleAtFixedRate(this::tick, 0, 10, TimeUnit.SECONDS);
    }

    public void shutdown() { exec.shutdownNow(); }

    public ToolResult schedule(Map<String, Object> a) {
        String text = str(a.get("text"));
        if (text == null || text.isBlank()) return ToolResult.error("提醒内容不能为空。");
        String repeat = str(a.get("repeat"));
        repeat = (repeat == null || !REPEATS.contains(repeat)) ? "once" : repeat;
        long due;
        Object at = a.get("at");
        Object delay = a.get("delayMinutes");
        if (at != null && !String.valueOf(at).isBlank()) {
            try { due = OffsetDateTime.parse(String.valueOf(at)).toInstant().toEpochMilli(); }
            catch (Exception e) { try { due = LocalDateTime.parse(String.valueOf(at)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(); }
                catch (Exception e2) { return ToolResult.error("at 时间格式无效，请使用 ISO 时间（如 2026-09-10T09:00）。"); } }
        } else if (delay != null) {
            due = System.currentTimeMillis() + (long)(Double.parseDouble(String.valueOf(delay)) * 60000);
        } else return ToolResult.error("请提供 at（ISO 时间）或 delayMinutes。");
        if (due <= System.currentTimeMillis()) return ToolResult.error("提醒时间已过去，请指定未来时间。");
        String id = "rm_" + Long.toString(System.currentTimeMillis(), 36) + randomHex(3);
        try (PreparedStatement ps = Db.get().prepareStatement("INSERT INTO reminders(id,text,due,repeat,created_at) VALUES(?,?,?,?,?)")) {
            ps.setString(1, id); ps.setString(2, text); ps.setLong(3, due); ps.setString(4, repeat); ps.setLong(5, System.currentTimeMillis());
            ps.executeUpdate();
        } catch (SQLException e) { return ToolResult.error("提醒写入失败: " + e.getMessage()); }
        return ToolResult.ok("已设定提醒：" + text + "（" + FMT.format(Instant.ofEpochMilli(due).atZone(ZoneId.systemDefault()).toLocalDateTime()) + "）");
    }

    public ToolResult remove(String id) {
        if (id == null) return ToolResult.error("参数无效。");
        try (PreparedStatement ps = Db.get().prepareStatement("DELETE FROM reminders WHERE id=?")) {
            ps.setString(1, id);
            int n = ps.executeUpdate();
            return n > 0 ? ToolResult.ok("已取消提醒。") : ToolResult.error("提醒不存在或已触发。");
        } catch (SQLException e) { return ToolResult.error("取消失败: " + e.getMessage()); }
    }

    public List<Reminder> list() {
        List<Reminder> out = new ArrayList<>();
        try (Statement st = Db.get().createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM reminders ORDER BY due ASC")) {
            while (rs.next()) out.add(map(rs));
        } catch (SQLException ignored) {}
        return out;
    }

    public ToolResult listTool() {
        List<Reminder> items = list();
        if (items.isEmpty()) return ToolResult.ok("当前没有未触发的提醒。");
        StringBuilder sb = new StringBuilder("提醒列表：\n");
        for (Reminder r : items)
            sb.append("- ").append(r.id()).append(" [").append(r.repeat()).append("] ")
              .append(r.text()).append("（").append(FMT.format(Instant.ofEpochMilli(r.due()).atZone(ZoneId.systemDefault()).toLocalDateTime())).append("）\n");
        return ToolResult.ok(sb.toString().trim());
    }

    public List<Map<String, Object>> historyList(int limit) {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = Db.get().prepareStatement(
                "SELECT reminder_id,text,repeat,fired_at FROM reminder_history ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", rs.getString(1)); m.put("text", rs.getString(2));
                m.put("repeat", rs.getString(3)); m.put("firedAt", rs.getLong(4));
                out.add(m);
            }
        } catch (SQLException ignored) {}
        return out;
    }

    private synchronized void tick() {
        long now = System.currentTimeMillis();
        List<Reminder> due = new ArrayList<>();
        try (Statement st = Db.get().createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM reminders WHERE due <= " + now)) {
            while (rs.next()) due.add(map(rs));
        } catch (Exception ignored) { return; }
        // 首轮扫描 = 应用刚启动：关机期间积压项合并为一条汇总通知，避免开屏连弹
        boolean catchUp = firstScan;
        firstScan = false;
        if (catchUp && onCatchUp != null && !due.isEmpty()) {
            try { onCatchUp.accept(List.copyOf(due)); } catch (Exception ignored) {}
        }
        for (Reminder r : due) {
            if (!catchUp || onCatchUp == null) {
                if (onFire != null) { try { onFire.accept(r); } catch (Exception ignored) {} }
            }
            history(r.text(), r.repeat());
            // 重复周期：关机跨多个周期时，旧逻辑只推进一格会在随后每 10 秒
            // 连续补触发（刷屏）；这里一路推进到严格晚于当前的下一个周期点
            Long period = switch (r.repeat()) {
                case "hourly" -> 3600_000L;
                case "daily" -> 86_400_000L;
                case "weekly" -> 7 * 86_400_000L;
                default -> null;
            };
            Long next = null;
            if (period != null) {
                next = r.due() + period;
                while (next <= now) next += period;
            }
            try {
                if (next != null) {
                    try (PreparedStatement ps = Db.get().prepareStatement("UPDATE reminders SET due=? WHERE id=?")) {
                        ps.setLong(1, next); ps.setString(2, r.id()); ps.executeUpdate();
                    }
                } else {
                    try (PreparedStatement ps = Db.get().prepareStatement("DELETE FROM reminders WHERE id=?")) {
                        ps.setString(1, r.id()); ps.executeUpdate();
                    }
                }
            } catch (SQLException ignored) {}
        }
    }

    private void history(String text, String repeat) {
        try (PreparedStatement ps = Db.get().prepareStatement("INSERT INTO reminder_history(reminder_id,text,repeat,fired_at) VALUES(?,?,?,?)")) {
            ps.setString(1, null); ps.setString(2, text); ps.setString(3, repeat); ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
            trimHistory();
        } catch (SQLException ignored) {}
    }

    private void trimHistory() {
        try (Statement st = Db.get().createStatement()) {
            st.execute("DELETE FROM reminder_history WHERE id NOT IN (SELECT id FROM reminder_history ORDER BY id DESC LIMIT 200)");
        } catch (SQLException ignored) {}
    }

    private static Reminder map(ResultSet rs) throws SQLException {
        return new Reminder(rs.getString("id"), rs.getString("text"), rs.getLong("due"), rs.getString("repeat"), rs.getLong("created_at"));
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
    private static String randomHex(int bytes) {
        byte[] b = new byte[bytes]; new Random().nextBytes(b);
        StringBuilder sb = new StringBuilder(); for (byte x : b) sb.append(String.format("%02x", x)); return sb.toString();
    }
}
