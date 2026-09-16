package com.localagent.mcp;

import com.localagent.toolkit.ToolDef;
import com.localagent.toolkit.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * MCP 外部工具目录：MCP 工具发现结果与本地内置 DEFS 的唯一合并点。
 *
 * 核心功能：
 * 1. 登记：未来的 MCP 客户端（stdio/SSE 传输层，当前工程尚未实现）完成
 *    initialize + tools/list 后，把发现结果以 {@link Discovered} 列表整体替换进来；
 * 2. 命名：每个外部工具统一使用 {@code mcp__<server>__<tool>} 限定名
 *    （对齐 Claude Code 习惯），模型侧与本地工具共用同一个 tools 数组；
 * 3. 合并：{@link #merge(List)} 把外部工具追加到本地 DEFS 之后，
 *    本地工具名永远优先——外部工具与本地重名时直接丢弃，防止恶意/配置失误的
 *    MCP server 伪装成本地高危工具（如 run_command）；
 * 4. 路由：Agent 执行时凭限定名解析出 {@link Route}(server,tool)，
 *    经 {@link Invoker} 发起 tools/call；未注册 Invoker（未连接）时返回结构化错误，
 *    不抛异常、不伪造结果，交由 Agent 回填模型。
 *
 * 设计思路：传输无关——本类只描述"工具发现后如何合并/路由"，不引入 MCP SDK、
 * 不持有任何进程/网络资源；传输层通过实现 {@link Invoker} 接入。
 * 外部工具协议不提供可信安全分级，统一按 confirm 处理，强制走既有用户审批链路。
 *
 * 线程模型：replace/merge/call 均 synchronized 或基于 volatile 不可变快照，
 * 允许 MCP 发现线程替换、Agent 循环线程并发读取。
 *
 * 创建时间：2026-09-15，核心用途：为本地工具集合并外部 MCP 工具提供唯一收口。
 */
public final class McpToolCatalog {

    /** MCP 工具限定名前缀。 */
    public static final String PREFIX = "mcp__";

    /** 外部工具统一安全分级：协议不可信，全部需要确认（受 requireConfirm 开关约束）。 */
    public static final String DEFAULT_RISK = "confirm";

    /** server/tool 标识允许的最大长度，防止异常 server 配置灌入超长工具名。 */
    private static final int MAX_ID_LEN = 64;

    /**
     * MCP tools/list 发现的单个外部工具原始信息。
     * @param server      来源 server 标识（配置中的短名，如 github/excel）
     * @param tool        server 内工具名
     * @param description 工具描述（可为 null/空，登记时补默认文案）
     * @param inputSchema 工具输入 JSON Schema（MCP 协议标准字段；可为 null）
     */
    public record Discovered(String server, String tool, String description,
                             Map<String, Object> inputSchema) {}

    /**
     * 限定名解析出的路由坐标。
     * @param server 目标 MCP server 标识
     * @param tool   server 内工具名
     */
    public record Route(String server, String tool) {}

    /**
     * MCP 调用执行通道（由未来的 MCP 客户端管理器实现并注册）。
     * 实现方负责 JSON-RPC tools/call、超时与结果文本化；抛出的任何异常都会被
     * 目录层转成 ToolResult.error，绝不允许中断 Agent 主循环。
     */
    @FunctionalInterface
    public interface Invoker {
        /**
         * 调用一个外部 MCP 工具。
         * @param server server 标识
         * @param tool   工具名
         * @param args   已通过 Schema 校验的参数 Map（非 null）
         * @return 工具执行结果（成功文本或错误）
         * @throws Exception 传输/协议异常由目录层兜底转换
         */
        ToolResult invoke(String server, String tool, Map<String, Object> args) throws Exception;
    }

    /** 内部条目：工具定义 + 路由坐标。 */
    private record Entry(ToolDef def, Route route) {}

    /** 当前已登记外部工具的不可变快照（限定名 -> 条目），保持发现顺序。 */
    private volatile Map<String, Entry> tools = Collections.emptyMap();

    /** 已注册的执行通道；null 表示当前没有连接任何 MCP 客户端。 */
    private volatile Invoker invoker;

    /**
     * 拼 MCP 工具限定名。
     * @param server server 标识
     * @param tool   工具名
     * @return {@code mcp__<server>__<tool>}
     */
    public static String qualifiedName(String server, String tool) {
        return PREFIX + server + "__" + tool;
    }

    /**
     * 判断工具名是否为 MCP 限定名（仅按前缀判断，不校验能否解析）。
     * @param name 工具名，允许 null
     * @return true 表示以 mcp__ 开头
     */
    public static boolean isMcpName(String name) {
        return name != null && name.startsWith(PREFIX);
    }

    /**
     * 解析限定名为路由坐标。
     * @param name 工具名
     * @return 解析失败（非 mcp__ 名/缺 server/缺 tool）返回 null
     */
    public static Route routeOf(String name) {
        if (!isMcpName(name)) return null;
        String rest = name.substring(PREFIX.length());
        int sep = rest.indexOf("__");
        if (sep <= 0) return null;
        String server = rest.substring(0, sep);
        String tool = rest.substring(sep + 2);
        // tool 为空串、server/tool 含 "__" 等情况统一由 validId 拒绝
        if (!validId(server) || !validId(tool)) return null;
        return new Route(server, tool);
    }

    /**
     * 整体替换已发现的 MCP 工具（每次 tools/list 刷新调用一次）。
     * 执行逻辑：逐个校验标识 -> 构造 ToolDef -> 重名后者覆盖 -> 发布不可变快照。
     * @param discovered 本次发现的全部工具；null 视为清空
     */
    public synchronized void replace(List<Discovered> discovered) {
        Map<String, Entry> next = new LinkedHashMap<>();
        if (discovered != null) {
            for (Discovered d : discovered) {
                if (d == null || !validId(d.server()) || !validId(d.tool())) continue;
                String qn = qualifiedName(d.server(), d.tool());
                next.put(qn, new Entry(toToolDef(qn, d), new Route(d.server(), d.tool())));
            }
        }
        this.tools = Collections.unmodifiableMap(next);
    }

    /** 清空全部外部工具（所有 MCP server 断开时调用）。 */
    public synchronized void clear() {
        this.tools = Collections.emptyMap();
    }

    /**
     * 注册/注销执行通道。
     * @param invoker MCP 客户端实现；传 null 回到"未连接"降级态
     */
    public void setInvoker(Invoker invoker) {
        this.invoker = invoker;
    }

    /**
     * 把外部 MCP 工具合并进本地工具列表（Agent 发送 tools 前的唯一收口）。
     * 执行逻辑：本地工具原样保留且排在前面；限定名与本地重名的外部工具丢弃
     * （本地优先策略，防止伪装）；外部工具之间以登记去重后的快照为准。
     * @param local 本地工具定义列表（已经过功能开关过滤），非 null
     * @return 合并后的新列表（不修改入参）；无外部工具时原样返回
     */
    public List<ToolDef> merge(List<ToolDef> local) {
        Map<String, Entry> snapshot = tools;
        if (snapshot.isEmpty()) return local;
        Set<String> taken = new HashSet<>();
        List<ToolDef> out = new ArrayList<>(local.size() + snapshot.size());
        for (ToolDef d : local) { taken.add(d.name()); out.add(d); }
        for (Entry e : snapshot.values()) {
            // 重名（含本地名碰撞）一律跳过：本地工具集是可信基线
            if (taken.add(e.def().name())) out.add(e.def());
        }
        return out;
    }

    /**
     * 按限定名取外部工具定义。
     * @param qualifiedName mcp__ 限定名
     * @return 工具定义；未登记返回 null
     */
    public ToolDef get(String qualifiedName) {
        Entry e = tools.get(qualifiedName);
        return e == null ? null : e.def();
    }

    /**
     * 按限定名取路由坐标。
     * @param qualifiedName mcp__ 限定名
     * @return 路由坐标；未登记返回 null
     */
    public Route route(String qualifiedName) {
        Entry e = tools.get(qualifiedName);
        return e == null ? null : e.route();
    }

    /** @return 当前已登记的外部工具数量 */
    public int size() { return tools.size(); }

    /**
     * 经执行通道调用外部工具（Agent 执行分流入口）。
     * 执行逻辑：无 Invoker 返回"未连接"结构化错误；调用异常统一兜底转 error，
     * 成功/失败结果都由 Agent 走原有的回灌/审计链路。
     * @param route 路由坐标（非 null）
     * @param args  已校验参数（null 按空 Map）
     * @return 永不返回 null；未连接或异常时 ok=false
     * 异常说明：本方法吞掉 Invoker 抛出的全部异常并转为错误结果，
     * 常见触发：server 进程退出、JSON-RPC 超时、工具内部报错
     */
    public ToolResult call(Route route, Map<String, Object> args) {
        Invoker current = invoker;
        if (current == null) {
            return ToolResult.error("MCP 服务通道未连接：当前没有可用的 MCP 客户端，外部工具 "
                    + route.server() + "/" + route.tool() + " 暂不可用。");
        }
        try {
            ToolResult r = current.invoke(route.server(), route.tool(),
                    args == null ? Map.of() : args);
            return r != null ? r : ToolResult.error("MCP 工具 " + route.server() + "/" + route.tool() + " 返回空结果");
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResult.error("MCP 调用失败（" + route.server() + "/" + route.tool() + "）：" + m);
        }
    }

    /**
     * 由发现结果构造限定名工具定义。
     * @param qn 限定名
     * @param d  发现项
     * @return risk 固定为 confirm、描述带来源前缀的工具定义
     */
    private static ToolDef toToolDef(String qn, Discovered d) {
        String rawDesc = d.description();
        String desc = "[MCP·" + d.server() + "] "
                + (rawDesc == null || rawDesc.isBlank() ? "外部 MCP 工具 " + d.tool() : rawDesc.strip());
        return new ToolDef(qn, desc, DEFAULT_RISK, sanitizeSchema(d.inputSchema()));
    }

    /**
     * 容错处理 server 上报的 inputSchema。
     * 执行逻辑：必须是 type=object 的 Map 才原样采用（深拷贝一层防外部持有修改）；
     * 缺失/畸形时退化为"任意属性对象"，宁可不校验也不误杀合法调用
     * （MCP 官方允许工具不声明 inputSchema）。
     * @param raw server 上报的原始 Schema，可为 null
     * @return 可放进 ToolDef.parameters 的 Schema Map（非 null）
     */
    private static Map<String, Object> sanitizeSchema(Map<String, Object> raw) {
        if (raw != null && "object".equals(String.valueOf(raw.get("type")))) {
            Map<String, Object> copy = new LinkedHashMap<>(raw);
            return copy;
        }
        Map<String, Object> loose = new LinkedHashMap<>();
        loose.put("type", "object");
        loose.put("properties", new LinkedHashMap<String, Object>());
        loose.put("additionalProperties", true);
        return loose;
    }

    /**
     * 校验 server/tool 标识：非空白、无首尾空白、不含分隔符序列 "__"、长度受限。
     * @param id 标识
     * @return true 表示可安全拼进限定名并可逆解析
     */
    private static boolean validId(String id) {
        if (id == null || id.isBlank() || id.length() > MAX_ID_LEN) return false;
        if (!id.equals(id.strip())) return false;
        return !id.contains("__");
    }
}
