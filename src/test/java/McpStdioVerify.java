import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localagent.config.Config;
import com.localagent.db.Db;
import com.localagent.mcp.McpManager;
import com.localagent.mcp.McpManager.ServerState;
import com.localagent.mcp.McpManager.ServerStatus;
import com.localagent.mcp.McpToolCatalog;
import com.localagent.mcp.McpToolCatalog.Route;
import com.localagent.toolkit.ToolDef;
import com.localagent.toolkit.ToolResult;
import com.localagent.tools.Tools;
import com.localagent.util.Json;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * MCP 离线 stdio 传输端到端回归。
 *
 * 覆盖：
 * 1. 配置红线：损坏/非对象 JSON 收集为错误；http+url 条目被拒绝（不联网红线）；
 *    enabled=false 静默跳过；command 不存在的服务启动失败但不影响其他服务；
 * 2. 真实子进程链路：以当前 JBR 启动 {@link FakeMcpServer}，完成 initialize ->
 *    tools/list -> tools/call（成功文本、isError 业务失败、JSON-RPC error）；
 * 3. 目录接线：发现登记数量、risk 固定 confirm、Schema 透传、与本地 40 工具合并；
 * 4. 生命周期：shutdown 后子进程客户端移除，调用降级为"未连接"结构化错误。
 */
public class McpStdioVerify {
    /** 通过计数。 */
    static int pass = 0;
    /** 失败计数。 */
    static int fail = 0;

