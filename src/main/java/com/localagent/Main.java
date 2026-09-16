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
    /** 单实例锁：静态持有至进程结束（不可在 main 返回时释放——Swing EDT 会让 JVM 继续运行）。 */
    private static com.localagent.system.SingleInstance instanceLock;

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}
        UiTheme.applyGlobal();

        Path dataDir = Path.of(System.getProperty("user.home"), "AppData", "Roaming", "本机助手", "data");
        // P0-7：最先获取单实例锁，避免多开引发 SQLite 并发写、提醒重复触发
        try {
            instanceLock = com.localagent.system.SingleInstance.tryAcquire(dataDir.resolve("app.lock"));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                    "启动失败：" + e.getMessage(), "本机助手", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (instanceLock == null) {
            JOptionPane.showMessageDialog(null,
                    "本机助手已经在运行中，请勿重复启动。", "本机助手", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        runApp(dataDir);
    }

    /**
     * 应用主体启动流程（持有单实例锁期间执行）。
     * @param dataDir 应用数据目录（SQLite/配置/知识索引/锁文件所在）
     */
    private static void runApp(Path dataDir) {
        // Db.init 内部完成旧版 schema 迁移；迁移发生时把旧 messages 表历史回填到 data blob，
        // 否则旧库升级后所有会话读写都会因缺 data 列失败（界面表现为发消息无回应）
        boolean migrated = Db.init(dataDir);
        Config.init();
        if (migrated) new SessionStore().backfillLegacyIfNeeded();
        upgradeConfig();
        // 首次启动引导：未完成过 Ollama 安装/模型配置时，模态引导用户一键安装
        // （仅 Windows；任何异常都不得阻断主界面启动，状态栏会照常提示离线）
        if (System.getProperty("os.name", "").toLowerCase().contains("win")
                && !Config.getBool("ollamaBootstrapDone", false)) {
            try {
                SwingUtilities.invokeAndWait(() ->
                        com.localagent.ui.OllamaSetupDialog.showIfNeeded(null));
            } catch (Exception e) {
                System.err.println("[bootstrap] Ollama 引导未运行：" + e.getMessage());
            }
        }
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
            // P0-4：实时到期逐条气泡；启动首轮扫描到的关机期间积压项合并一条补发通知
            scheduler.init(reminder ->
                SwingUtilities.invokeLater(() -> frame.notifyReminder(reminder.text())),
                missed ->
                SwingUtilities.invokeLater(() -> frame.notifyCatchUp(missed)));
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
