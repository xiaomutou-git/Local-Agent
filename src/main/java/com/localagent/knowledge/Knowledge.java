package com.localagent.knowledge;

import com.localagent.safety.DocSafety;
import com.localagent.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * 本地知识库 BM25 检索（移植 knowledge.js）。
 *
 * 索引持久化（2026-09 P0 修复）：
 * - 索引文件不再写进被索引目录（旧实现把 knowledge-index.json 放在目录内，
 *   且 .json 本身是可索引类型，重建时会把索引文件自己吃进去），改为存到
 *   %APPDATA%/本机助手/data/knowledge/ 下，文件名按目录绝对路径哈希隔离；
 * - 旧缓存只比对目录名，文件增删改后索引永久陈旧。现持久化每个已处理文件的
 *   路径+大小+修改时间清单，启动时仅做元数据级（stat）比对，任一变化即重建；
 * - 建索引在单线程后台守护执行器进行，首条检索不再阻塞 agent-loop / 界面；
 * - 设置面板可手动「重建索引」并查看文件数/片段数/拦截数。
 *
 * 安全（L-10）：命中危险指令特征的文件不纳入索引。
 */
public class Knowledge {
    private static final Set<String> TEXT_EXTS = Set.of(".md", ".txt", ".json", ".csv", ".py", ".js", ".java", ".log", ".ini", ".yaml", ".yml", ".xml", ".html", ".css");
    private static final Set<String> SKIP_DIRS = Set.of("node_modules", ".git", ".svn", ".obsidian", ".trash", ".idea", ".vscode");
    /** 旧版本写在知识目录内的索引文件名：发现即跳过，避免把陈旧索引当资料入库。 */
    private static final String LEGACY_CACHE_NAME = "knowledge-index.json";
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024;
    private static final Pattern TOKEN_RE = Pattern.compile("[a-z0-9_\\-\\u4e00-\\u9fa5]+");

    public record Chunk(String source, String text, double score) {}
    public record Stats(int files, int chunks, long ms, String error) {}

    /** 清单条目：path=绝对路径，size=字节，mtime=修改时间毫秒，blocked=是否因危险内容被拦截。 */
    private record FileMeta(String path, long size, long mtime, boolean blocked) {}

