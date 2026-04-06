package com.openclaw.enterprise.intelligence;

import com.openclaw.enterprise.common.JsonUtils;
import com.openclaw.enterprise.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 记忆存储 — 混合搜索 (TF-IDF 关键词 + Hash Vector，时间衰减，MMR 重排序)
 *
 * <p>存储布局：</p>
 * <ul>
 *   <li>常青记忆: {@code workspace/MEMORY.md} (手动维护，永不过期)</li>
 *   <li>每日记忆: {@code workspace/memory/daily/{YYYY-MM-DD}.jsonl} (每天一个文件)</li>
 * </ul>
 *
 * <p>混合搜索流水线 (5 阶段)：</p>
 * <ol>
 *   <li>加载全部记忆</li>
 *   <li>双路径搜索: 关键词 (TF-IDF 30%) + 向量 (Hash 70%)</li>
 *   <li>合并分数</li>
 *   <li>时间衰减: {@code score *= e^(-0.01 * age_in_days)}</li>
 *   <li>MMR 重排序 (lambda=0.7, Jaccard 相似度)</li>
 * </ol>
 *
 * <p>claw0 参考: s06_intelligence.py 第 268-557 行 MemoryStore</p>
 */
@Service
public class MemoryStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryStore.class);

    /** 停用词集合 (中英双语) */
    private static final Set<String> STOP_WORDS = Set.of(
        "the", "a", "an", "is", "are", "was", "were", "in", "on", "at",
        "to", "for", "of", "with", "by", "from", "and", "or", "not",
        "it", "this", "that", "de", "le", "zai", "shi", "wo", "you",
        "he", "jiu", "bu", "ren", "dou", "yi", "ge", "shang", "ye",
        "hen", "dao", "shuo", "yao", "qu", "ni", "hui", "zhe", "meiyou",
        "kan", "hao"
    );

    private static final int HASH_DIMS = 64;

    private final Path memoryDir;
    private final Path evergreenPath;

    /** 预加载的记忆缓存 */
    private List<MemoryEntry> allEntries = new CopyOnWriteArrayList<>();
    private volatile Map<String, Integer> cachedDf = new HashMap<>();

    public MemoryStore(AppProperties.WorkspaceProperties workspaceProps) {
        this.memoryDir = workspaceProps.path().resolve("memory/daily");
        this.evergreenPath = workspaceProps.path().resolve("MEMORY.md");
    }

    /**
     * 启动时预加载所有记忆到内存
     */
    @PostConstruct
    void preload() {
        this.allEntries = new CopyOnWriteArrayList<>(loadAllMemories());
        this.cachedDf = buildDocumentFrequency(allEntries);
        log.info("Preloaded {} memory entries", allEntries.size());
    }

    /**
     * 写入一条记忆
     */
    public void writeMemory(String content, String category) {
        try {
            Files.createDirectories(memoryDir);
            String today = Instant.now().toString().substring(0, 10);
            Path file = memoryDir.resolve(today + ".jsonl");

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("content", content);
            entry.put("category", category != null ? category : "general");
            entry.put("ts", Instant.now().toString());

            JsonUtils.appendJsonl(file, entry);

            // 增量更新内存缓存
            MemoryEntry memEntry = new MemoryEntry(content, category, Instant.now(), "daily", 0.0);
            allEntries.add(memEntry);
            Map<String, Integer> newDf = buildDocumentFrequency(allEntries);
            cachedDf = newDf;

            log.debug("Memory written: {} (category={})", content.substring(0, Math.min(50, content.length())), category);
        } catch (Exception e) {
            log.error("Failed to write memory", e);
        }
    }

    /**
     * 混合搜索 — TF-IDF 关键词 + Hash 向量 + 时间衰减 + MMR
     */
    public List<MemoryEntry> hybridSearch(String query, int topK) {
        if (allEntries.isEmpty() || query == null || query.isBlank()) {
            return List.of();
        }

        // 阶段 2: 双路径搜索
        List<ScoredEntry> keywordResults = keywordSearch(query, allEntries);
        List<ScoredEntry> vectorResults = vectorSearch(query, allEntries);

        // 合并分数: 关键词 30% + 向量 70%
        Map<String, Double> mergedScores = new HashMap<>();
        for (var se : keywordResults) {
            mergedScores.merge(entryKey(se.entry), se.score * 0.3, Double::sum);
        }
        for (var se : vectorResults) {
            mergedScores.merge(entryKey(se.entry), se.score * 0.7, Double::sum);
        }

        // 阶段 3: 构建合并列表
        List<ScoredEntry> merged = new ArrayList<>();
        for (var entry : allEntries) {
            Double score = mergedScores.get(entryKey(entry));
            if (score != null && score > 0) {
                merged.add(new ScoredEntry(entry, score));
            }
        }

        // 阶段 4: 时间衰减
        merged = applyTemporalDecay(merged);

        // 阶段 5: MMR 重排序
        return mmrRerank(merged, 0.7, topK);
    }

    // ==================== TF-IDF 关键词搜索 ====================

    private List<ScoredEntry> keywordSearch(String query, List<MemoryEntry> entries) {
        String[] queryTokens = tokenize(query);
        if (queryTokens.length == 0) return List.of();

        double[] queryVec = tfidf(queryTokens, cachedDf, entries.size());

        List<ScoredEntry> results = new ArrayList<>();
        for (MemoryEntry entry : entries) {
            String[] docTokens = tokenize(entry.content());
            if (docTokens.length == 0) continue;
            double[] docVec = tfidf(docTokens, cachedDf, entries.size());
            double sim = cosine(queryVec, docVec);
            if (sim > 0) {
                results.add(new ScoredEntry(entry, sim));
            }
        }

        results.sort((a, b) -> Double.compare(b.score, a.score));
        return results.stream().limit(20).toList();
    }

    /**
     * Build a TF-IDF vector using df as the authoritative vocabulary.
     *
     * <p>All vectors share the same dimension = df.size(). Terms present in
     * the token list but absent from df are ignored so that query and document
     * vectors are always comparable via cosine similarity.</p>
     */
    private double[] tfidf(String[] tokens, Map<String, Integer> df, int totalDocs) {
        // 词频
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) tf.merge(t, 1, Integer::sum);

        // Fixed vocabulary from df — terms not in df are ignored
        List<String> vocab = new ArrayList<>(df.keySet());
        double[] vec = new double[vocab.size()];

        for (int i = 0; i < vocab.size(); i++) {
            String term = vocab.get(i);
            double tfVal = tf.getOrDefault(term, 0) / (double) tokens.length;
            int docFreq = df.getOrDefault(term, 0);
            double idfVal = Math.log((totalDocs + 1.0) / (1 + docFreq));
            vec[i] = tfVal * idfVal;
        }
        return vec;
    }

    // ==================== Hash 向量搜索 ====================

    private List<ScoredEntry> vectorSearch(String query, List<MemoryEntry> entries) {
        double[] queryVec = hashVector(query, HASH_DIMS);

        List<ScoredEntry> results = new ArrayList<>();
        for (MemoryEntry entry : entries) {
            double[] docVec = hashVector(entry.content(), HASH_DIMS);
            double sim = cosine(queryVec, docVec);
            if (sim > 0) {
                results.add(new ScoredEntry(entry, sim));
            }
        }

        results.sort((a, b) -> Double.compare(b.score, a.score));
        return results.stream().limit(20).toList();
    }

    private double[] hashVector(String text, int dims) {
        double[] vec = new double[dims];
        String[] tokens = tokenize(text);
        for (String token : tokens) {
            Random rng = new Random(token.hashCode());
            for (int i = 0; i < dims; i++) {
                vec[i] += rng.nextGaussian();
            }
        }
        // 归一化为单位向量
        double norm = 0;
        for (double v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < dims; i++) vec[i] /= norm;
        }
        return vec;
    }

    // ==================== 评分与重排序 ====================

    private double cosine(double[] a, double[] b) {
        int len = Math.min(a.length, b.length);
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < len; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom > 0 ? dot / denom : 0;
    }

    private List<ScoredEntry> applyTemporalDecay(List<ScoredEntry> entries) {
        Instant now = Instant.now();
        List<ScoredEntry> result = new ArrayList<>();
        for (var se : entries) {
            long ageDays = (now.getEpochSecond() - se.entry.timestamp().getEpochSecond()) / 86400;
            double decayed = se.score * Math.exp(-0.01 * ageDays);
            result.add(new ScoredEntry(se.entry, decayed));
        }
        return result;
    }

    private List<MemoryEntry> mmrRerank(List<ScoredEntry> candidates, double lambda, int topK) {
        if (candidates.isEmpty()) return List.of();

        List<ScoredEntry> sorted = new ArrayList<>(candidates);
        sorted.sort((a, b) -> Double.compare(b.score, a.score));

        List<MemoryEntry> selected = new ArrayList<>();
        selected.add(sorted.getFirst().entry);

        while (selected.size() < topK && selected.size() < sorted.size()) {
            ScoredEntry best = null;
            double bestScore = Double.NEGATIVE_INFINITY;

            for (var cand : sorted) {
                if (selected.contains(cand.entry)) continue;

                double maxSim = 0;
                for (MemoryEntry sel : selected) {
                    maxSim = Math.max(maxSim, jaccard(cand.entry.content(), sel.content()));
                }

                double mmrScore = lambda * cand.score - (1 - lambda) * maxSim;
                if (mmrScore > bestScore) {
                    bestScore = mmrScore;
                    best = cand;
                }
            }

            if (best == null) break;
            selected.add(best.entry);
        }

        return selected;
    }

    private double jaccard(String a, String b) {
        Set<String> sa = new HashSet<>(Arrays.asList(tokenize(a)));
        Set<String> sb = new HashSet<>(Arrays.asList(tokenize(b)));
        if (sa.isEmpty() && sb.isEmpty()) return 1.0;
        Set<String> intersection = new HashSet<>(sa);
        intersection.retainAll(sb);
        Set<String> union = new HashSet<>(sa);
        union.addAll(sb);
        return union.isEmpty() ? 0 : (double) intersection.size() / union.size();
    }

    // ==================== 分词 ====================

    String[] tokenize(String text) {
        if (text == null || text.isBlank()) return new String[0];

        List<String> tokens = new ArrayList<>();
        StringBuilder buf = new StringBuilder();

        for (char c : text.toLowerCase().toCharArray()) {
            if (isCJK(c)) {
                flushAsciiBuffer(buf, tokens);
                String t = String.valueOf(c);
                if (!STOP_WORDS.contains(t)) tokens.add(t);
            } else if (Character.isLetterOrDigit(c)) {
                buf.append(c);
            } else {
                flushAsciiBuffer(buf, tokens);
            }
        }
        flushAsciiBuffer(buf, tokens);
        return tokens.toArray(new String[0]);
    }

    private void flushAsciiBuffer(StringBuilder buf, List<String> tokens) {
        if (buf.length() > 1) {
            String t = buf.toString();
            if (!STOP_WORDS.contains(t)) tokens.add(t);
        }
        buf.setLength(0);
    }

    private boolean isCJK(char c) {
        return (c >= '\u4E00' && c <= '\u9FFF')
            || (c >= '\u3400' && c <= '\u4DBF')
            || (c >= '\uF900' && c <= '\uFAFF')
            || (c >= '\u3040' && c <= '\u309F')
            || (c >= '\u30A0' && c <= '\u30FF');
    }

    // ==================== 加载与索引 ====================

    private List<MemoryEntry> loadAllMemories() {
        List<MemoryEntry> entries = new ArrayList<>();

        // 加载常青记忆 (MEMORY.md)
        if (Files.exists(evergreenPath)) {
            try {
                String content = Files.readString(evergreenPath);
                for (String para : content.split("\n\n")) {
                    para = para.trim();
                    if (!para.isBlank() && !para.startsWith("#")) {
                        entries.add(new MemoryEntry(para, "evergreen",
                            Instant.EPOCH, "evergreen", 0.0));
                    }
                }
            } catch (IOException e) {
                log.warn("Failed to load MEMORY.md", e);
            }
        }

        // 加载每日记忆 (JSONL)
        if (Files.exists(memoryDir)) {
            try (var stream = Files.list(memoryDir)) {
                stream.filter(p -> p.toString().endsWith(".jsonl"))
                    .sorted()
                    .forEach(file -> {
                        var lines = JsonUtils.readJsonl(file, Map.class);
                        for (var raw : lines) {
                            String content = (String) raw.get("content");
                            String category = (String) raw.get("category");
                            String ts = (String) raw.get("ts");
                            if (content != null) {
                                entries.add(new MemoryEntry(content,
                                    category != null ? category : "general",
                                    ts != null ? Instant.parse(ts) : Instant.now(),
                                    "daily", 0.0));
                            }
                        }
                    });
            } catch (IOException e) {
                log.warn("Failed to load daily memories", e);
            }
        }

        return entries;
    }

    private Map<String, Integer> buildDocumentFrequency(List<MemoryEntry> entries) {
        Map<String, Integer> df = new HashMap<>();
        for (MemoryEntry entry : entries) {
            Set<String> seen = new HashSet<>();
            for (String token : tokenize(entry.content())) {
                if (seen.add(token)) {
                    df.merge(token, 1, Integer::sum);
                }
            }
        }
        return df;
    }

    private String entryKey(MemoryEntry entry) {
        return entry.content() + "|" + entry.timestamp().toString();
    }

    /** 内部评分记录 */
    record ScoredEntry(MemoryEntry entry, double score) {}
}
