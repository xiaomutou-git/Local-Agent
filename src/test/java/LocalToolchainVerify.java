import com.fasterxml.jackson.databind.JsonNode;
import com.localagent.mcp.LocalToolchain;
import com.localagent.tools.Proc;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地 MCP 运行环境探测与模板生成的纯逻辑回归。
 *
 * 覆盖：
 * 1. probe：假 Runner 模拟 npx 可用/uvx 异常/python 非零退出，验证可用判定、
 *    版本截取与异常兜底（探测绝不抛出）；
 * 2. templates：Windows 下 npx 模板包装 cmd.exe /c，uvx 用 uvx.exe；
 *    非 Windows 直接用原生命令；renderEntry 的 command/args 拼接；
 * 3. availableTemplates 按探测结果过滤；
 * 4. insertTemplate：空配置插入、保留既有条目、重名自动后缀、坏 JSON/非对象拒绝。
 */
public class LocalToolchainVerify {
    /** 通过计数。 */
    static int pass = 0;
    /** 失败计数。 */
    static int fail = 0;

    /**
     * 断言辅助。
     * @param name 用例名
     * @param cond 条件，true 通过
     */
    static void t(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    /**
     * 按命令前缀匹配结果的假执行器（不启动任何真实进程）。
     * 用法：key 取命令列表首元素（cmd.exe 场景取第三个元素 npx），
     * 命中返回预设结果或抛出预设异常。
     */
    static final class FakeRunner implements LocalToolchain.Runner {
        /** key -> 结果/异常。 */
        private final Map<String, Object> rules = new HashMap<>();

        /**
         * 登记一条规则。
         * @param key    匹配键（可执行名）
         * @param result 返回结果
         * @return 自身（链式）
         */
        FakeRunner put(String key, Proc.Result result) { rules.put(key, result); return this; }

        /**
         * 登记一条"启动即抛异常"规则。
         * @param key 匹配键
         * @param e   抛出异常
         * @return 自身（链式）
         */
        FakeRunner putThrow(String key, Exception e) { rules.put(key, e); return this; }

        /**
         * 执行假命令。
         * @param command 完整命令行
         * @return 预设结果
         * @throws Exception 预设异常；未登记时抛 IllegalStateException
         */
        @Override
        public Proc.Result run(List<String> command) throws Exception {
            // Windows npx 探测形态：cmd.exe /c npx --version，真实程序键取 npx
            String key = command.get(0).equals("cmd.exe") ? command.get(2) : command.get(0);
            Object v = rules.get(key);
            if (v instanceof Exception e) throw e;
            if (v instanceof Proc.Result r) return r;
            return new Proc.Result(127, "", "not found");
        }
    }

    /** 回归入口。 @param args 未使用 */
    public static void main(String[] args) {
        // ---- 1) 探测：npx 可用、uvx 启动异常、python 非零退出 ----
        FakeRunner runner = new FakeRunner()
                .put("npx", new Proc.Result(0, "10.8.2\n", ""))
                .putThrow("uvx.exe", new java.io.IOException("cannot run"))
                .put("python.exe", new Proc.Result(1, "", "bad"));
        List<LocalToolchain.Probe> probes = LocalToolchain.probe(runner, true);
        LocalToolchain.Probe pNpx = byKey(probes, "npx");
        LocalToolchain.Probe pUvx = byKey(probes, "uvx");
        LocalToolchain.Probe pPy = byKey(probes, "python");
        t("探测固定 3 个环境", probes.size() == 3);
        t("npx 可用且版本取首行", pNpx.available() && "10.8.2".equals(pNpx.version()));
        t("uvx 异常按不可用处理（不抛出）", pUvx != null && !pUvx.available() && pUvx.version().isEmpty());
        t("python 非零退出按不可用", pPy != null && !pPy.available());

        // 全可用场景
        FakeRunner allOk = new FakeRunner()
                .put("npx", new Proc.Result(0, "9.0.0", ""))
                .put("uvx.exe", new Proc.Result(0, "uv 0.5.1", ""))
                .put("python.exe", new Proc.Result(0, "Python 3.11.9", ""));
        List<LocalToolchain.Probe> all = LocalToolchain.probe(allOk, true);
        t("Windows 下 uvx.exe/python.exe 可探测",
                byKey(all, "uvx").available() && byKey(all, "python").available()
                        && "Python 3.11.9".equals(byKey(all, "python").version()));

        // ---- 2) 模板命令形态 ----
        List<LocalToolchain.Template> winTpls = LocalToolchain.templates(true);
        LocalToolchain.Template wFs = byId(winTpls, "filesystem");
        LocalToolchain.Template wTime = byId(winTpls, "time");
        t("内置 3 个模板", winTpls.size() == 3);
        t("Windows npx 模板经 cmd.exe /c",
                wFs.command().equals(List.of("cmd.exe", "/c", "npx")));
        t("filesystem 默认参数含目录 D:/",
                wFs.args().contains("@modelcontextprotocol/server-filesystem") && wFs.args().contains("D:/"));
        t("Windows uvx 模板直接用 uvx.exe",
                wTime.command().equals(List.of("uvx.exe"))
                        && wTime.args().contains("--local-timezone")
                        && wTime.args().contains("Asia/Shanghai"));

        List<LocalToolchain.Template> nixTpls = LocalToolchain.templates(false);
        t("非 Windows npx 模板不经 cmd",
                byId(nixTpls, "filesystem").command().equals(List.of("npx")));
        t("非 Windows uvx 模板用 uvx",
                byId(nixTpls, "time").command().equals(List.of("uvx")));

        // renderEntry：command 取前缀首元素，其余前缀并入 args
        JsonNode fsEntry = LocalToolchain.renderEntry(wFs);
        t("renderEntry.command=cmd.exe", "cmd.exe".equals(fsEntry.path("command").asText()));
        t("renderEntry.args 以 /c npx 开头",
                fsEntry.path("args").get(0).asText().equals("/c")
                        && fsEntry.path("args").get(1).asText().equals("npx")
                        && fsEntry.path("args").size() == 5);

        // ---- 3) 按探测结果过滤模板 ----
        List<LocalToolchain.Template> onlyNpx = LocalToolchain.availableTemplates(probes, true);
        t("仅 npx 可用时给出 filesystem+memory 两个模板",
                onlyNpx.size() == 2
                        && onlyNpx.stream().anyMatch(x -> "filesystem".equals(x.id()))
                        && onlyNpx.stream().noneMatch(x -> "time".equals(x.id())));
        t("npx/uvx 均可用时给 3 个模板",
                LocalToolchain.availableTemplates(all, true).size() == 3);
        t("全部不可用时无模板",
                LocalToolchain.availableTemplates(
                        LocalToolchain.probe(new FakeRunner(), true), true).isEmpty());

        // ---- 4) insertTemplate 纯函数 ----
        LocalToolchain.InsertResult r1 = LocalToolchain.insertTemplate("", wFs);
        t("空文本插入后名为 filesystem", "filesystem".equals(r1.serverName()));
        JsonNode merged1 = parse(r1.json());
        t("插入 JSON 含 filesystem.command", merged1.path("filesystem").path("command").asText().equals("cmd.exe"));

        LocalToolchain.InsertResult r2 =
                LocalToolchain.insertTemplate("{\"old\":{\"command\":\"x.exe\",\"args\":[]}}", wFs);
        JsonNode merged2 = parse(r2.json());
        t("插入保留既有条目 old", merged2.has("old") && merged2.has("filesystem"));

        LocalToolchain.InsertResult r3 = LocalToolchain.insertTemplate(r1.json(), wFs);
        t("重名自动后缀 filesystem2", "filesystem2".equals(r3.serverName()));
        LocalToolchain.InsertResult r4 = LocalToolchain.insertTemplate(r3.json(), wFs);
        t("再次冲突后缀 filesystem3", "filesystem3".equals(r4.serverName()));
        t("后缀追加不破坏前两个",
                parse(r4.json()).has("filesystem") && parse(r4.json()).has("filesystem2"));

        boolean badJson = false;
        try { LocalToolchain.insertTemplate("{坏", wFs); }
        catch (IllegalArgumentException e) { badJson = true; }
        t("坏 JSON 抛 IllegalArgumentException", badJson);

        boolean nonObject = false;
        try { LocalToolchain.insertTemplate("[1,2]", wFs); }
        catch (IllegalArgumentException e) { nonObject = true; }
        t("非对象 JSON 抛 IllegalArgumentException", nonObject);

        // memory 模板参数
        LocalToolchain.Template wMem = byId(winTpls, "memory");
        t("memory 模板含 server-memory 包",
                wMem.args().contains("@modelcontextprotocol/server-memory"));

        System.out.println(pass + " 通过 / " + fail + " 失败");
        if (fail > 0) System.exit(1);
    }

    /**
     * 解析 JSON（断言辅助用，损坏即让测试线程失败退出）。
     * @param json JSON 文本
     * @return 节点
     */
    private static JsonNode parse(String json) {
        try { return com.localagent.util.Json.mapper().readTree(json); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    /** 按 key 找探测结果。 */
    private static LocalToolchain.Probe byKey(List<LocalToolchain.Probe> ps, String key) {
        return ps.stream().filter(p -> p.key().equals(key)).findFirst().orElse(null);
    }

    /** 按 id 找模板。 */
    private static LocalToolchain.Template byId(List<LocalToolchain.Template> ts, String id) {
        return ts.stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
    }
}