    private Path dir;
    private Path cacheFile;
    /** 当前生效片段（构建完成后整体替换为不可变列表，volatile 保证检索线程无锁可见）。 */
    private volatile List<Chunk> chunks = List.of();
    private volatile boolean built = false;
    private volatile Stats stats = new Stats(0, 0, 0, "");
    /** 索引构建专用单线程守护执行器：与 agent-loop 隔离，构建期间界面不冻结。 */
    private final ExecutorService indexExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "knowledge-index"); t.setDaemon(true); return t;
    });
    /** 最近一次/进行中的构建任务（用于合并并发请求与查询构建状态）。 */
    private volatile CompletableFuture<Stats> task;

    /**
     * 配置知识目录并立即在后台预热索引（非阻塞）：缓存清单仍有效则直接加载，
     * 否则后台重建。重复调用（如设置中改目录）会重置状态并以新目录重新预热。
     * @param knowledgeDir 知识目录绝对路径；空白时回退 D:/知识库
     */
    public synchronized void init(String knowledgeDir) {
        dir = knowledgeDir == null || knowledgeDir.isBlank() ? Paths.get("D:/知识库") : Paths.get(knowledgeDir);
        cacheFile = appDataCacheFile(dir);
        built = false;
        chunks = List.of();
        stats = new Stats(0, 0, 0, "");
        startWarmup();
    }

    public Path dir() { return dir; }

    /** 索引是否已可用于检索（缓存加载或构建完成）。 */
    public boolean isReady() { return built; }

    /** 后台构建任务是否进行中。 */
    public boolean isIndexing() {
        CompletableFuture<Stats> t = task;
        return t != null && !t.isDone();
    }

    /** 最近一次构建统计（未构建过返回零值统计）。 */
    public Stats stats() { return stats; }

    /**
     * 启动一次后台预热：已在跑则复用同一任务。
     * @return 构建任务 Future
     */
    private synchronized CompletableFuture<Stats> startWarmup() {
        if (task != null && !task.isDone()) return task;
        task = CompletableFuture.supplyAsync(this::warmup, indexExec);
        return task;
    }

    /**
     * 手动强制重建（设置面板「重建索引」按钮）：无条件重新扫描并持久化。
     * @return 构建任务 Future；调用方可在完成后刷新统计展示
     */
    public synchronized CompletableFuture<Stats> rebuildAsync() {
        built = false;
        task = CompletableFuture.supplyAsync(this::buildIndex, indexExec);
        return task;
    }

    /**
     * 预热执行体（工作线程）：先尝试加载且校验缓存清单，失效才全量重建。
     * @return 生效后的构建统计
     */
    private synchronized Stats warmup() {
        if (built) return stats;
        if (cacheFile != null && tryLoadCache()) return stats;
        return buildIndex();
    }

    /**
     * 全量同步建索引（公开为 synchronized 供任务与潜在测试调用）。
     * 执行逻辑：元数据扫描候选文件 -> 逐个读取（超限/不可读跳过）->
     * 危险内容拦截并计数 -> 切块收集 -> 持久化片段与文件清单。
     * @return 本次构建统计（文件数/片段数/耗时/拦截说明）
     */
    public synchronized Stats buildIndex() {
        long t0 = System.currentTimeMillis();
        List<Chunk> built2 = new ArrayList<>();
        List<FileMeta> manifest = new ArrayList<>();
        int files = 0, blocked = 0, unreadable = 0;
        if (Files.isDirectory(dir)) {
            for (FileMeta meta : scanCandidates()) {
                try {
                    String text = Files.readString(Paths.get(meta.path()), StandardCharsets.UTF_8);
                    if (text.isBlank()) { manifest.add(meta); continue; }
                    if (DocSafety.hasDanger(text)) {
                        blocked++;
                        manifest.add(new FileMeta(meta.path(), meta.size(), meta.mtime(), true));
                        continue;
                    }
                    built2.addAll(chunkText(text, meta.path()));
                    manifest.add(meta);
                    files++;
                } catch (Exception readErr) {
                    unreadable++;
                }
            }
        }
        List<String> notes = new ArrayList<>();
        if (blocked > 0) notes.add("已拦截 " + blocked + " 个含危险内容的文件，未纳入索引");
        if (unreadable > 0) notes.add(unreadable + " 个文件读取失败已跳过");
        Stats s = new Stats(files, built2.size(), System.currentTimeMillis() - t0,
                String.join("；", notes));
        chunks = List.copyOf(built2);
        stats = s;
        // 先落盘再置就绪标志：确保 isReady()=true 时缓存已可被后续实例加载
        tryPersist(manifest);
        built = true;
        return s;
    }

    /**
     * BM25 检索（非阻塞）：索引未就绪时立即返回空结果，由调用方提示稍后再试，
     * 绝不在 agent-loop 线程上同步等待全量建索引。
     * @param query 查询串
     * @param k     最多返回片段数
     * @return 按相关度降序的片段；索引未就绪或无命中时返回空列表
     */
    public List<Chunk> search(String query, int k) {
        if (!built) {
            startWarmup();
            return List.of();
        }
        List<Chunk> snapshot = chunks;
        if (snapshot.isEmpty()) return List.of();
        Map<String, Double> q = bm25Vector(query);
        double avg = snapshot.stream().mapToInt(c -> tokenize(c.text()).size()).average().orElse(1);
        int n = snapshot.size();
        Map<String, Integer> df = new HashMap<>();
        for (Chunk c : snapshot) {
            for (String t : new HashSet<>(tokenize(c.text()))) df.merge(t, 1, Integer::sum);
        }
        List<Chunk> scored = new ArrayList<>();
        for (Chunk c : snapshot) {
            List<String> toks = tokenize(c.text());
            Map<String, Integer> tf = new HashMap<>();
            for (String t : toks) tf.merge(t, 1, Integer::sum);
            double s = 0;
            for (var e : q.entrySet()) {
                double f = tf.getOrDefault(e.getKey(), 0);
                if (f == 0) continue;
                s += idf(n, df, e.getKey()) * (2.2 * f) / (f + 1.2 * (0.25 + 0.75 * toks.size() / Math.max(1, avg)));
            }
            if (s > 0) scored.add(new Chunk(c.source(), c.text(), s));
        }
        scored.sort(Comparator.comparingDouble(Chunk::score).reversed());
        return scored.subList(0, Math.min(k, scored.size()));
    }

    // ---- 切块 ----
    private List<Chunk> chunkText(String text, String source) {
        List<Chunk> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String header = "";
        for (String line : text.lines().toList()) {
            if (line.matches("#{1,6}\\s+.+")) {
                flushChunk(buf, header, source, out);
                buf.setLength(0); header = line.trim() + "\n";
            } else {
                buf.append(line).append('\n');
                if (buf.length() > 1600) { flushChunk(buf, header, source, out); buf.setLength(0); }
            }
        }
        flushChunk(buf, header, source, out);
        return out;
    }

    private static void flushChunk(StringBuilder buf, String header, String source, List<Chunk> out) {
        String body = buf.toString().trim();
        if (!body.isEmpty()) out.add(new Chunk(source, (header + body).length() >= 20 ? header + '\n' + body : body, 0));
    }

    // ---- BM25 工具 ----
    /**
     * 分词：拉丁字母/数字按下划线连字符组成整词；中文没有空格，整句会被基础
     * 正则切成一个超长 token 导致任何子词都无法命中，故对连续中文段额外产出
     * 重叠二元组（单字段产出单字）。查询与文档走同一套规则。
     * @param t 原始文本
     * @return 小写化后的词项列表（词 + 中文 bigram，允许重复，由 TF 统计计数）
     */
    private static List<String> tokenize(String t) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN_RE.matcher(t == null ? "" : t.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String raw = m.group();
            StringBuilder latin = new StringBuilder();
            StringBuilder cjk = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                char c = raw.charAt(i);
                if (c >= 0x4E00 && c <= 0x9FA5) {
                    flushLatin(latin, out);
                    cjk.append(c);
                } else {
                    flushCjk(cjk, out);
                    latin.append(c);
                }
            }
            flushLatin(latin, out);
            flushCjk(cjk, out);
        }
        return out;
    }

    /**
     * 冲刷累积的拉丁/数字段：非空时作为一个整词输出。
     * @param latin 累积缓冲（输出后清空）
     * @param out   词项收集列表
     */
    private static void flushLatin(StringBuilder latin, List<String> out) {
        if (latin.length() > 0) {
            out.add(latin.toString());
            latin.setLength(0);
        }
    }

    /**
     * 冲刷累积的中文段：输出重叠二元组（长度 1 时输出单字）。
     * @param cjk 累积缓冲（输出后清空）
     * @param out 词项收集列表
     */
    private static void flushCjk(StringBuilder cjk, List<String> out) {
        int len = cjk.length();
        if (len == 1) {
            out.add(cjk.toString());
        } else {
            for (int i = 0; i + 1 < len; i++) out.add(cjk.substring(i, i + 2));
        }
        cjk.setLength(0);
    }

    private static Map<String, Double> bm25Vector(String q) {
        Map<String, Double> m = new HashMap<>();
        for (String t : tokenize(q)) m.merge(t, 1.0, Double::sum);
        return m;
    }

    /** BM25 逆文档频率。 */
    private static double idf(int n, Map<String, Integer> df, String w) {
        double d = n - df.getOrDefault(w, 0) + 0.5;
        return Math.log((d + 0.5) / (df.getOrDefault(w, 0) + 0.5) + 1);
    }

    // ---- 文件扫描/缓存 ----
    /**
     * 元数据扫描候选文件（不读内容）：常规文件 + 文本扩展名 + 跳过目录
     * + 排除旧版目录内索引文件 + 单文件不超过 2MB。
     * @return 候选文件元数据（blocked 标志此处恒 false，读取阶段修正）
     */
    private List<FileMeta> scanCandidates() {
        List<FileMeta> out = new ArrayList<>();
        try (var walk = Files.walk(dir)) {
            var it = walk.filter(Files::isRegularFile).iterator();
            while (it.hasNext()) {
                Path f = it.next();
                if (!TEXT_EXTS.contains(ext(f))) continue;
                if (f.getFileName().toString().equalsIgnoreCase(LEGACY_CACHE_NAME)) continue;
                if (isSkipped(f)) continue;
                long size;
                try { size = Files.size(f); } catch (IOException e) { continue; }
                if (size > MAX_FILE_BYTES) continue;
                long mtime;
                try { mtime = Files.getLastModifiedTime(f).toMillis(); } catch (IOException e) { continue; }
                out.add(new FileMeta(f.toString(), size, mtime, false));
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    private static String ext(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i);
    }

    private boolean isSkipped(Path f) {
        for (Path seg : f) if (SKIP_DIRS.contains(seg.toString())) return true;
        return false;
    }

    /**
     * 持久化片段与文件清单到 AppData 下的索引文件（不再写入被索引目录）。
     * @param manifest 本次实际处理的文件清单（含被拦截条目，供下次元数据比对）
     */
    private void tryPersist(List<FileMeta> manifest) {
        if (cacheFile == null) return;
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("version", 2);
            data.put("dir", dir.toString());
            data.put("chunks", chunks.stream().map(c -> Map.of("source", c.source(), "text", c.text())).toList());
            data.put("files", stats.files()); data.put("chunksCount", stats.chunks());
            data.put("ms", stats.ms()); data.put("error", stats.error());
            List<Map<String, Object>> mf = new ArrayList<>();
            for (FileMeta m : manifest) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("p", m.path()); e.put("s", m.size()); e.put("t", m.mtime());
                if (m.blocked()) e.put("b", true);
                mf.add(e);
            }
            data.put("manifest", mf);
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, Json.stringify(data), StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            // 缓存写失败不影响内存索引可用性，下次启动退化为重建
        }
    }

    /**
     * 加载并校验缓存：目录一致且文件清单与当前磁盘元数据完全一致（路径集合、
     * 大小、修改时间）才采用，任一文件增/删/改即判定失效。
     * @return true=缓存有效并已装载；false=缓存缺失/损坏/陈旧，调用方应重建
     */
    private boolean tryLoadCache() {
        if (cacheFile == null || !Files.exists(cacheFile)) return false;
        try {
            var root = Json.mapper().readTree(Files.readString(cacheFile, StandardCharsets.UTF_8));
            if (!dir.toString().equals(root.path("dir").asText(""))) return false;
            var manifestNode = root.path("manifest");
            if (!manifestNode.isArray()) return false;
            // 清单映射（path -> {size,mtime}），与当前候选逐个核对
            Map<String, long[]> saved = new HashMap<>();
            for (var n : manifestNode) {
                saved.put(n.path("p").asText(),
                        new long[]{n.path("s").asLong(-1), n.path("t").asLong(-1)});
            }
            List<FileMeta> current = scanCandidates();
            if (saved.size() != current.size()) return false;
            for (FileMeta m : current) {
                long[] sm = saved.get(m.path());
                if (sm == null || sm[0] != m.size() || sm[1] != m.mtime()) return false;
            }
            List<Chunk> loaded = new ArrayList<>();
            for (var n : root.path("chunks"))
                loaded.add(new Chunk(n.path("source").asText(), n.path("text").asText(), 0));
            chunks = List.copyOf(loaded);
            stats = new Stats(root.path("files").asInt(0), chunks.size(),
                    root.path("ms").asLong(0), root.path("error").asText(""));
            built = true;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 计算知识目录对应的 AppData 索引文件路径（按绝对路径 SHA-256 前 16 位隔离）。
     * @param knowledgeDir 知识目录
     * @return %APPDATA%/本机助手/data/knowledge/index-&lt;hash&gt;.json
     */
    private static Path appDataCacheFile(Path knowledgeDir) {
        try {
            String norm = knowledgeDir.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(norm.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) hex.append(String.format("%02x", digest[i]));
            Path base = Path.of(System.getProperty("user.home"),
                    "AppData", "Roaming", "本机助手", "data", "knowledge");
            return base.resolve("index-" + hex + ".json");
        } catch (Exception e) {
            return null;
        }
    }
}
