package com.localagent.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localagent.tools.Proc;
import com.localagent.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 MCP 运行环境探测与常见"纯本地"server 配置模板。
 *
 * 核心功能：
 * 1. 探测 npx / uvx / python 三类常见 MCP 启动器是否在本机可用及版本；
 *    进程执行全部走 {@link Proc}（参数数组、死代理环境、不经交互式 shell），
 *    探测动作本身不访问网络；
 * 2. 提供 filesystem / memory / time 三个确定为本地能力的 stdio server 模板，
 *    生成可直接合并进 mcpServers 的 JSON 片段；
 * 3. Windows 兼容：npx 在本机是 npx.cmd 批处理，ProcessBuilder 无法直接启动，
 *    模板与探测统一包装为 cmd.exe /c npx …（VS Code 等 MCP 宿主的通行做法）；
 *    uvx/python 为 .exe，直接启动。
 *
 * 诚实说明：npx/uvx 首次拉取 server 包需要外网，请在有网环境预先执行一次预热；
 * 本类不发起下载，只检测启动器与生成配置，运行期 MCP 子进程仍注入死代理。
 *
 * 可测试性：命令执行抽象为 {@link Runner}，OS 判断作为参数传入模板函数，
 * 单测用假 Runner 即可覆盖，不依赖本机是否安装 node/uv。
 *
 * 创建时间：2026-09-15，核心用途：降低离线 MCP 服务的配置门槛与排错成本。
 */
public final class LocalToolchain {

    /** 版本探测超时（毫秒）：仅跑 --version，8 秒足够。 */
    private static final int PROBE_TIMEOUT_MS = 8_000;

    private LocalToolchain() {}

    /**
     * 命令执行抽象（便于单测注入假实现）。
     */
    @FunctionalInterface
    public interface Runner {
        /**
         * 执行一条外部命令。
         * @param command 完整命令行（命令 + 参数，均为独立元素）
         * @return 执行结果（退出码 + 标准输出）
         * @throws Exception 进程启动失败/超时/中断时抛出，调用方按"不可用"处理
         */
        Proc.Result run(List<String> command) throws Exception;
    }

    /**
     * 一个运行环境的探测结果。
     * @param key       唯一键：npx / uvx / python
     * @param display   界面展示名
     * @param available 是否可用（版本命令退出码 0 且有输出）
     * @param version   版本输出首行（不可用时为空串）
     */
    public record Probe(String key, String display, boolean available, String version) {}

    /**
     * 一个 server 配置模板。
     * @param id           模板唯一 id
     * @param serverName   默认写入 mcpServers 的服务短名
     * @param toolchainKey 依赖的运行环境 key（npx/uvx）
     * @param description  界面说明（含用途与参数含义）
     * @param command      配置中 command 的 argv 前缀（Windows npx 已包 cmd.exe /c）
     * @param args         配置中 args 的默认值
     */
    public record Template(String id, String serverName, String toolchainKey, String description,
                           List<String> command, List<String> args) {}

    /** 模板插入结果。 @param json 合并后的 mcpServers JSON；@param serverName 实际写入的短名（可能因冲突加后缀） */
    public record InsertResult(String json, String serverName) {}

    /**
     * 默认 Runner：经 Proc 以死代理环境执行，8 秒超时。
     * @return Runner 实例
     */
    public static Runner defaultRunner() {
        return command -> Proc.exec(command.get(0), command.subList(1, command.size()),
                null, PROBE_TIMEOUT_MS, null);
    }

