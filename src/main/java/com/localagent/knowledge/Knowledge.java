package com.localagent.knowledge;

import com.localagent.safety.DocSafety;
import com.localagent.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/**
 * 本地知识库 BM25 检索（移植 knowledge.js）。
 *
 * 索引以 JSON 文件持久化（knowledge-index.json）；切块按 Markdown 标题/定长。
 * 安全（L-10）：命中危险指令特征的文件不纳入索引。
 */
public class Knowledge {
    private static final Set<String> TEXT_EXTS = Set.of(".md", ".txt", ".json", ".csv", ".py", ".js", ".java", ".log", ".ini", ".yaml", ".yml", ".xml", ".html", ".css");
    private static final Set<String> SKIP_DIRS = Set.of("node_modules", ".git", ".svn", ".obsidian", ".trash", ".idea", ".vscode");
    private static final Pattern TOKEN_RE = Pattern.compile("[a-z0-9_\\-\\u4e00-\\u9fa5]+");

    public record Chunk(String source, String text, double score) {}
    public record Stats(int files, int chunks, long ms, String error) {}

    private Path dir;
    private Path cacheFile;
    private List<Chunk> chunks = new ArrayList<>();
    private boolean built = false;
    private Stats stats = new Stats(0, 0, 0, "");

    public void init(String knowledgeDir) {
        dir = knowledgeDir == null || knowledgeDir.isBlank() ? Paths.get("D:/知识库") : Paths.get(knowledgeDir);
        cacheFile = dir.resolve("knowledge-index.json");
        if (tryLoadCache()) return;
        built = true;
    }

    public Path dir() { return dir; }

    /** 同步建索引。 */
    public synchronized Stats buildIndex() {
        long t0 = System.currentTimeMillis();
        chunks = new ArrayList<>();
        int files = 0, blocked = 0;
        if (Files.isDirectory(dir)) {
            try (var walk = Files.walk(dir)) {
                var it = walk.filter(Files::isRegularFile).iterator();
                while (it.hasNext()) {
                    Path f = it.next();
                    if (!TEXT_EXTS.contains(ext(f))) continue;
                    if (isSkipped(f)) continue;
                    try {
                        long size = Files.size(f);
                        if (size > 2 * 1024 * 1024) continue;
                        String text = Files.readString(f, StandardCharsets.UTF_8);
                        if (text.isBlank()) continue;
                        if (DocSafety.hasDanger(text)) { blocked++; continue; }
                        chunks.addAll(chunkText(text, f.toString()));
                        files++;
                    } catch (Exception ignored) {}
                }
            } catch (IOException ignored) {}
        }
        String err = blocked > 0 ? "已拦截 " + blocked + " 个含危险内容的文件，未纳入索引" : "";
        stats = new Stats(files, chunks.size(), System.currentTimeMillis() - t0, err);
        built = true;
        tryPersist();
        return stats;
    }

    public Stats ensureIndex() {
        if (!built) return buildIndex();
        return stats;
    }

    /** BM25 检索。 */
    public List<Chunk> search(String query, int k) {
        ensureIndex();
        if (chunks.isEmpty()) return List.of();
        Map<String, Double> q = bm25Vector(query);
        double avg = chunks.stream().mapToInt(c -> tokenize(c.text()).size()).average().orElse(1);
        int n = chunks.size();
        Map<String, Integer> df = new HashMap<>();
        for (Chunk c : chunks) {
            for (String t : new HashSet<>(tokenize(c.text()))) df.merge(t, 1, Integer::sum);
        }
        List<Chunk> scored = new ArrayList<>();
        for (Chunk c : chunks) {
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
    private static List<String> tokenize(String t) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN_RE.matcher(t == null ? "" : t.toLowerCase(Locale.ROOT));
        while (m.find()) out.add(m.group());
        return out;
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

    // ---- 文件遍历/缓存 ----
    private static String ext(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i);
    }

    private boolean isSkipped(Path f) {
        for (Path seg : f) if (SKIP_DIRS.contains(seg.toString())) return true;
        return false;
    }

    private void tryPersist() {
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("dir", dir.toString());
            data.put("chunks", chunks.stream().map(c -> Map.of("source", c.source(), "text", c.text())).toList());
            data.put("files", stats.files()); data.put("chunksCount", stats.chunks());
            data.put("ms", stats.ms()); data.put("error", stats.error());
            Files.createDirectories(dir);
            Files.writeString(cacheFile, Json.stringify(data), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    private boolean tryLoadCache() {
        if (!Files.exists(cacheFile)) return false;
        try {
            var root = Json.mapper().readTree(Files.readString(cacheFile, StandardCharsets.UTF_8));
            if (!dir.toString().equals(root.path("dir").asText(""))) return false;
            chunks.clear();
            for (var n : root.path("chunks")) chunks.add(new Chunk(n.path("source").asText(), n.path("text").asText(), 0));
            stats = new Stats(root.path("files").asInt(0), chunks.size(), root.path("ms").asLong(0), root.path("error").asText(""));
            built = true;
            return true;
        } catch (Exception e) { return false; }
    }
}
