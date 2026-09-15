package com.localagent.db;

import java.nio.file.*;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SQLite 数据访问层（移植自 Electron 版 db.js）。
 *
 * 核心用途：应用全部本地持久化（设置、会话、记忆、提醒、审计日志）。
 * 设计说明：
 * - 使用 sqlite-jdbc 3.43（N-API 无关的纯 JAR，随项目 lib 分发）；
 * - 会话历史/UI 条目结构复杂且演进频繁，按 sessions.js 的整体序列化思路
 *   存为单个 JSON blob（conversations.data），避免与 messages/ui_events
 *   双表手工同步；其余表保持关系型。
 * - 全部语句使用 PreparedStatement 参数化，杜绝 SQL 注入。
 */
public final class Db {
    private static Connection conn;
    private static Path dataDir;

    private Db() {}

    /**
     * 初始化数据库：创建数据目录、打开连接、建表、迁移旧结构。幂等。
     * @return 本次是否发生了旧版 schema 迁移（true 表示调用方可执行历史数据回填）
     */
    public static synchronized boolean init(Path dir) {
        try {
            dataDir = dir;
            Files.createDirectories(dir);
            String url = "jdbc:sqlite:" + dir.resolve("agent.db");
            conn = DriverManager.getConnection(url);
            boolean migrated;
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("PRAGMA foreign_keys = ON");
                createSchema();
                migrated = migrateSchema();
            }
            return migrated;
        } catch (Exception e) {
            throw new RuntimeException("数据库初始化失败: " + e.getMessage(), e);
        }
    }

    /**
     * 旧版（Electron 迁移库）到当前 schema 的结构迁移。
     * 背景：旧库 conversations 表为关系型列（tokens_in/tokens_out/ms/turns），
     * 消息存独立 messages 表；当前版本改为 data JSON blob。CREATE TABLE IF NOT
     * EXISTS 不会修改已存在的旧表，若不迁移会导致所有会话读写抛
     * "no such column: data"，且该异常在 UI 线程被静默吞掉（表现为发消息无回应）。
     *
     * 迁移策略：缺列时直接 ALTER ADD COLUMN（旧统计列保留无害），历史消息由
     * SessionStore.backfillLegacyIfNeeded 从 messages 表回填到 data。
     *
     * @return true 表示 conversations 表刚补齐了 data 列
     * @throws SQLException 表结构查询或 ALTER 失败时抛出（由 init 统一包装）
     */
    private static boolean migrateSchema() throws SQLException {
        boolean hasData = false;
        try (ResultSet rs = conn.createStatement()
                .executeQuery("PRAGMA table_info(conversations)")) {
            while (rs.next()) {
                if ("data".equals(rs.getString("name"))) { hasData = true; break; }
            }
        }
        if (!hasData) {
            try (Statement st = conn.createStatement()) {
                // SQLite 允许为 ADD COLUMN 指定 NOT NULL + 常量默认值；旧行 data 落为 '{}'
                st.execute("ALTER TABLE conversations ADD COLUMN data TEXT NOT NULL DEFAULT '{}'");
            }
        }
        return !hasData;
    }

    public static synchronized Connection get() {
        if (conn == null) throw new IllegalStateException("Database not initialized. Call init() first.");
        return conn;
    }

    public static Path dataDir() { return dataDir; }

    private static void createSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS settings (
                    key   TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )""");
            // 会话整体存储：data 为 {history, ui, stats} 的 JSON
            st.execute("""
                CREATE TABLE IF NOT EXISTS conversations (
                    id         TEXT PRIMARY KEY,
                    title      TEXT NOT NULL DEFAULT '新对话',
                    data       TEXT NOT NULL DEFAULT '{}',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS memories (
                    id         TEXT PRIMARY KEY,
                    text       TEXT NOT NULL,
                    category   TEXT NOT NULL DEFAULT 'general',
                    source     TEXT NOT NULL DEFAULT 'assistant',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS reminders (
                    id         TEXT PRIMARY KEY,
                    text       TEXT NOT NULL,
                    due        INTEGER NOT NULL,
                    repeat     TEXT NOT NULL DEFAULT 'once',
                    created_at INTEGER NOT NULL
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS reminder_history (
                    id          INTEGER PRIMARY KEY AUTOINCREMENT,
                    reminder_id TEXT,
                    text        TEXT NOT NULL,
                    repeat      TEXT,
                    fired_at    INTEGER NOT NULL
                )""");
            st.execute("""
                CREATE TABLE IF NOT EXISTS audit_logs (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    event      TEXT NOT NULL,
                    tool       TEXT,
                    args       TEXT,
                    risk       TEXT,
                    ms         INTEGER,
                    error      TEXT,
                    reason     TEXT,
                    created_at TEXT NOT NULL
                )""");
        }
    }

    // ---- settings ----
    /** 读取全部设置项（value 为原始 JSON 文本，由 Config 解析）。 */
    public static Map<String, String> loadSettings() {
        Map<String, String> m = new LinkedHashMap<>();
        try (PreparedStatement ps = get().prepareStatement("SELECT key, value FROM settings");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) m.put(rs.getString(1), rs.getString(2));
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
        return m;
    }

    public static void upsertSetting(String key, String jsonValue) {
        try (PreparedStatement ps = get().prepareStatement(
                "INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value")) {
            ps.setString(1, key);
            ps.setString(2, jsonValue);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void close() {
        try { if (conn != null) conn.close(); } catch (Exception ignored) {}
        conn = null;
    }
}
