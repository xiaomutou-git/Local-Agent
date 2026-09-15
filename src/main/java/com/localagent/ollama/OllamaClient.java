package com.localagent.ollama;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localagent.config.Config;
import com.localagent.util.Json;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Ollama 本地模型客户端（移植 ollama.js）。
 *
 * 安全约束（对应报告 M-07/M-08/L-04/L-08）：
 * 1) 仅允许字面回环地址（天然免疫十进制 IP/IPv6 映射/DNS rebinding）；
 * 2) URL 不得含用户名/密码（防止凭据明文落库回显）；
 * 3) 禁止端口落在系统服务/数据库/Redis/Docker 等危险端口；
 * 4) HttpClient 显式 Redirect.NEVER —— 防止被替换的回环服务以 30x 把
 *    含对话内容的 POST body 转发到外网。
 */
public final class OllamaClient {
    private static final Set<String> LOOPBACK = new HashSet<>(List.of("127.0.0.1", "localhost", "::1", "[::1]"));
    private static final Set<Integer> UNSAFE_PORTS = new HashSet<>(List.of(
            1, 7, 9, 11, 13, 15, 17, 19, 20, 21, 22, 23, 25, 37, 42, 43, 53, 69, 77, 79, 87, 95,
            101, 102, 103, 104, 109, 110, 111, 113, 115, 117, 119, 123, 135, 137, 139, 143, 161,
            179, 389, 427, 465, 512, 513, 514, 515, 526, 530, 531, 532, 540, 548, 554, 556, 563,
            587, 601, 636, 989, 990, 993, 995, 1719, 1720, 1723, 2049, 3659, 4045, 5060, 5061,
            6000, 6566, 6665, 6666, 6667, 6668, 6669, 6697, 10080,
            1433, 1521, 3306, 3389, 5432, 5985, 5986, 27017, 11211, 6379, 2375, 2376));

