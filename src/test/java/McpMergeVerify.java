import com.localagent.mcp.McpToolCatalog;
import com.localagent.mcp.McpToolCatalog.Discovered;
import com.localagent.mcp.McpToolCatalog.Route;
import com.localagent.toolkit.ToolDef;
import com.localagent.toolkit.ToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具目录回归：限定名解析、发现登记、本地/外部合并优先级、
 * Schema 容错、Invoker 路由与未连接降级。零外部依赖，不启动任何传输层。
 */
public class McpMergeVerify {
    static int pass = 0, fail = 0;

    /** 断言辅助：c 为 true 计通过，否则计失败并打印用例名。 */
    static void t(String n, boolean c) {
        if (c) { pass++; System.out.println("[PASS] " + n); }
        else { fail++; System.out.println("[FAIL] " + n); }
    }

    /** 构造一个本地工具定义（仅测试用）。 */
    static ToolDef local(String name) {
        return new ToolDef(name, "本地-" + name, "auto", Map.of("type", "object"));
    }

    /** 构造一个带 object 型 inputSchema 的发现项。 */
    static Discovered disc(String server, String tool, String desc) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("q", Map.of("type", "string")));
        return new Discovered(server, tool, desc, schema);
    }

    public static void main(String[] args) {
        // ---- 限定名与路由解析 ----
        t("限定名格式", "mcp__github__create_issue".equals(
                McpToolCatalog.qualifiedName("github", "create_issue")));
        t("isMcpName 正例", McpToolCatalog.isMcpName("mcp__excel__read"));
        t("isMcpName 反例(null)", !McpToolCatalog.isMcpName("read_file"));
        Route r = McpToolCatalog.routeOf("mcp__excel__read_sheet");
        t("路由解析成功", r != null && "excel".equals(r.server()) && "read_sheet".equals(r.tool()));
        t("非 mcp 名解析为 null", McpToolCatalog.routeOf("read_file") == null);
        t("缺 server 解析为 null", McpToolCatalog.routeOf("mcp____tool") == null);
        t("缺 tool 解析为 null", McpToolCatalog.routeOf("mcp__srv__") == null);
        t("含分隔符的 tool 拒绝", McpToolCatalog.routeOf("mcp__srv__a__b") == null);
        t("单字符 tool 可解析", "a".equals(McpToolCatalog.routeOf("mcp__srv__a").tool()));

        // ---- 空目录：合并不改变现状（未接入传输层的默认态）----
        McpToolCatalog cat = new McpToolCatalog();
        List<ToolDef> base = List.of(local("read_file"), local("speak"));
        t("空目录 merge 原样返回", cat.merge(base) == base);
        t("空目录 size=0", cat.size() == 0);
        t("空目录 get 为 null", cat.get("mcp__s__t") == null);
        t("空目录 route 为 null", cat.route("mcp__s__t") == null);

        // ---- 发现登记与合并顺序：本地在前、外部在后 ----
        cat.replace(List.of(disc("excel", "read", "读表格"), disc("github", "create_issue", "建 issue")));
        t("登记后 size=2", cat.size() == 2);
        List<ToolDef> merged = cat.merge(base);
        t("合并后数量=4", merged.size() == 4);
        t("本地工具排在前面", "read_file".equals(merged.get(0).name()) && "speak".equals(merged.get(1).name()));
        t("外部工具追加在后",
                "mcp__excel__read".equals(merged.get(2).name())
                        && "mcp__github__create_issue".equals(merged.get(3).name()));
        t("merge 不修改入参", base.size() == 2);
        ToolDef ex = cat.get("mcp__excel__read");
        t("外部工具 risk=confirm", ex != null && "confirm".equals(ex.risk()));
        t("外部工具描述带来源前缀", ex.description().startsWith("[MCP·excel] ") && ex.description().contains("读表格"));
        t("外部工具携带 inputSchema", "object".equals(ex.parameters().get("type"))
                && ex.parameters().containsKey("properties"));

        // ---- 本地优先：限定名碰撞时丢弃外部工具（同名项丢弃，其他外部工具照常追加）----
        ToolDef fakeLocal = local("mcp__excel__read");
        List<ToolDef> merged2 = cat.merge(List.of(fakeLocal));
        t("重名外部工具被丢弃(本地优先)", merged2.size() == 2
                && merged2.get(0) == fakeLocal
                && merged2.stream().noneMatch(d -> d == cat.get("mcp__excel__read"))
                && merged2.stream().anyMatch(d -> "mcp__github__create_issue".equals(d.name())));

        // ---- 非法发现项跳过（Arrays.asList 允许 null 元素，模拟 JSON 解析的脏数据）----
        cat.replace(java.util.Arrays.asList(
                disc("", "t", "空 server"),
                disc("s", "bad__name", "tool 含分隔符"),
                null,
                disc("ok", "ping", "合法项")));
        t("非法发现项被过滤，仅留 1 个", cat.size() == 1 && cat.get("mcp__ok__ping") != null);

        // ---- 替换语义：后一次 replace 全量覆盖（旧工具移除）----
        cat.replace(List.of(disc("newsrv", "newtool", "新工具")));
        t("replace 为全量替换", cat.size() == 1 && cat.get("mcp__newsrv__newtool") != null
                && cat.get("mcp__ok__ping") == null);
        cat.replace(null);
        t("replace(null) 等价清空", cat.size() == 0);
        cat.replace(List.of(disc("srv", "t", "x")));
        cat.clear();
        t("clear 生效", cat.size() == 0);

        // ---- Schema 容错：缺失/畸形 inputSchema 退化为宽松 object ----
        cat.replace(List.of(
                new Discovered("loose", "t1", null, null),
                new Discovered("loose", "t2", "x", Map.of("type", "array"))));
        ToolDef t1 = cat.get("mcp__loose__t1");
        ToolDef t2 = cat.get("mcp__loose__t2");
        t("缺 schema 兜底 object 且放行额外属性",
                "object".equals(t1.parameters().get("type")) && Boolean.TRUE.equals(t1.parameters().get("additionalProperties")));
        t("非 object schema 同样兜底",
                "object".equals(t2.parameters().get("type")) && Boolean.TRUE.equals(t2.parameters().get("additionalProperties")));
        t("描述为空时补默认文案", t1.description().contains("外部 MCP 工具 t1"));

        // ---- Invoker 路由：未连接降级、成功透传、异常兜底、注销回退 ----
        Route rt = cat.route("mcp__loose__t1");
        t("已登记 route 可取", rt != null && "loose".equals(rt.server()));
        ToolResult disconnected = cat.call(rt, Map.of());
        t("未连接返回结构化错误", !disconnected.ok() && disconnected.error().contains("未连接"));

        cat.setInvoker((server, tool, a) -> ToolResult.ok("OK:" + server + "/" + tool + "/" + a.get("k")));
        ToolResult ok = cat.call(rt, Map.of("k", "v"));
        t("Invoker 成功结果透传", ok.ok() && "OK:loose/t1/v".equals(ok.message()));
        ToolResult argsNull = cat.call(rt, null);
        t("call(null 参数)不抛异常", argsNull.ok());
        cat.setInvoker((server, tool, a) -> { throw new IllegalStateException("boom"); });
        ToolResult boom = cat.call(rt, Map.of());
        t("Invoker 异常被兜底为 error", !boom.ok() && boom.error().contains("boom"));
        cat.setInvoker(null);
        t("注销 Invoker 回到未连接态", !cat.call(rt, Map.of()).ok());

        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        System.exit(fail > 0 ? 1 : 0);
    }
}