    /**
     * 探测 npx / uvx / python 三个运行环境（并行）。
     * 执行逻辑：对每个环境构造版本命令（Windows 的 npx 包 cmd.exe /c），
     * 三个探测各起一个守护线程并行执行，总等待时间约等于最慢的一个（约 8 秒），
     * 避免 Windows 上 python 命未安装的商店别名时串行累计到二十余秒；
     * 任何异常或非零退出都按"不可用"处理，绝不抛出。
     * @param runner  命令执行器（需支持多线程并发调用；默认 Proc 实现无共享状态）
     * @param windows 是否按 Windows 方式拼命令
     * @return 三个环境的探测结果（固定顺序 npx、uvx、python）
     */
    public static List<Probe> probe(Runner runner, boolean windows) {
        // 单项探测任务：键 + 展示名 + 版本命令
        record Task(String key, String display, List<String> command) {}
        List<Task> tasks = List.of(
                new Task("npx", "Node.js / npx",
                        windows ? List.of("cmd.exe", "/c", "npx", "--version") : List.of("npx", "--version")),
                new Task("uvx", "uv / uvx",
                        List.of(windows ? "uvx.exe" : "uvx", "--version")),
                new Task("python", "Python",
                        List.of(windows ? "python.exe" : "python", "--version")));
        Probe[] out = new Probe[tasks.size()];
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < tasks.size(); i++) {
            final int idx = i;
            Task task = tasks.get(i);
            Thread th = new Thread(() -> out[idx] = probeOne(runner, task.key(), task.display(), task.command()),
                    "toolchain-probe-" + task.key());
            th.setDaemon(true);
            th.start();
            threads.add(th);
        }
        for (Thread th : threads) {
            try {
                // Proc 自身超时 8 秒，join 留 2 秒收尾余量；极端超时也不阻塞 UI 过久
                th.join(PROBE_TIMEOUT_MS + 2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        // join 超时等极端情况下仍未写回的槽位，按不可用兜底
        for (int i = 0; i < out.length; i++) {
            if (out[i] == null) {
                Task task = tasks.get(i);
                out[i] = new Probe(task.key(), task.display(), false, "");
            }
        }
        return List.of(out);
    }

    /**
     * 探测单个环境并规整版本文本。
     * @param runner  命令执行器
     * @param key     环境键
     * @param display 展示名
     * @param command 版本命令
     * @return 探测结果
     */
    private static Probe probeOne(Runner runner, String key, String display, List<String> command) {
        try {
            Proc.Result r = runner.run(command);
            String text = (r.out() == null ? "" : r.out()).trim();
            if (r.code() == 0 && !text.isEmpty()) {
                String firstLine = text.split("\\R", 2)[0].trim();
                return new Probe(key, display, true, firstLine);
            }
            return new Probe(key, display, false, "");
        } catch (Exception e) {
            return new Probe(key, display, false, "");
        }
    }

    /**
     * 返回全部内置模板（与探测结果无关，UI 自行按可用性过滤）。
     * @param windows true 时 npx 模板包装 cmd.exe /c
     * @return 模板列表（filesystem、memory、time）
     */
    public static List<Template> templates(boolean windows) {
        List<String> npxCmd = windows
                ? List.of("cmd.exe", "/c", "npx")
                : List.of("npx");
        List<Template> list = new ArrayList<>();
        list.add(new Template("filesystem", "filesystem", "npx",
                "本地文件系统只读/受控访问；最后一个参数为允许访问的目录，默认 D:/，按需修改",
                npxCmd, List.of("-y", "@modelcontextprotocol/server-filesystem", "D:/")));
        list.add(new Template("memory", "memory", "npx",
                "本地知识图谱记忆（JSON 文件，纯本地存储）",
                npxCmd, List.of("-y", "@modelcontextprotocol/server-memory")));
        list.add(new Template("time", "time", "uvx",
                "时区/时间查询（本地计算，不联网）；--local-timezone 默认 Asia/Shanghai",
                List.of(windows ? "uvx.exe" : "uvx"),
                List.of("mcp-server-time", "--local-timezone", "Asia/Shanghai")));
        return List.copyOf(list);
    }

    /**
     * 按探测结果过滤出当前可直接使用的模板。
     * @param probes  探测结果
     * @param windows 是否 Windows（决定模板命令形态）
     * @return 依赖环境可用的模板列表
     */
    public static List<Template> availableTemplates(List<Probe> probes, boolean windows) {
        Map<String, Boolean> ok = new LinkedHashMap<>();
        for (Probe p : probes) ok.put(p.key(), p.available());
        List<Template> out = new ArrayList<>();
        for (Template t : templates(windows)) {
            if (ok.getOrDefault(t.toolchainKey(), false)) out.add(t);
        }
        return List.copyOf(out);
    }

    /**
     * 把模板渲染为 mcpServers 条目节点（command + args）。
     * @param t 模板
     * @return 形如 {"command":"...","args":[...]} 的对象节点
     */
    public static ObjectNode renderEntry(Template t) {
        ObjectNode entry = Json.mapper().createObjectNode();
        // 模板 command 是 argv 前缀：首元素为 command，其余并入 args 前部
        entry.put("command", t.command().get(0));
        ArrayNode args = entry.putArray("args");
        for (int i = 1; i < t.command().size(); i++) args.add(t.command().get(i));
        t.args().forEach(args::add);
        return entry;
    }

    /**
     * 把模板插入现有 mcpServers JSON（纯函数，不落盘）。
     * 执行逻辑：解析现有文本（空白按 {}）-> 顶层必须是对象 -> 短名冲突时
     * 依次加 2/3 后缀 -> 写入条目并重新序列化。
     * @param existingJson 当前配置文本
     * @param t            待插入模板
     * @return 插入结果（新 JSON + 实际短名）
     * @throws IllegalArgumentException 现有文本不是合法 JSON 对象时抛出
     */
    public static InsertResult insertTemplate(String existingJson, Template t) {
        JsonNode root;
        try {
            root = Json.mapper().readTree(existingJson == null || existingJson.isBlank()
                    ? "{}" : existingJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("现有 MCP 配置不是合法 JSON：" + e.getMessage());
        }
        if (!root.isObject()) throw new IllegalArgumentException("现有 MCP 配置必须是 JSON 对象。");
        ObjectNode obj = (ObjectNode) root;
        String name = t.serverName();
        for (int i = 2; obj.has(name); i++) name = t.serverName() + i;
        obj.set(name, renderEntry(t));
        return new InsertResult(Json.stringify(obj), name);
    }
}
