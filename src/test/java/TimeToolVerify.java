import com.localagent.toolkit.ToolDef;
import com.localagent.toolkit.ToolResult;
import com.localagent.tools.ToolSchemas;
import com.localagent.tools.Tools;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * get_current_time 时间感知工具回归（P0-3）。
 *
 * 验证：工具已注册（总数 37）、无参 Schema 非空且校验通过、执行成功并包含
 * 中文基准时间/星期/时区/Unix 时间戳字段，且星期与时间戳与系统时钟一致。
 *
 * 创建时间：2026-09，核心用途：防止模型时间感知能力在重构中丢失或字段错配。
 */
public class TimeToolVerify {
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
     * 回归入口。
     * @param args 未使用
     */
    public static void main(String[] args) {
        Tools tools = new Tools(null, null, null, null, null);

        ToolDef def = tools.get("get_current_time");
        t("get_current_time 已注册", def != null);
        t("工具总数为 40", tools.list().size() == 40);
        t("无参工具 Schema 非空对象", def != null && "object".equals(def.parameters().get("type")));
        t("空参数通过 Schema 校验", def != null && ToolSchemas.validate(def, Map.of()).isEmpty());

        ToolResult r = tools.execute("get_current_time", Map.of());
        t("执行成功", r != null && r.ok());
        String msg = r == null ? "" : r.message();
        t("输出含「当前系统时间」", msg.contains("当前系统时间"));
        t("输出含「时区」", msg.contains("时区"));
        t("输出含 Unix 时间戳", msg.contains("Unix 时间戳"));

        // 星期与系统时钟一致性（直接复用实现的映射规则做交叉核对）
        ZonedDateTime now = ZonedDateTime.now();
        String[] weekdays = {"星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
        String expectWeek = weekdays[now.getDayOfWeek().getValue() - 1];
        t("星期与系统时钟一致", msg.contains(expectWeek));

        // ISO 行中的 Unix 秒与当前时钟相差不超过 5 秒
        long unixNow = now.toEpochSecond();
        long printed = -1;
        for (String line : msg.lines().toList()) {
            if (line.contains("Unix 时间戳")) {
                String digits = line.replaceAll("\\D", "");
                if (!digits.isEmpty()) printed = Long.parseLong(digits.substring(0, Math.min(10, digits.length())));
            }
        }
        t("Unix 时间戳与系统时钟误差 ≤5 秒", printed > 0 && Math.abs(printed - unixNow) <= 5);

        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        System.exit(fail > 0 ? 1 : 0);
    }
}
