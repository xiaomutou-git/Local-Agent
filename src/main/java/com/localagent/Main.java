package com.localagent;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localagent.agent.Agent;
import com.localagent.agent.SessionStore;
import com.localagent.config.Config;
import com.localagent.db.Audit;
import com.localagent.db.Db;
import com.localagent.knowledge.Knowledge;
import com.localagent.mcp.McpManager;
import com.localagent.memory.MemoryStore;
import com.localagent.ollama.OllamaClient;
import com.localagent.scheduler.ReminderScheduler;
import com.localagent.tools.Tools;
import com.localagent.tts.Tts;
import com.localagent.util.Json;
import com.localagent.ui.MainFrame;
import com.localagent.ui.UiTheme;

import javax.swing.*;
import java.nio.file.Path;

/**
 * 本机助手 JVM 版入口。
 *
 * 启动顺序：数据目录 -> SQLite -> 配置 -> 审计清理 -> 知识库 -> 提醒调度
 * -> TTS/记忆/Ollama/工具/Agent -> Swing 界面 -> 状态轮询。
 */
public class Main {
    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        UiTheme.applyGlobal();

        Path dataDir = Path.of(System.getProperty("user.home"), "AppData", "Roaming", "本机助手", "data");
        // Db.init 内部完成旧版 schema 迁移；迁移发生时把旧 messages 表历史回填到 data blob，
        // 否则旧库升级后所有会话读写都会因缺 data 列失败（界面表现为发消息无回应）
        boolean migrated = Db.init(dataDir);
        Config.init();
        if (migrated) new SessionStore().backfillLegacyIfNeeded();
        upgradeConfig();
        Audit.init();

        Knowledge knowledge = new Knowledge();
        knowledge.init(Config.getString("knowledgeDir", "D:/知识库"));

        Tts tts = new Tts();
        OllamaClient ollama = new OllamaClient();
        MemoryStore memory = new MemoryStore(ollama);
        ReminderScheduler scheduler = new ReminderScheduler();
        Tools tools = new Tools(ollama, memory, knowledge, scheduler, tts);
        Agent agent = new Agent(ollama, tools, memory, knowledge);

        // 离线 MCP：仅连接配置中的"本地 stdio"服务（网络型服务会被拒绝）。
        // 后台守护线程执行握手与状态协调（开关关时 reconcile 为空操作），
        // 避免子进程启动慢拖住界面；失败只记录、不影响主程序；设置中改配置可热重连
        McpManager mcp = new McpManager();
        Thread mcpBoot = new Thread(() -> mcp.reconcile(agent.mcpCatalog()), "mcp-bootstrap");
        mcpBoot.setDaemon(true);
        mcpBoot.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try { mcp.shutdown(); } catch (Exception ignored) { }
        }, "mcp-shutdown"));

        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame(agent, ollama, memory, scheduler, knowledge, mcp);
            agent.setUi(frame.callback());
            scheduler.init(reminder ->
                SwingUtilities.invokeLater(() -> frame.notifyReminder(reminder.text())));
            frame.setVisible(true);
            frame.initialRefresh();
        });
    }

    /**
     * 历史配置一次性升级（按 configVersion 只执行一次，之后尊重用户手动修改）。
     * v1 -> v2：旧版默认 keepAlive=0（用完即卸载模型），连续对话每轮都要冷启动
     * 重新载入 8B 模型（数秒）；升级为 30 分钟保活，消除重复加载造成的等待。
     */
    private static void upgradeConfig() {
        // 必须以数据库中实际存储的版本为准：Config 内存值含 DEFAULTS 合并，
        // 老用户即使没存过 configVersion 也会读到默认的 2，使升级被错误跳过
        if (Config.getStoredInt("configVersion", 1) >= 2) return;
        ObjectNode patch = Json.mapper().createObjectNode();
        // 同步检查"存储的"keepAlive：缺失（Electron 旧库默认即 0）或显式 0 都升级
        if (Config.getStoredInt("keepAlive", 0) == 0) patch.put("keepAlive", 1800000);
        patch.put("configVersion", 2);
        Config.set(patch);
    }
}
