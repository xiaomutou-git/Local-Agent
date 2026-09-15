package com.localagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.localagent.config.Config;
import com.localagent.tools.ToolResult;
import com.localagent.util.Json;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 本地服务管理器：读配置、启动各 stdio 服务、聚合工具发现结果并注册调用通道。
 *
 * 核心功能：
 * 1. 解析 Config.mcpServers（JSON），仅接受 stdio 形态 {command,args,env,enabled}；
 *    任何网络型配置（type=http/sse/streamable-http 或含 url）直接拒绝并记录原因，
 *    从源头保证"不能联网"；
 * 2. 逐个启动子进程并握手发现工具；单个服务失败只记录错误、跳过该服务，
 *    不阻断其他服务与应用启动；
 * 3. 把全部发现结果整体发布到 {@link McpToolCatalog}，并注册按 server 名
 *    路由的 Invoker；应用退出时 shutdown 销毁全部子进程；
 * 4. 可观测：{@link #statuses()} 给出每个服务的连接状态与工具数；
 *    {@link #testConnections(String)} 用一份临时 JSON 做一次性连接测试，
 *    不写配置、不污染正式连接，供设置界面"测试连接"使用；
 * 5. 热协调：{@link #reconcile(McpToolCatalog)} 按当前开关与配置整体重连/停用，
 *    用户改完配置无需重启应用。
 *
 * 设计思路：本类是 McpStdioClient 与目录之间唯一的编排者，Agent 不直接接触
 * 进程细节；start/reconcile 均在调用线程执行（UI 需自行放后台线程）。
 *
 * 创建时间：2026-09-15，核心用途：在离线模式下把本地 MCP 生态接入 Agent，
 * 并让连接状态对用户可见、可测试、可热切换。
 */
public final class McpManager {

    /** 握手/工具发现超时（启动阶段，毫秒）。 */
    private static final long STARTUP_TIMEOUT_MS = 30_000L;

    /**
     * 单个服务的连接状态（UI 层负责映射为中文文案）。
     */
    public enum ServerState {
        /** 握手与工具发现成功。 */
        CONNECTED,
        /** 配置合法但进程启动/握手失败。 */
        FAILED,
        /** 配置违反离线红线或格式非法，未启动进程。 */
        REJECTED,
        /** enabled 显式为 false，按配置跳过。 */
        SKIPPED
    }

    /**
     * 一条服务状态快照。
     * @param name      服务短名（整体配置损坏时用 "*"）
     * @param state     状态
     * @param toolCount 发现到的工具数（仅 CONNECTED 有意义）
     * @param detail    细节说明（错误原因/停用原因/"连接正常，N 个工具"）
     */
    public record ServerStatus(String name, ServerState state, int toolCount, String detail) {}

    /** 一条服务配置（stdio 形态）。 */
    private record ServerConf(String name, String command, List<String> args, Map<String, String> env) {}

    /** 已连接的客户端（name -> client），start/shutdown 同步保护。 */
    private final Map<String, McpStdioClient> clients = Collections.synchronizedMap(new LinkedHashMap<>());

    /** 最近一次 start/reconcile 的错误说明（每个失败/被拒服务一条）。 */
    private volatile List<String> lastErrors = List.of();

    /** 最近一次 start/reconcile 的逐服务状态快照。 */
    private volatile List<ServerStatus> lastStatuses = List.of();

    private volatile boolean started;

    /**
     * 按当前配置启动全部启用的本地 MCP 服务并接入目录（幂等：重复调用直接返回）。
     * 执行逻辑：解析配置 -> 拒绝网络型条目 -> 逐个启动握手发现 -> 发布工具 +
     * 注册 Invoker。单个服务失败被收集到错误列表并跳过。
     * @param catalog Agent 持有的工具目录
     * @return 本次启动的错误信息列表；空列表表示全部服务连接成功（含"无配置"）
     * 异常说明：配置 JSON 整体损坏等非逐服务异常也会包装为单条错误返回，不抛出
     */
    public synchronized List<String> start(McpToolCatalog catalog) {
        if (started) return lastErrors;
        started = true;
        List<String> errors = new ArrayList<>();
        List<ServerStatus> statuses = new ArrayList<>();
        List<ServerConf> confs;
        try {
            confs = parseConfig(Config.getString("mcpServers", "{}"), errors, statuses);
        } catch (Exception e) {
            String msg = "MCP 配置解析失败：" + e.getMessage();
            errors.add(msg);
            statuses.add(new ServerStatus("*", ServerState.REJECTED, 0, msg));
            confs = List.of();
        }

        List<McpToolCatalog.Discovered> discovered = new ArrayList<>();
        for (ServerConf conf : confs) {
            McpStdioClient client = null;
            try {
                client = new McpStdioClient(conf.name(), conf.command(), conf.args(), conf.env());
                client.initialize(STARTUP_TIMEOUT_MS);
                List<McpToolCatalog.Discovered> tools = client.listTools(STARTUP_TIMEOUT_MS);
                discovered.addAll(tools);
                clients.put(conf.name(), client);
                statuses.add(new ServerStatus(conf.name(), ServerState.CONNECTED, tools.size(),
                        "连接正常，" + tools.size() + " 个工具"));
            } catch (Exception e) {
                // 失败服务不纳入 clients；销毁其半成品进程，错误仅记录不阻断启动
                if (client != null) { try { client.close(); } catch (Exception ignored) { } }
                String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                String msg = "MCP 服务「" + conf.name() + "」连接失败：" + m;
                errors.add(msg);
                statuses.add(new ServerStatus(conf.name(), ServerState.FAILED, 0, m));
            }
        }

        // 即使全部失败也要发布（清空可能残留的旧发现）并设置路由
        catalog.replace(discovered);
        catalog.setInvoker(this::invoke);
        lastErrors = List.copyOf(errors);
        lastStatuses = List.copyOf(statuses);
        return lastErrors;
    }

    /**
     * 按当前开关与配置一次性协调到目标态（热生效入口）：
     * 开关开 -> 销毁旧连接后按最新配置整体重连；开关关 -> 销毁连接、清空目录工具
     * 与 Invoker（调用立即降级为"未连接"）。
     * @param catalog Agent 持有的工具目录
     * @return 协调后的逐服务状态快照（停用态返回空列表）
     */
    public synchronized List<ServerStatus> reconcile(McpToolCatalog catalog) {
        if (Config.getBool("mcpEnabled", false)) {
            shutdown();
            start(catalog);
        } else {
            shutdown();
            catalog.clear();
            catalog.setInvoker(null);
            lastStatuses = List.of();
        }
        return lastStatuses;
    }

    /**
     * 用一份临时 JSON 做一次性连接测试（不写 Config、不触碰任何正式连接）。
     * 执行逻辑：解析校验（红线/停用直接给状态，不起进程）-> 对每个合法条目
     * 临时启动子进程、握手、发现工具 -> 无论成败立即销毁测试进程。
     * @param json 设置界面当前编辑的 mcpServers 文本
     * @return 逐服务测试结果（保持配置中出现顺序）；整体 JSON 损坏时返回单条 REJECTED
     */
    public static List<ServerStatus> testConnections(String json) {
        JsonNode root;
        try {
            root = parseRoot(json);
        } catch (Exception e) {
            return List.of(new ServerStatus("*", ServerState.REJECTED, 0,
                    "配置解析失败：" + e.getMessage()));
        }
        List<ServerStatus> out = new ArrayList<>();
        root.fields().forEachRemaining(en -> {
            String name = en.getKey();
            JsonNode node = en.getValue();
            String reject = validateEntry(name, node);
            if (reject != null) {
                out.add(new ServerStatus(name, ServerState.REJECTED, 0, reject));
                return;
            }
            if (!node.path("enabled").asBoolean(true)) {
                out.add(new ServerStatus(name, ServerState.SKIPPED, 0, "已停用（enabled=false），未测试"));
                return;
            }
            ServerConf conf = buildConf(name, node);
            McpStdioClient client = null;
            try {
                client = new McpStdioClient(conf.name(), conf.command(), conf.args(), conf.env());
                client.initialize(STARTUP_TIMEOUT_MS);
                int count = client.listTools(STARTUP_TIMEOUT_MS).size();
                out.add(new ServerStatus(name, ServerState.CONNECTED, count,
                        "连接正常，" + count + " 个工具"));
            } catch (Exception e) {
                String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                out.add(new ServerStatus(name, ServerState.FAILED, 0, m));
            } finally {
                if (client != null) { try { client.close(); } catch (Exception ignored) { } }
            }
        });
        return List.copyOf(out);
    }

    /**
     * Invoker 路由：按 server 名找到对应子进程客户端执行 tools/call。
     * @param server 服务短名
     * @param tool   工具名
     * @param args   已校验参数
     * @return 调用结果；服务已断开时返回结构化错误
     * @throws Exception 由目录层兜底（本方法内部已处理大多数异常）
     */
    private ToolResult invoke(String server, String tool, Map<String, Object> args) throws Exception {
        McpStdioClient client = clients.get(server);
        if (client == null) {
            return ToolResult.error("MCP 服务「" + server + "」当前未连接（启动失败或已关闭）。");
        }
        // 工具调用超时复用命令执行超时配置，避免外部工具无限挂起
        long timeout = Math.max(1000L, Config.getInt("cmdTimeout", 120000));
        return client.callTool(tool, args, timeout);
    }

    /**
     * 关闭全部子进程（应用退出/重连前/停用 MCP 时调用）；关闭后允许再次 start。
     */
    public synchronized void shutdown() {
        synchronized (clients) {
            for (McpStdioClient c : clients.values()) {
                try { c.close(); } catch (Exception ignored) { }
            }
            clients.clear();
        }
        started = false;
    }

    /** @return 最近一次 start/reconcile 的错误快照 */
    public List<String> lastErrors() { return lastErrors; }

    /** @return 最近一次 start/reconcile 的逐服务状态快照 */
    public List<ServerStatus> statuses() { return lastStatuses; }

    /** @return 当前已连接服务数量 */
    public int connectedCount() { return clients.size(); }

    /**
     * 解析并校验 mcpServers JSON。
     * 执行逻辑：顶层必须是对象；逐条检查 enabled、拒绝网络型、command 必填，
     * args 必须是字符串数组，env 必须是字符串键值对象；问题条目记入 errors/statuses
     * （REJECTED）并跳过；enabled=false 记入 SKIPPED。
     * @param json     配置 JSON 字符串
     * @param errors   错误收集列表（输出参数）
     * @param statuses 状态收集列表（输出参数）
     * @return 合法且启用的服务配置列表
     * @throws Exception 顶层 JSON 无法解析或不是对象时抛出
     */
    private static List<ServerConf> parseConfig(String json, List<String> errors,
                                                List<ServerStatus> statuses) throws Exception {
        JsonNode root = parseRoot(json);
        List<ServerConf> out = new ArrayList<>();
        root.fields().forEachRemaining(en -> {
            String name = en.getKey();
            JsonNode node = en.getValue();
            String reject = validateEntry(name, node);
            if (reject != null) {
                errors.add(reject);
                statuses.add(new ServerStatus(name, ServerState.REJECTED, 0, reject));
                return;
            }
            if (!node.path("enabled").asBoolean(true)) {
                statuses.add(new ServerStatus(name, ServerState.SKIPPED, 0, "已停用（enabled=false）"));
                return;
            }
            out.add(buildConf(name, node));
        });
        return out;
    }

    /**
     * 解析顶层 JSON 为对象节点。
     * @param json 配置文本（null/空白按空对象处理）
     * @return 对象节点
     * @throws Exception JSON 损坏或顶层不是对象时抛出
     */
    private static JsonNode parseRoot(String json) throws Exception {
        JsonNode root = Json.mapper().readTree(json == null || json.isBlank() ? "{}" : json);
        if (!root.isObject()) throw new IllegalArgumentException("mcpServers 必须是 JSON 对象");
        return root;
    }

    /**
     * 由已校验节点构造服务配置。
     * @param name 服务短名
     * @param node 已通过 {@link #validateEntry} 的对象节点
     * @return 不可变服务配置
     */
    private static ServerConf buildConf(String name, JsonNode node) {
        List<String> args = new ArrayList<>();
        node.path("args").forEach(a -> args.add(a.asText()));
        Map<String, String> env = new LinkedHashMap<>();
        JsonNode envNode = node.path("env");
        if (envNode.isObject()) {
            envNode.fields().forEachRemaining(kv -> env.put(kv.getKey(), kv.getValue().asText()));
        }
        return new ServerConf(name, node.path("command").asText().trim(),
                List.copyOf(args), Map.copyOf(env));
    }

    /**
     * 校验单条服务配置。
     * @param name 服务短名
     * @param node 配置节点
     * @return 拒绝原因（中文）；null 表示格式合法
     */
    private static String validateEntry(String name, JsonNode node) {
        if (!node.isObject()) return "MCP 服务「" + name + "」配置必须是对象，已跳过";
        // —— 离线红线：任何网络型传输一律拒绝 ——
        String type = node.path("type").asText("stdio").toLowerCase();
        if (!"stdio".equals(type)) {
            return "MCP 服务「" + name + "」使用了 " + type + " 传输；离线模式仅支持本地 stdio 服务，已跳过";
        }
        if (node.has("url") || node.has("serverUrl") || node.has("endpoint")) {
            return "MCP 服务「" + name + "」包含网络地址配置；离线模式不允许远程 MCP 服务，已跳过";
        }
        if (node.path("command").asText("").isBlank()) {
            return "MCP 服务「" + name + "」缺少 command（本地可执行文件），已跳过";
        }
        if (node.has("args") && !node.path("args").isArray()) {
            return "MCP 服务「" + name + "」的 args 必须是字符串数组，已跳过";
        }
        for (JsonNode a : node.path("args")) {
            if (!a.isTextual()) return "MCP 服务「" + name + "」的 args 每个元素必须是字符串，已跳过";
        }
        if (node.has("env") && !node.path("env").isObject()) {
            return "MCP 服务「" + name + "」的 env 必须是键值对象，已跳过";
        }
        if (name == null || name.isBlank() || name.contains("__") || name.length() > 64) {
            return "MCP 服务名「" + name + "」非法（不能为空、不能含 __、长度 ≤64），已跳过";
        }
        return null;
    }
}