    /**
     * 断言辅助。
     * @param name 用例名
     * @param cond 断言条件，true 计通过，false 计失败并打印
     */
    static void t(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    /**
     * 递归删除临时目录（best-effort，静默）：按路径倒序先删文件再删空目录，
     * 遇 Windows 文件锁做短暂重试。
     * 不输出 stderr：build.ps1 以 EAP=Stop 运行测试，原生进程 stderr 会被
     * 包装成终止错误导致整轮构建中断。
     * @param root 待删除目录；为 null 或不存在时直接返回
     */
    static void deleteRecursively(Path root) {
        if (root == null || !Files.exists(root)) return;
        for (int attempt = 0; attempt < 3; attempt++) {
            boolean remaining = false;
            try (Stream<Path> walk = Files.walk(root)) {
                for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
                    try { Files.deleteIfExists(p); } catch (IOException e) { remaining = true; }
                }
            } catch (IOException e) {
                return;
            }
            if (!remaining || !Files.exists(root)) return;
            try { Thread.sleep(120); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
    }

    /**
     * 注册 JVM 退出清理钩子：先关闭 SQLite 连接释放 wal 锁，再递归删目录。
     * 本回归经 summarize() 以 System.exit 结束且存在提前 return 的环境检查
     * 分支（try/finally 无法统一覆盖），用 shutdown hook 保证零残留。
     * @param dir 退出时递归删除的临时目录
     */
    static void cleanupOnExit(Path dir) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Db.close();
            deleteRecursively(dir);
        }, "test-cleanup"));
    }

    /**
     * 回归入口。
     * @param args 未使用
     * @throws Exception 测试环境准备失败时抛出（属测试设施错误）
     */
    public static void main(String[] args) throws Exception {
        // 输出 UTF-8，保证中文断言信息在控制台可读
        System.setOut(new java.io.PrintStream(System.out, true, StandardCharsets.UTF_8));

        Path dir = Files.createTempDirectory("localagent-mcptest");
        cleanupOnExit(dir);
        Db.init(dir);
        Config.init();

        String javaExe = System.getProperty("java.home") + File.separator + "bin"
                + File.separator + (System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java");
        if (!new File(javaExe).isFile()) {
            t("测试 JRE 存在（" + javaExe + "）", false);
            summarize();
            return;
        }
        String root = new File(System.getProperty("user.dir")).getAbsolutePath();
        String cp = root + File.separator + "out" + File.separator + "classes"
                + File.pathSeparator + root + File.separator + "out" + File.separator + "test"
                + File.pathSeparator + root + File.separator + "lib" + File.separator + "*";
        if (!new File(root, "out" + File.separator + "test" + File.separator + "FakeMcpServer.class").isFile()) {
            t("FakeMcpServer 已编译到 out\\test（需在项目根目录运行）", false);
            summarize();
            return;
        }

        // ---- 1) 配置解析红线（不启动任何子进程）----
        assertBadConfig("{损坏", "顶层损坏 JSON 收集为错误");
        assertBadConfig("123", "顶层非对象 JSON 收集为错误");

        // ---- 2) 组装混合配置：1 个合法 stdio + 1 个坏命令 + 1 个网络型 + 1 个停用 ----
        ObjectNode servers = Json.mapper().createObjectNode();
        ObjectNode fake = servers.putObject("fake");
        fake.put("command", javaExe);
        fake.putArray("args")
                .add("-Dfile.encoding=UTF-8")
                .add("-cp").add(cp)
                .add("FakeMcpServer");
        ObjectNode bad = servers.putObject("badcmd");
        bad.put("command", "__nonexistent_cmd_xyz_12345__.exe");
        ObjectNode web = servers.putObject("web");
        web.put("type", "http");
        web.put("command", javaExe);
        web.put("url", "http://127.0.0.1:9/mcp");
        ObjectNode off = servers.putObject("off");
        off.put("command", javaExe);
        off.put("enabled", false);
        putServers(Json.stringify(servers));

        McpToolCatalog catalog = new McpToolCatalog();
        McpManager manager = new McpManager();
        List<String> errors = manager.start(catalog);

        t("坏命令与网络型各产生一条错误（共 2 条）", errors.size() == 2);
        t("http/url 条目被离线红线拒绝", errors.stream()
                .anyMatch(s -> s.contains("web") && s.contains("离线模式")));
        t("坏命令条目报连接失败", errors.stream()
                .anyMatch(s -> s.contains("badcmd") && s.contains("连接失败")));
        t("enabled=false 条目静默跳过（无错误）", errors.stream().noneMatch(s -> s.contains("off")));
        t("仅 1 个服务连接成功", manager.connectedCount() == 1);

        // ---- 3) 发现结果登记与 Schema 透传 ----
        t("发现 2 个外部工具", catalog.size() == 2);
        String echoQn = McpToolCatalog.qualifiedName("fake", "echo");
        String boomQn = McpToolCatalog.qualifiedName("fake", "boom");
        ToolDef echoDef = catalog.get(echoQn);
        t("echo 工具已登记", echoDef != null);
        t("外部工具 risk 固定为 confirm", echoDef != null && "confirm".equals(echoDef.risk()));
        t("描述带来源前缀", echoDef != null && echoDef.description().contains("[MCP·fake]"));
        t("inputSchema 透传（properties.text）", echoDef != null
                && "object".equals(String.valueOf(echoDef.parameters().get("type")))
                && echoDef.parameters().get("properties") instanceof Map<?, ?> props
                && props.get("text") instanceof Map<?, ?>);
        t("boom 工具已登记", catalog.get(boomQn) != null);

        List<ToolDef> merged = catalog.merge(new Tools(null, null, null, null, null).list());
        t("本地 40 + 外部 2 = 42 合并", merged.size() == 42);
        t("合并列表含 echo 限定名", merged.stream().anyMatch(d -> echoQn.equals(d.name())));

        // ---- 4) 真实 tools/call：成功 / isError / JSON-RPC error ----
        Route echoRoute = catalog.route(echoQn);
        t("echo 路由可解析", echoRoute != null && "fake".equals(echoRoute.server()));
        ToolResult ok = catalog.call(echoRoute, Map.of("text", "你好-mcp"));
        t("echo 调用成功", ok.ok());
        t("echo 文本经子进程 UTF-8 往返精确", "ECHO:你好-mcp".equals(ok.message()));

        ToolResult boom = catalog.call(catalog.route(boomQn), Map.of());
        t("isError=true 转为失败结果", !boom.ok() && boom.error() != null && boom.error().contains("boom"));

        ToolResult unknown = catalog.call(new Route("fake", "no_such_tool"), Map.of());
        t("未知工具回 JSON-RPC error 并转失败",
                !unknown.ok() && unknown.error() != null && unknown.error().contains("-32601"));

        // ---- 4.5) statuses 快照四态齐全 ----
        ServerStatus sFake = find(manager.statuses(), "fake");
        t("statuses: fake CONNECTED 且工具数 2",
                sFake != null && sFake.state() == ServerState.CONNECTED && sFake.toolCount() == 2);
        ServerStatus sBad = find(manager.statuses(), "badcmd");
        t("statuses: badcmd FAILED", sBad != null && sBad.state() == ServerState.FAILED);
        ServerStatus sWeb = find(manager.statuses(), "web");
        t("statuses: web REJECTED", sWeb != null && sWeb.state() == ServerState.REJECTED);
        ServerStatus sOff = find(manager.statuses(), "off");
        t("statuses: off SKIPPED", sOff != null && sOff.state() == ServerState.SKIPPED);

        // ---- 4.6) 一次性测试连接：四态正确且绝不污染正式连接 ----
        List<ServerStatus> tested = McpManager.testConnections(Json.stringify(servers));
        t("测试连接覆盖全部 4 个条目", tested.size() == 4);
        ServerStatus tFake = find(tested, "fake");
        t("测试: fake CONNECTED 2 工具",
                tFake != null && tFake.state() == ServerState.CONNECTED && tFake.toolCount() == 2);
        t("测试: badcmd FAILED", find(tested, "badcmd") != null
                && find(tested, "badcmd").state() == ServerState.FAILED);
        t("测试: web REJECTED（不起进程）", find(tested, "web") != null
                && find(tested, "web").state() == ServerState.REJECTED);
        t("测试: off SKIPPED", find(tested, "off") != null
                && find(tested, "off").state() == ServerState.SKIPPED);
        t("测试后正式目录未被污染（仍 2 工具）", catalog.size() == 2);
        t("测试后正式连接仍可调用",
                catalog.call(echoRoute, Map.of("text", "正式连接")).ok()
                        && catalog.call(echoRoute, Map.of("text", "正式连接")).message()
                                .equals("ECHO:正式连接"));
        List<ServerStatus> badJsonTest = McpManager.testConnections("{损坏");
        t("测试: 损坏 JSON 返回单条 REJECTED",
                badJsonTest.size() == 1 && badJsonTest.get(0).state() == ServerState.REJECTED
                        && "*".equals(badJsonTest.get(0).name()));
        t("测试: 空配置返回空状态", McpManager.testConnections("{}").isEmpty());

        // 连接存活期间重复 start 幂等：直接返回上次错误快照，不重复拉起子进程
        t("重复 start 幂等返回快照", manager.start(catalog) == errors);
        t("幂等 start 后连接数不变", manager.connectedCount() == 1);

        // ---- 5) 关闭后降级：未连接错误，进程被销毁 ----
        manager.shutdown();
        ToolResult afterClose = catalog.call(echoRoute, Map.of("text", "x"));
        t("shutdown 后调用返回未连接错误",
                !afterClose.ok() && afterClose.error() != null && afterClose.error().contains("未连接"));
        t("shutdown 后连接数为 0", manager.connectedCount() == 0);
        // shutdown 后允许重新 start；重复 shutdown 不抛异常
        manager.shutdown();

        // ---- 5.5) reconcile 热切换：扩到双服务 -> 停用 -> 再开单服务 ----
        ObjectNode twoServers = Json.mapper().createObjectNode();
        twoServers.set("fake", fake.deepCopy());
        twoServers.set("fake2", fake.deepCopy());
        putServers(Json.stringify(twoServers));
        putEnabled(true);
        List<ServerStatus> stTwo = manager.reconcile(catalog);
        t("热重连后 2 个服务在线", manager.connectedCount() == 2);
        t("热重连后目录 4 个工具", catalog.size() == 4);
        t("热重连状态含 fake2 CONNECTED", find(stTwo, "fake2") != null
                && find(stTwo, "fake2").state() == ServerState.CONNECTED);
        String echo2Qn = McpToolCatalog.qualifiedName("fake2", "echo");
        t("热重连后新服务工具可调用",
                catalog.call(catalog.route(echo2Qn), Map.of("text", "二号")).message().equals("ECHO:二号"));
        t("热重连后旧服务工具可调用",
                catalog.call(echoRoute, Map.of("text", "一号")).message().equals("ECHO:一号"));

        // 开关关闭：进程销毁 + 目录清空 + Invoker 摘除
        putEnabled(false);
        List<ServerStatus> stOff = manager.reconcile(catalog);
        t("停用 reconcile 返回空状态", stOff.isEmpty());
        t("停用后连接数 0", manager.connectedCount() == 0);
        t("停用后目录清空", catalog.size() == 0);
        t("停用后调用降级为未连接",
                !catalog.call(echoRoute, Map.of()).ok());

        // 再开启：按单服务配置恢复，Agent 下一轮即可用，全程无需重启
        putServers(Json.stringify(servers));
        putEnabled(true);
        manager.reconcile(catalog);
        t("再开启后恢复 1 个连接", manager.connectedCount() == 1);
        t("再开启后目录恢复 2 个工具", catalog.size() == 2);
        t("再开启后 echo 恢复调用",
                "ECHO:恢复".equals(catalog.call(echoRoute, Map.of("text", "恢复")).message()));
        manager.shutdown();

        summarize();
    }

    /**
     * 写入一条预期非法的 mcpServers 配置并断言 start 收集到单条解析错误。
     * @param badJson 非法配置文本
     * @param caseName 用例名
     */
    private static void assertBadConfig(String badJson, String caseName) {
        putServers(badJson);
        List<String> errs = new McpManager().start(new McpToolCatalog());
        t(caseName, errs.size() == 1 && errs.get(0).contains("MCP 配置解析失败"));
    }

    /**
     * 覆盖 mcpServers 配置（走白名单写入通道）。
     * @param json 配置 JSON 文本
     */
    private static void putServers(String json) {
        ObjectNode p = Json.mapper().createObjectNode();
        p.put("mcpServers", json);
        Config.set(p);
    }

    /**
     * 覆盖 mcpEnabled 开关（走白名单写入通道）。
     * @param enabled 是否启用
     */
    private static void putEnabled(boolean enabled) {
        ObjectNode p = Json.mapper().createObjectNode();
        p.put("mcpEnabled", enabled);
        Config.set(p);
    }

    /**
     * 按服务名在状态列表中查找状态。
     * @param st   状态列表
     * @param name 服务短名
     * @return 匹配项；找不到返回 null
     */
    private static ServerStatus find(List<ServerStatus> st, String name) {
        for (ServerStatus s : st) if (s.name().equals(name)) return s;
        return null;
    }

    /** 打印汇总并以退出码表达成败（build.ps1 按退出码判断）。 */
    private static void summarize() {
        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }
}
