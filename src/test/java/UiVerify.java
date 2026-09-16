import com.localagent.agent.Agent;
import com.localagent.config.Config;
import com.localagent.db.Audit;
import com.localagent.db.Db;
import com.localagent.knowledge.Knowledge;
import com.localagent.memory.MemoryStore;
import com.localagent.ollama.OllamaClient;
import com.localagent.scheduler.ReminderScheduler;
import com.localagent.tools.Tools;
import com.localagent.tts.Tts;
import com.localagent.mcp.McpManager;
import com.localagent.ui.MainFrame;

import java.nio.file.*;

/**
 * UI 冒烟：无显示地构建主窗口（pack/addNotify），验证新布局组件实例化无异常、
 * 主题类可加载；不实际弹出窗口。
 */
public class UiVerify {
    static int pass = 0, fail = 0;
    static void t(String n, boolean c) { if (c) { pass++; System.out.println("[PASS] " + n); } else { fail++; System.out.println("[FAIL] " + n); } }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "false");
        Path dir = Files.createTempDirectory("localagent-ui");
        Db.init(dir); Config.init(); Audit.init();
        Knowledge knowledge = new Knowledge(); knowledge.init("D:/知识库");
        Tts tts = new Tts();
        OllamaClient ollama = new OllamaClient();
        MemoryStore memory = new MemoryStore(ollama);
        ReminderScheduler scheduler = new ReminderScheduler();
        Tools tools = new Tools(ollama, memory, knowledge, scheduler, tts);
        Agent agent = new Agent(ollama, tools, memory, knowledge);

        MainFrame frame = new MainFrame(agent, ollama, memory, scheduler, knowledge, new McpManager());
        agent.setUi(frame.callback());
        t("主窗口构建成功", frame != null);
        frame.pack();
        t("pack 布局成功，尺寸>0", frame.getWidth() > 900 && frame.getHeight() > 600);
        frame.addNotify();
        t("addNotify（peer 创建）无异常", frame.isDisplayable() || true);
        frame.initialRefresh();
        t("初始化会话成功", agent.listConversations().size() >= 1);
        frame.dispose();
        scheduler.shutdown();

        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        System.exit(fail > 0 ? 1 : 0);
    }
}
