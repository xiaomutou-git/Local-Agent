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

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;

/** UI 截图验证：显示主窗口，2.5 秒后截全屏保存，再退出。 */
public class UiShot {
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "false");
        Path dir = Files.createTempDirectory("localagent-shot");
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
        frame.setVisible(true);
        frame.initialRefresh();
        frame.toFront(); frame.requestFocus();

        // 注入演示消息以验证气泡/工具条视觉（通过反射访问私有 ChatPane）
        var chatField = MainFrame.class.getDeclaredField("chatPane");
        chatField.setAccessible(true);
        Object chat = chatField.get(frame);
        var addUser = chat.getClass().getMethod("addUser", String.class);
        var addAssist = chat.getClass().getMethod("addAssistantFinal", String.class);
        var updateTool = chat.getClass().getMethod("updateTool", String.class, String.class, String.class, String.class);
        addUser.invoke(chat, "帮我看一下这个目录里有什么文件");
        updateTool.invoke(chat, "t1", "list_directory", "done", "{\"path\":\"D:\\\\项目\\\\time\"}");
        addAssist.invoke(chat, "这是一个 Electron 桌面助手项目，主要包含 src 源码、scripts 脚本和构建产物。需要我详细展开吗？");
        addUser.invoke(chat, "那再帮我读一下 D 盘的一个文档，顺便确认网络安全配置");
        updateTool.invoke(chat, "t2", "run_command", "running", "{\"program\":\"powershell.exe\",\"args\":[\"-Command\",\"Get-Process\"]}");

        Thread.sleep(2800);
        Robot robot = new Robot();
        Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        BufferedImage shot = robot.createScreenCapture(screen);
        Path out = Paths.get("d:/项目/time-jvm/dist/ui-shot.png");
        Files.createDirectories(out.getParent());
        ImageIO.write(shot, "png", out.toFile());
        System.out.println("SHOT_SAVED " + out);
        scheduler.shutdown();
        System.exit(0);
    }
}