    private static final Pattern VISION_RE = Pattern.compile(".*-(vl|vision|vlx|mini2vl|3-vl).*", Pattern.CASE_INSENSITIVE);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER) // M-07：禁止跟随重定向
            .build();

    /** 校验并返回规范化 URI；非法则抛 IllegalArgumentException（由上层转错误提示）。 */
    public URI assertLoopback(String urlStr) {
        URI u;
        try { u = URI.create(urlStr); } catch (Exception e) {
            throw new IllegalArgumentException("Ollama 服务地址无效。");
        }
        String auth = u.getRawUserInfo();
        if (auth != null && !auth.isEmpty())
            throw new IllegalArgumentException("Ollama 服务地址不应包含用户名/密码，请去掉 user:pass@ 部分。");
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (!LOOPBACK.contains(host))
            throw new IllegalArgumentException("出于安全考虑，只允许连接本机 (127.0.0.1) 的模型服务。");
        int port = u.getPort() == -1 ? ("https".equals(u.getScheme()) ? 443 : 80) : u.getPort();
        if (UNSAFE_PORTS.contains(port))
            throw new IllegalArgumentException("端口 " + port + " 属于不安全的系统服务端口，禁止连接。");
        return u;
    }

    private String origin() { return assertLoopback(Config.getString("baseUrl", "http://127.0.0.1:11434")).resolve("/").toString().replaceAll("/$", ""); }

    public boolean isCloudModel(String name) {
        if (name == null || name.isBlank()) return true;
        return name.matches("(?i).*-cloud$") || name.contains("-cloud-");
    }

    public boolean isNonVisionModel(String n) {
        return n == null || !VISION_RE.matcher(n).matches();
    }

    /** 查询模型列表，过滤云端模型。 */
    public List<Map<String, Object>> listModels() {
        HttpRequest req = HttpRequest.newBuilder(URI.create(origin() + "/api/tags"))
                .timeout(Duration.ofSeconds(5)).GET().build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) throw new RuntimeException("连接 Ollama 失败 (" + resp.statusCode() + ")");
            JsonNode data = Json.mapper().readTree(resp.body()).get("models");
            List<Map<String, Object>> out = new ArrayList<>();
            if (data != null) for (JsonNode m : data) {
                if (isCloudModel(m.path("name").asText())) continue;
                out.add(Map.of("name", m.path("name").asText(),
                        "size", m.path("size").asLong(0)));
            }
            return out;
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException("连接 Ollama 失败: " + e.getMessage(), e); }
    }

    public record Stats(Boolean connected, String baseUrl, List<Map<String, Object>> models, String error) {}

    /** 连接状态；连不上时在收敛端口中自动发现，但不再自动写配置（M-08 收敛）。 */
    public Stats getStatus() {
        String cfg = Config.getString("baseUrl", "http://127.0.0.1:11434");
        try {
            assertLoopback(cfg);
            List<Map<String, Object>> models = listModels();
            String model = Config.getString("model", "");
            if ((model == null || model.isBlank()) && !models.isEmpty()) {
                ObjectNode patch = Json.mapper().createObjectNode();
                patch.put("model", String.valueOf(models.get(0).get("name")));
                Config.set(patch);
            }
            return new Stats(true, origin(), models, null);
        } catch (Exception e) {
            return new Stats(false, cfg, List.of(), e.getMessage());
        }
    }

    /**
     * 流式对话：逐行解析 SSE/NDJSON，每个完整 JSON 行回调一次。
     * @param onLine 收到一个 JSON 事件（含 message.content 增量 / done 等）
     */
    public void chatStream(String model, List<Map<String, Object>> messages,
                           List<Map<String, Object>> tools, Consumer<JsonNode> onLine) {
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("model", model);
        body.put("stream", true);
        body.put("think", Config.getBool("thinking", false));
        body.putObject("options").put("num_ctx", Config.getInt("numCtx", 8192));
        body.put("keep_alive", Config.getInt("keepAlive", 1800000));
        if (tools != null && !tools.isEmpty()) body.set("tools", Json.mapper().valueToTree(tools));
        body.set("messages", Json.mapper().valueToTree(messages));

        HttpRequest req = HttpRequest.newBuilder(URI.create(origin() + "/api/chat"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)))
                .build();
        try {
            HttpResponse<java.util.stream.Stream<String>> resp =
                    http.send(req, HttpResponse.BodyHandlers.ofLines());
            if (resp.statusCode() / 100 != 2)
                throw new RuntimeException("Ollama 请求失败 (" + resp.statusCode() + ")");
            final boolean[] parseWarned = {false};
            resp.body().forEach(line -> {
                if (line == null || line.isBlank()) return;
                try {
                    onLine.accept(Json.mapper().readTree(line));
                } catch (Exception parseErr) {
                    // 单行损坏不应中断整条流；但首次失败需留痕，避免"无回复"时无线索可查
                    if (!parseWarned[0]) {
                        parseWarned[0] = true;
                        System.err.println("[ollama] 流式响应存在无法解析的行: "
                                + parseErr.getMessage());
                    }
                }
            });
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException("Ollama 流式请求失败: " + e.getMessage(), e); }
    }

    /** 图片理解（视觉模型）。 */
    public String askImage(String model, String prompt, String imageB64) {
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("model", model);
        body.put("stream", false);
        body.put("keep_alive", Config.getInt("keepAlive", 1800000));
        ArrayNode msgs = body.putArray("messages");
        ObjectNode one = msgs.addObject();
        one.put("role", "user");
        one.put("content", prompt == null || prompt.isBlank() ? "请描述这张图片的内容。" : prompt);
        one.putArray("images").add(imageB64);
        HttpRequest req = HttpRequest.newBuilder(URI.create(origin() + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(180))
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build();
        try {
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) throw new RuntimeException("Ollama 请求失败 (" + resp.statusCode() + ")");
            JsonNode node = Json.mapper().readTree(resp.body());
            return node.path("message").path("content").asText("");
        } catch (Exception e) { throw new RuntimeException("图片分析失败: " + e.getMessage(), e); }
    }

    /**
     * 异步非流式生成（记忆提取等后台结构化任务），返回可显式取消的 Future。
     * 走 /api/chat 而非 /api/generate：deepseek-r1 在 generate 端点会忽略 think 参数、
     * 把大段思考写进 response 文本（实测近 19 秒）；chat 端点下思考进入独立的
     * message.thinking 字段，content 直接是所需 JSON，解析稳定、生成更短。
     *
     * 必须基于 sendAsync：同步 send 只能靠线程中断 best-effort 取消（实测无效，
     * 请求会跑完全程继续占用模型）；CompletableFuture.cancel(true) 由 JDK 保证
     * 中止底层 HTTP exchange，连接断开后 Ollama 立即停止生成、释放模型槽位。
     *
     * @param model  模型名（须为本地已存在模型）
     * @param prompt 指令文本（要求模型只输出 JSON 数组）
     * @return 以助手消息正文完成的 Future（可能含 ```json 围栏，由调用方剥离）；
     *         网络失败/非 2xx/被取消时以异常完成
     */
    public java.util.concurrent.CompletableFuture<String> generateAsync(String model, String prompt) {
        ObjectNode body = Json.mapper().createObjectNode();
        body.put("model", model).put("stream", false).put("think", false);
        body.put("keep_alive", Config.getInt("keepAlive", 1800000));
        // 记忆提取结果只需要很短的 JSON 数组。硬限输出 token 防止模型长篇展开；
        // 注意 r1（qwen3 蒸馏）即使 think=false 仍会先吐约 200~400 思考 token，
        // 预算太小会把思考吃光导致 content 为空，故给到 600（思考 + JSON + 余量）
        ObjectNode opts = body.putObject("options");
        opts.put("num_predict", 600);
        opts.put("num_ctx", Config.getInt("numCtx", 8192));
        ArrayNode msgs = body.putArray("messages");
        msgs.addObject().put("role", "user").put("content", prompt);
        HttpRequest req = HttpRequest.newBuilder(URI.create(origin() + "/api/chat"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body))).build();
        return http.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(resp -> {
            if (resp.statusCode() / 100 != 2)
                throw new RuntimeException("generate 失败 (" + resp.statusCode() + ")");
            try {
                return Json.mapper().readTree(resp.body()).path("message").path("content").asText("");
            } catch (Exception e) {
                throw new RuntimeException("generate 响应解析失败: " + e.getMessage(), e);
            }
        });
    }
}
