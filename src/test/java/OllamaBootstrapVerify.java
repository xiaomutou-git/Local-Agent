import com.localagent.ollama.OllamaEnv;
import com.localagent.ollama.OllamaSetup;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Ollama 首次引导纯逻辑回归：硬件选型、安装路径探测、pull 进度解析、
 * 签名校验判定、标签匹配。不触网、不启动进程（网络/进程部分由人工/集成验证）。
 */
public class OllamaBootstrapVerify {
    static int pass = 0, fail = 0;
    static void t(String n, boolean c) {
        if (c) { pass++; System.out.println("[PASS] " + n); }
        else { fail++; System.out.println("[FAIL] " + n); }
    }

    static final long GB = 1024L * 1024 * 1024;

    public static void main(String[] a) throws Exception {
        System.out.println("== 硬件档位推荐（阈值边界）==");
        t("0 字节内存回退最低档", "qwen2.5:0.5b".equals(OllamaEnv.recommend(0).tag()));
        t("2GB 内存推荐 0.5b", "qwen2.5:0.5b".equals(OllamaEnv.recommend(2 * GB).tag()));
        t("恰好 4GB 推荐 1.5b", "qwen2.5:1.5b".equals(OllamaEnv.recommend(4 * GB).tag()));
        t("7.9GB 仍为 1.5b", "qwen2.5:1.5b".equals(OllamaEnv.recommend((long) (7.9 * GB)).tag()));
        t("恰好 8GB 推荐 3b", "qwen2.5:3b".equals(OllamaEnv.recommend(8 * GB).tag()));
        t("恰好 16GB 推荐 7b", "qwen2.5:7b".equals(OllamaEnv.recommend(16 * GB).tag()));
        t("恰好 32GB 推荐 14b", "qwen2.5:14b".equals(OllamaEnv.recommend(32 * GB).tag()));
        t("64GB 不超过最高档 14b", "qwen2.5:14b".equals(OllamaEnv.recommend(64 * GB).tag()));

        System.out.println("== 档位表与视觉候选 ==");
        List<OllamaEnv.ModelOption> opts = OllamaEnv.options();
        t("档位共 5 档", opts.size() == 5);
        boolean asc = true;
        for (int i = 1; i < opts.size(); i++) asc &= opts.get(i).minRamGb() >= opts.get(i - 1).minRamGb();
        t("档位按内存阈值升序", asc);
        t("3b 档有视觉候选 qwen2.5vl:3b", "qwen2.5vl:3b".equals(
                opts.stream().filter(o -> "qwen2.5:3b".equals(o.tag())).findFirst().orElseThrow().visionTag()));
        t("1.5b 档无视觉候选（null）", opts.stream()
                .filter(o -> "qwen2.5:1.5b".equals(o.tag())).findFirst().orElseThrow().visionTag() == null);

        System.out.println("== 安装路径探测（注入环境变量，不依赖真实机器状态）==");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("LOCALAPPDATA", "C:\\Users\\tester\\AppData\\Local");
        env.put("ProgramFiles", "C:\\Program Files");
        env.put("ProgramFiles(x86)", "C:\\Program Files (x86)");
        env.put("PATH", "C:\\Windows\\System32;D:\\tools\\ollama;");
        List<Path> cands = OllamaEnv.candidateExePaths(env);
        t("候选含官方用户态安装路径", cands.stream().anyMatch(p ->
                p.toString().equals("C:\\Users\\tester\\AppData\\Local\\Programs\\Ollama\\ollama.exe")));
        t("候选含 Program Files 路径", cands.stream().anyMatch(p ->
                p.toString().equals("C:\\Program Files\\Ollama\\ollama.exe")));
        t("候选解析 PATH 中的 ollama 目录", cands.stream().anyMatch(p ->
                p.toString().equals("D:\\tools\\ollama\\ollama.exe")));
        t("空环境表不产生候选", OllamaEnv.candidateExePaths(Map.of()).isEmpty());

        Path tmpDir = Files.createTempDirectory("ollama-env-test");
        Path fakeExe = tmpDir.resolve("ollama.exe");
        Files.writeString(fakeExe, "stub");
        t("firstExisting 命中已存在文件", fakeExe.equals(OllamaEnv.firstExisting(List.of(
                tmpDir.resolve("nope.exe"), fakeExe))));
        t("firstExisting 全不存在返回 null", null == OllamaEnv.firstExisting(List.of(
                tmpDir.resolve("a.exe"), tmpDir.resolve("b.exe"))));
        t("desktopAppOf 取同目录 ollama app.exe",
                fakeExe.resolveSibling("ollama app.exe").equals(OllamaEnv.desktopAppOf(fakeExe)));
        Files.deleteIfExists(fakeExe);
        Files.deleteIfExists(tmpDir);

        System.out.println("== 模型标签匹配（兼容 @sha256 形式）==");
        Set<String> tags = Set.of("qwen2.5:7b", "nomic-embed-text:latest@sha256:abc");
        t("精确标签命中", OllamaEnv.hasTag(tags, "qwen2.5:7b"));
        t("带摘要标签按基础名命中", OllamaEnv.hasTag(tags, "nomic-embed-text:latest"));
        t("未安装标签不命中", !OllamaEnv.hasTag(tags, "qwen2.5:3b"));
        t("null 入参安全返回 false", !OllamaEnv.hasTag(null, null));

        System.out.println("== ollama pull NDJSON 进度解析 ==");
        OllamaEnv.PullState p1 = OllamaEnv.parsePullLine(
                "{\"status\":\"pulling 6a5846e40\",\"total\":1000,\"completed\":250}");
        t("pulling 行千分比为 250", p1.permille() == 250 && !p1.done());
        OllamaEnv.PullState p2 = OllamaEnv.parsePullLine("{\"status\":\"verifying sha256 digest\"}");
        t("无 total 的中间态为不确定(-1)", p2.permille() == -1 && !p2.done());
        OllamaEnv.PullState p3 = OllamaEnv.parsePullLine("{\"status\":\"success\"}");
        t("success 行为完成态", p3.done() && p3.permille() == 1000);
        t("垃圾行不抛异常且非完成", !OllamaEnv.parsePullLine("not-json").done()
                && OllamaEnv.parsePullLine("").permille() == -1);
        t("进度超界被夹到 0..1000", OllamaEnv.parsePullLine(
                "{\"status\":\"x\",\"total\":10,\"completed\":99}").permille() == 1000);

        System.out.println("== 安装器签名判定 ==");
        t("Valid + Ollama 主体通过", OllamaEnv.isTrustedOllamaSignature(
                "Valid|CN=Ollama, Inc., O=Ollama, Inc., S=California, C=US"));
        t("Valid 但非 Ollama 主体拒绝", !OllamaEnv.isTrustedOllamaSignature(
                "Valid|CN=Evil Corp, O=Evil"));
        t("UnknownError 拒绝", !OllamaEnv.isTrustedOllamaSignature("UnknownError|"));
        t("null 输出拒绝", !OllamaEnv.isTrustedOllamaSignature(null));
        List<String> sigCmd = OllamaEnv.signatureCheckCommand(tmpDir.resolve("OllamaSetup.exe"));
        t("签名校验命令以 powershell.exe 起", "powershell.exe".equals(sigCmd.get(0)));
        t("签名校验命令含 Get-AuthenticodeSignature",
                sigCmd.stream().anyMatch(s -> s.contains("Get-AuthenticodeSignature")));

        System.out.println("== 阶段判定（真实环境不抛异常）与体积格式 ==");
        // detect 依赖真实机器的 Ollama 安装/服务状态，不做具体阶段硬断言
        OllamaSetup.Stage ignored = OllamaSetup.detect("http://127.0.0.1:11434");
        t("detect 真实环境返回非空阶段: " + ignored, ignored != null);
        t("humanSize GB 格式", "5.0 GB".equals(OllamaEnv.humanSize(5L * GB)));
        t("humanSize MB 格式", OllamaEnv.humanSize(512L * 1024 * 1024).contains("MB"));
        t("humanSize 负值不报错", OllamaEnv.humanSize(-1).contains("KB"));

        System.out.println();
        System.out.println("结果：" + pass + " 通过 / " + fail + " 失败");
        System.exit(fail > 0 ? 1 : 0);
    }
}
