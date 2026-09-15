import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localagent.util.Json;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 测试用假 MCP stdio 服务（被 McpStdioVerify 以子进程方式启动）。
 *
 * 行为：
 * - initialize：返回 2024-11-05 握手结果；
 * - notifications/*：忽略（含 initialized）；
 * - tools/list：暴露 echo（必填 text）与 boom（无参，业务失败）两个工具；
 * - tools/call：echo 回 ECHO:<text>；boom 回 isError=true；未知工具回 JSON-RPC error。
 * 协议帧：每行一个 JSON（UTF-8），与真实 MCP stdio 传输一致。
 */
public class FakeMcpServer {
    /**
     * 子进程入口：标准 JSON-RPC 行协议读写循环。
     * @param args 未使用
     * @throws Exception IO 异常直接退出（测试侧按进程退出处理）
     */
    public static void main(String[] args) throws Exception {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8));
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            JsonNode msg;
            try { msg = Json.mapper().readTree(line); } catch (Exception e) { continue; }
            String method = msg.path("method").asText("");
            JsonNode idNode = msg.get("id");
            boolean hasId = idNode != null && !idNode.isNull();

            if ("initialize".equals(method) && hasId) {
                ObjectNode result = Json.mapper().createObjectNode();
                result.put("protocolVersion", "2024-11-05");
                result.set("capabilities", Json.mapper().createObjectNode());
                ObjectNode info = Json.mapper().createObjectNode();
                info.put("name", "fake-mcp"); info.put("version", "1.0");
                result.set("serverInfo", info);
                send(out, idNode, result, null);
            } else if ("tools/list".equals(method) && hasId) {
                ObjectNode echo = Json.mapper().createObjectNode();
                echo.put("name", "echo");
                echo.put("description", "回显输入文本");
                ObjectNode echoSchema = Json.mapper().createObjectNode();
                echoSchema.put("type", "object");
                ObjectNode props = Json.mapper().createObjectNode();
                props.set("text", objectWith("type", "string"));
                echoSchema.set("properties", props);
                echoSchema.set("required", Json.mapper().createArrayNode().add("text"));
                echo.set("inputSchema", echoSchema);

                ObjectNode boom = Json.mapper().createObjectNode();
                boom.put("name", "boom");
                boom.put("description", "总是返回业务错误");
                ObjectNode boomSchema = Json.mapper().createObjectNode();
                boomSchema.put("type", "object");
                boomSchema.set("properties", Json.mapper().createObjectNode());
                boom.set("inputSchema", boomSchema);

                ObjectNode result = Json.mapper().createObjectNode();
                result.set("tools", Json.mapper().createArrayNode().add(echo).add(boom));
                send(out, idNode, result, null);
            } else if ("tools/call".equals(method) && hasId) {
                String tool = msg.path("params").path("name").asText("");
                if ("echo".equals(tool)) {
                    String text = msg.path("params").path("arguments").path("text").asText("");
                    ObjectNode result = Json.mapper().createObjectNode();
                    ObjectNode block = Json.mapper().createObjectNode();
                    block.put("type", "text"); block.put("text", "ECHO:" + text);
                    result.set("content", Json.mapper().createArrayNode().add(block));
                    send(out, idNode, result, null);
                } else if ("boom".equals(tool)) {
                    ObjectNode result = Json.mapper().createObjectNode();
                    result.put("isError", true);
                    ObjectNode block = Json.mapper().createObjectNode();
                    block.put("type", "text"); block.put("text", "服务端业务失败 boom");
                    result.set("content", Json.mapper().createArrayNode().add(block));
                    send(out, idNode, result, null);
                } else {
                    ObjectNode err = Json.mapper().createObjectNode();
                    err.put("code", -32601); err.put("message", "Unknown tool: " + tool);
                    send(out, idNode, null, err);
                }
            }
            // notifications/* 无 id：忽略不回
            out.flush();
        }
    }

    /** 快速构造 {"key": 字符串值} 的对象节点。 */
    private static ObjectNode objectWith(String key, String value) {
        ObjectNode n = Json.mapper().createObjectNode();
        n.put(key, value);
        return n;
    }

    /**
     * 输出一帧 JSON-RPC 响应（result 与 error 二选一）。
     * @param out    输出流
     * @param id     请求 id（原样回填）
     * @param result 成功结果，可为 null
     * @param error  错误对象，可为 null
     * @throws Exception 写入失败时抛出
     */
    private static void send(BufferedWriter out, JsonNode id, JsonNode result, JsonNode error) throws Exception {
        ObjectNode resp = Json.mapper().createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id);
        if (error != null) resp.set("error", error); else resp.set("result", result);
        out.write(Json.stringify(resp));
        out.write('\n');
        out.flush();
    }
}
