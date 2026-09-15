import com.localagent.agent.SessionStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话导出 Markdown 纯函数回归（P0-5）。
 *
 * 验证 SessionStore.toMarkdown：标题/导出时间行、user/assistant/tool/system
 * 四种角色渲染（system 跳过）、assistant 携带 tool_calls 时标注数量、
 * tool 内容逐行引用、空标题兜底「未命名会话」。
 *
 * 创建时间：2026-09，核心用途：保证会话导出格式不随存储结构调整而退化。
 */
public class SessionExportVerify {
    static int pass = 0, fail = 0;

    /**
     * 断言辅助。
     * @param name 用例名
     * @param cond 断言条件
     */
    static void t(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    /**
     * 构造一条历史消息 Map。
     * @param role    角色 user/assistant/tool/system
     * @param content 消息内容
     * @return 有序 Map（与 Db JSON 反序列化结构一致）
     */
    static Map<String, Object> msg(String role, String content) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("content", content);
        return m;
    }

    /**
     * 回归入口。
     * @param args 未使用
     */
    public static void main(String[] args) {
        List<Map<String, Object>> history = new ArrayList<>();
        history.add(msg("system", "系统提示词，不应被导出"));
        history.add(msg("user", "帮我查一下天气"));
        Map<String, Object> ai = msg("assistant", "好的，我来调用工具。");
        ai.put("tool_calls", List.of(Map.of("name", "run_command"), Map.of("name", "read_file")));
        history.add(ai);
        Map<String, Object> tool = msg("tool", "第一行结果\n第二行结果");
        tool.put("tool_name", "run_command");
        history.add(tool);
        history.add(msg("assistant", "最终答复。"));

        String md = SessionStore.toMarkdown("测试会话", history);

        t("标题以一级标题输出", md.startsWith("# 测试会话"));
        t("含导出时间行", md.contains("导出时间："));
        t("system 角色不导出", !md.contains("系统提示词，不应被导出"));
        t("用户段落标题", md.contains("## 用户") && md.contains("帮我查一下天气"));
        t("助手工具调用数量标注", md.contains("## 助手（含 2 个工具调用）"));
        t("工具段落与逐行引用",
                md.contains("### 工具：run_command") && md.contains("> 第一行结果") && md.contains("> 第二行结果"));
        t("普通助手段落保留", md.contains("## 助手\n") && md.contains("最终答复。"));

        String empty = SessionStore.toMarkdown("  ", List.of());
        t("空白标题兜底为未命名会话", empty.startsWith("# 未命名会话"));

        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        System.exit(fail > 0 ? 1 : 0);
    }
}
