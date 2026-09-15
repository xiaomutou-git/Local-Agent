package com.localagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localagent.tools.ToolResult;
import com.localagent.tools.Proc;
import com.localagent.util.Json;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单个 MCP 服务的 stdio 客户端（JSON-RPC 2.0，换行分隔帧）。
 *
 * 核心功能：以本机子进程方式启动一个 MCP server 命令，经 stdin/stdout 完成
 * initialize 握手、notifications/initialized 通知、tools/list 发现与
 * tools/call 调用，把结果文本化为 {@link ToolResult}。
 *
 * 离线姿态（"不能联网"模式的三层保证）：
 * 1. 只存在 stdio 传输——本类不发起任何 socket/HTTP 连接，配置层
 *    （{@link McpManager}）拒绝一切 url/http/sse 型服务；
 * 2. 子进程环境注入 {@link Proc#deadProxyEnv()} 死代理变量，遵循
 *    HTTP_PROXY/HTTPS_PROXY 的程序其出站请求会被导向 127.0.0.1:9 而失败；
 *    注意这只是纵深防御，不能阻止不读代理变量的直连程序，物理断网需 OS 防火墙；
 * 3. 子进程命令只能来自本机用户写入的配置，且其暴露的工具统一 confirm。
 *
 * 线程模型：stdout/stderr 各一个守护读线程；请求 id -> CompletableFuture
 * 由读线程完成，写管道串行化；进程退出时所有未决请求异常完成。
 *
 * 创建时间：2026-09-15，核心用途：在零网络前提下让 Agent 获得本地 MCP 工具生态。
 */
final class McpStdioClient implements AutoCloseable {

    /** 握手协议版本（2024-11-05 版兼容性最好；服务端可协商返回其他版本）。 */
    private static final String PROTOCOL_VERSION = "2024-11-05";

    /** stderr 尾部保留长度，仅用于拼进错误信息帮助排查。 */
    private static final int STDERR_TAIL_LEN = 2000;

    private final String name;
    private final Process process;
    private final BufferedWriter writer;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private final StringBuilder stderrTail = new StringBuilder();
    private volatile boolean closed;

    /**
     * 启动子进程并建立通信管道。
     * @param name       服务短名（仅用于错误文案）
     * @param command    可执行文件（参数数组方式启动，不经 shell，元字符不会被解释）
     * @param argv       命令参数列表（不含 command 本身），可为 null
     * @param extraEnv   额外环境变量（合并在死代理环境之上），可为 null
     * @throws IOException 进程启动失败（命令不存在等）时抛出
     */
    McpStdioClient(String name, String command, List<String> argv, Map<String, String> extraEnv)
            throws IOException {
        this.name = name;
        List<String> cmd = new ArrayList<>();
        cmd.add(command);
        if (argv != null) cmd.addAll(argv);
        ProcessBuilder pb = new ProcessBuilder(cmd);
        // 继承一份系统环境（保证 PATH 等可用于发现 node/npx），但覆盖代理为死地址
        pb.environment().clear();
        pb.environment().putAll(Proc.deadProxyEnv());
        if (extraEnv != null) pb.environment().putAll(extraEnv);
        pb.redirectErrorStream(false);
        this.process = pb.start();
        this.writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

        Thread outReader = new Thread(this::readLoop, "mcp-" + name + "-out");
        outReader.setDaemon(true);
        outReader.start();
        Thread errReader = new Thread(this::drainStderr, "mcp-" + name + "-err");
        errReader.setDaemon(true);
        errReader.start();

        // 进程退出兜底：唤醒所有未决请求，避免调用方永久等待
        process.onExit().thenRun(this::failAllPending);
    }

    /**
     * MCP 初始化握手：发 initialize，再发 notifications/initialized。
     * @param timeoutMs 握手超时毫秒
     * @throws Exception 超时、进程退出、服务端返回 JSON-RPC error 时抛出
     */
    void initialize(long timeoutMs) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "local-agent-jvm", "version", "1.0"));
        // 握手完成即可；服务端协商返回的协议版本对当前使用的方法集无影响
        request("initialize", params, timeoutMs);
        notify("notifications/initialized", Map.of());
    }

    /**
     * 拉取该服务的全部工具。
     * @param timeoutMs 超时毫秒
     * @return 发现项列表（已转换为目录可登记的形态）
     * @throws Exception 超时、进程退出、协议错误时抛出
     */
    List<McpToolCatalog.Discovered> listTools(long timeoutMs) throws Exception {
        JsonNode result = request("tools/list", Map.of(), timeoutMs);
        List<McpToolCatalog.Discovered> tools = new ArrayList<>();
        for (JsonNode t : result.path("tools")) {
            String toolName = t.path("name").asText("");
            if (toolName.isBlank()) continue;
            String desc = t.path("description").asText("");
            Map<String, Object> schema = null;
            JsonNode input = t.path("inputSchema");
            if (input.isObject()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = Json.mapper().convertValue(input, Map.class);
                schema = m;
            }
            tools.add(new McpToolCatalog.Discovered(name, toolName, desc, schema));
        }
        return tools;
    }

    /**
     * 调用一个工具并把 MCP 结果文本化。
     * 执行逻辑：tools/call -> result.content 逐块提取文本；
     * isError=true 或 JSON-RPC error 转为 {@link ToolResult#error(String)}。
     * @param toolName  服务内工具名
     * @param args      参数（已通过 Schema 校验）
     * @param timeoutMs 超时毫秒
     * @return 永不返回 null；超时/异常时 ok=false 且错误信息可回灌模型
     */
    ToolResult callTool(String toolName, Map<String, Object> args, long timeoutMs) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", args == null ? Map.of() : args);
        try {
            JsonNode result = request("tools/call", params, timeoutMs);
            boolean isError = result.path("isError").asBoolean(false);
            String text = flattenContent(result.path("content"));
            if (text.isBlank()) text = isError ? "工具返回错误（无文本内容）" : "（工具无文本输出）";
            return isError ? ToolResult.error(text) : ToolResult.ok(text);
        } catch (TimeoutException e) {
            return ToolResult.error("MCP 工具 " + name + "/" + toolName + " 调用超时（" + timeoutMs + "ms）");
        } catch (Exception e) {
            String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return ToolResult.error("MCP 工具 " + name + "/" + toolName + " 调用失败：" + m);
        }
    }

    /**
     * 把 MCP content 块数组拍平为纯文本。
     * @param content content 节点（可能缺失/非数组）
     * @return 拼接后的文本；图片/音频/资源等非文本块给占位说明
     */
    private String flattenContent(JsonNode content) {
        if (!content.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : content) {
            String type = block.path("type").asText("");
            switch (type) {
                case "text" -> {
                    String t = block.path("text").asText("");
                    if (!t.isEmpty()) sb.append(t).append("\n\n");
                }
                case "image" -> sb.append("[图片内容：本助手当前不支持把 MCP 图片回灌模型]\n\n");
                case "audio" -> sb.append("[音频内容：本助手不支持音频回灌]\n\n");
                case "resource", "resource_link" -> {
                    String text = block.path("resource").path("text").asText("");
                    sb.append(text.isBlank() ? "[资源块：" + block.path("resource").path("uri").asText("?") + "]\n\n"
                            : text + "\n\n");
                }
                default -> sb.append("[不支持的 MCP 内容类型：" + type + "]\n\n");
            }
        }
        return sb.toString().strip();
    }

    /**
     * 发送 JSON-RPC 请求并等待结果。
     * @param method    方法名
     * @param params    参数 Map
     * @param timeoutMs 超时毫秒
     * @return result 字段节点
     * @throws Exception 超时抛 TimeoutException；error 帧/进程退出抛 IOException
     */
    private JsonNode request(String method, Object params, long timeoutMs) throws Exception {
        if (closed) throw new IOException("连接已关闭");
        int id = nextId.getAndIncrement();
        ObjectNode msg = Json.mapper().createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("id", id);
        msg.put("method", method);
        msg.set("params", Json.mapper().valueToTree(params));
        CompletableFuture<JsonNode> fut = new CompletableFuture<>();
        pending.put(id, fut);
        try {
            sendRaw(msg);
            return fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            pending.remove(id);
            throw e;
        }
    }

    /**
     * 发送 JSON-RPC 通知（无 id，不等待响应）。
     * @param method 方法名
     * @param params 参数
     * @throws IOException 管道写入失败时抛出
     */
    private void notify(String method, Object params) throws IOException {
        ObjectNode msg = Json.mapper().createObjectNode();
        msg.put("jsonrpc", "2.0");
        msg.put("method", method);
        msg.set("params", Json.mapper().valueToTree(params));
        sendRaw(msg);
    }

    /**
     * 向子进程 stdin 写入一行 JSON 帧（写操作串行化）。
     * @param node 消息节点
     * @throws IOException 管道关闭/破裂时抛出
     */
    private void sendRaw(ObjectNode node) throws IOException {
        synchronized (writeLock) {
            writer.write(Json.stringify(node));
            writer.write('\n');
            writer.flush();
        }
    }

    /**
     * stdout 读循环：逐行解析 JSON-RPC 帧并分发——
     * 带 id+result/error 的响应完成未决请求；服务端请求（如 ping）给应答；
     * 通知帧（无 id）忽略。
     */
    private void readLoop() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                try { dispatch(Json.mapper().readTree(line)); }
                catch (Exception ignored) { /* 单帧解析失败不杀连接 */ }
            }
        } catch (IOException e) {
            // 进程正常关闭时管道读取会抛异常，忽略
        } finally {
            failAllPending();
        }
    }

    /**
     * 分发一帧 JSON-RPC 消息。
     * @param frame 已解析帧
     */
    private void dispatch(JsonNode frame) {
        JsonNode idNode = frame.get("id");
        JsonNode resultNode = frame.get("result");
        JsonNode errorNode = frame.get("error");
        String method = frame.path("method").asText("");
        // 1) 响应帧：完成请求 future（error 帧异常完成）
        if (idNode != null && !idNode.isNull() && (resultNode != null || errorNode != null)) {
            int id = idNode.asInt(-1);
            CompletableFuture<JsonNode> fut = pending.remove(id);
            if (fut == null) return;
            if (errorNode != null) {
                fut.completeExceptionally(new IOException("MCP 错误(" + errorNode.path("code").asInt(-1)
                        + ")：" + errorNode.path("message").asText("未知错误")));
            } else {
                fut.complete(resultNode);
            }
            return;
        }
        // 2) 服务端请求：ping 回 result，其余回 method not found(-32601)
        if (idNode != null && !idNode.isNull() && !method.isEmpty()) {
            ObjectNode resp = Json.mapper().createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.set("id", idNode);
            if ("ping".equals(method)) {
                resp.set("result", Json.mapper().createObjectNode());
            } else {
                ObjectNode err = Json.mapper().createObjectNode();
                err.put("code", -32601);
                err.put("message", "method not found（本机助手不支持服务端发起该请求）");
                resp.set("error", err);
            }
            try { sendRaw(resp); } catch (IOException ignored) { }
            return;
        }
        // 3) 通知帧（notifications/* 等）：无需响应，忽略
    }

    /** 持续抽干 stderr，保留尾部文本，防止管道阻塞并用于错误诊断。 */
    private void drainStderr() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            char[] buf = new char[1024];
            int n;
            while ((n = r.read(buf)) != -1) {
                synchronized (stderrTail) {
                    stderrTail.append(buf, 0, n);
                    if (stderrTail.length() > STDERR_TAIL_LEN) {
                        stderrTail.delete(0, stderrTail.length() - STDERR_TAIL_LEN);
                    }
                }
            }
        } catch (IOException ignored) { }
    }

    /** 进程退出或读循环结束时，把所有未决请求异常完成。 */
    private void failAllPending() {
        IOException e = new IOException("MCP 服务 " + name + " 进程已退出" + stderrSuffix());
        for (Map.Entry<Integer, CompletableFuture<JsonNode>> en : pending.entrySet()) {
            en.getValue().completeExceptionally(e);
        }
        pending.clear();
    }

    /** @return 有 stderr 内容时附上尾部片段的错误后缀，否则空串 */
    private String stderrSuffix() {
        synchronized (stderrTail) {
            String tail = stderrTail.toString().strip();
            return tail.isEmpty() ? "" : "，stderr：" + tail;
        }
    }

    /**
     * 关闭连接：结束未决请求并销毁子进程（先优雅 destroy，短等后 destroyForcibly）。
     */
    @Override
    public void close() {
        closed = true;
        failAllPending();
        try { process.destroy(); } catch (Exception ignored) { }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }
}
