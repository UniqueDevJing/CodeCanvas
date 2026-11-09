package com.codecanvas.llm.cache;

import com.codecanvas.llm.LlmPurpose;
import com.codecanvas.llm.LlmRequest;
import com.codecanvas.llm.LlmResponse;
import com.codecanvas.llm.ModelTier;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 语义缓存 Advisor：在模型网关层复用「同一需求的确定性理解结果」。
 *
 * <p><b>命中策略（两级）</b>：先按归一化后的 prompt 做精确哈希命中；未命中时，
 * 在同 purpose / 同模型 / 同系统提示词 / 同温度桶的候选里，按 TF 向量的余弦相似度
 * 找最相近的一条，超过阈值才复用。命中后完全不产生模型调用，也不计入 TokenMeter。
 *
 * <p><b>⚠️ 这是「词法-语义近似」缓存，不是向量嵌入缓存</b>：相似度由中文 2-gram
 * + 英文词元的 TF 余弦算出，不调用 embedding 模型。理由很直接——为了省一次调用
 * 而先发一次 embedding 请求，在短 prompt 场景里往往不划算；这里换来的是**零额外成本、
 * 零外部依赖、可单测、可解释**。若将来接入真 embedding，只需替换 {@link #vectorOf}。
 *
 * <p><b>刻意不缓存什么（比缓存本身更重要）</b>：
 * <ul>
 *   <li>{@code CODE} 生成：用户点「重新生成」时想拿新版本，缓存会把它变成复读机；</li>
 *   <li>{@code MODIFY} / {@code FIX}：这两类提示词带着当前代码和具体报错，
 *       复用旧输出会把失败结果喂回自检修复闭环，造成死循环；</li>
 *   <li>高温采样（&gt; maxTemperature）：探索性生成本就该有多样性；</li>
 *   <li>空输出 / 失败输出：只存成功且非空的响应。</li>
 * </ul>
 * 因此默认只缓存 {@code PRD}（需求澄清）阶段。收益真实但有限，不编造「省 73%」这类数字。
 */
@Component
public class SemanticCache {

    public enum MatchType { EXACT, SEMANTIC }

    /** 一次命中。similarity 精确命中恒为 1.0，近似命中为实际余弦值。 */
    public record Hit(LlmResponse response, MatchType matchType, double similarity,
                      String matchedPrompt, String key, long ageMs, int priorHits) {
    }

    private static final Pattern LATIN = Pattern.compile("[a-z0-9]+");
    private static final Pattern CJK = Pattern.compile("[\u4e00-\u9fff]+");

    private final boolean enabled;
    private final double threshold;
    private final long ttlMillis;
    private final int maxEntries;
    private final Set<LlmPurpose> purposes;
    private final double maxTemperature;

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong exactHits = new AtomicLong();
    private final AtomicLong semanticHits = new AtomicLong();
    private final AtomicLong savedInputTokens = new AtomicLong();
    private final AtomicLong savedOutputTokens = new AtomicLong();
    private final DoubleAdder savedCost = new DoubleAdder();

    public SemanticCache(
            @Value("${codecanvas.semantic-cache.enabled:true}") boolean enabled,
            @Value("${codecanvas.semantic-cache.similarity-threshold:0.78}") double threshold,
            @Value("${codecanvas.semantic-cache.ttl-seconds:3600}") long ttlSeconds,
            @Value("${codecanvas.semantic-cache.max-entries:256}") int maxEntries,
            @Value("${codecanvas.semantic-cache.purposes:PRD}") String purposes,
            @Value("${codecanvas.semantic-cache.max-temperature:0.35}") double maxTemperature) {
        this.enabled = enabled;
        this.threshold = Math.max(0, Math.min(threshold, 1));
        this.ttlMillis = Math.max(0, ttlSeconds) * 1000L;
        this.maxEntries = Math.max(1, maxEntries);
        this.purposes = parsePurposes(purposes);
        this.maxTemperature = maxTemperature;
    }

    private static Set<LlmPurpose> parsePurposes(String raw) {
        EnumSet<LlmPurpose> out = EnumSet.noneOf(LlmPurpose.class);
        if (raw != null) {
            for (String part : raw.split(",")) {
                try {
                    out.add(LlmPurpose.valueOf(part.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                    // 配置里写错的名字直接忽略，不让应用起不来
                }
            }
        }
        return out.isEmpty() ? EnumSet.of(LlmPurpose.PRD) : out;
    }

    public boolean enabled() {
        return enabled;
    }

    /** 是否允许参与缓存（网关在调用前判定，命中/未命中统计由网关显式上报） */
    public boolean eligible(LlmRequest request) {
        return enabled
                && request != null
                && request.purpose() != null
                && purposes.contains(request.purpose())
                && request.temperature() <= maxTemperature
                && request.userPrompt() != null
                && !request.userPrompt().isBlank();
    }

    public Optional<Hit> lookup(LlmRequest request) {
        if (!eligible(request)) {
            return Optional.empty();
        }
        long now = System.currentTimeMillis();
        String scope = scopeOf(request);
        String key = keyOf(request, scope);

        Entry exact = entries.get(key);
        if (exact != null) {
            if (expired(exact, now)) {
                entries.remove(key);
            } else {
                exact.lastAccess = now;
                return Optional.of(new Hit(exact.response, MatchType.EXACT, 1.0,
                        exact.normalizedPrompt, key, now - exact.createdAt, exact.hitCount.get()));
            }
        }

        Map<String, Double> query = vectorOf(request.userPrompt());
        Entry best = null;
        double bestScore = 0;
        for (Entry candidate : entries.values()) {
            if (expired(candidate, now) || !scope.equals(candidate.scope)) {
                continue;
            }
            double score = cosine(query, candidate.vector);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }
        if (best != null && bestScore >= threshold) {
            best.lastAccess = now;
            return Optional.of(new Hit(best.response, MatchType.SEMANTIC, bestScore,
                    best.normalizedPrompt, best.key, now - best.createdAt, best.hitCount.get()));
        }
        return Optional.empty();
    }

    /** 只存成功且非空的响应；写满后按 LRU 淘汰一条。 */
    public void store(LlmRequest request, LlmResponse response) {
        if (!eligible(request) || response == null
                || response.text() == null || response.text().isBlank()) {
            return;
        }
        evictIfNeeded();
        String scope = scopeOf(request);
        String key = keyOf(request, scope);
        long now = System.currentTimeMillis();
        entries.put(key, new Entry(key, scope, normalize(request.userPrompt()),
                vectorOf(request.userPrompt()), response, now));
    }

    /** @param savedCost 按本次本会走到的档位单价估算出的「本该花费」，由 TokenMeter 同口径给出 */
    public void recordHit(Hit hit, ModelTier tier, double savedCost) {
        hits.incrementAndGet();
        if (hit.matchType() == MatchType.EXACT) {
            exactHits.incrementAndGet();
        } else {
            semanticHits.incrementAndGet();
        }
        savedInputTokens.addAndGet(hit.response().inputTokens());
        savedOutputTokens.addAndGet(hit.response().outputTokens());
        this.savedCost.add(Math.max(0, savedCost));
        Entry e = entries.get(hit.key());
        if (e != null) {
            e.hitCount.incrementAndGet();
        }
    }

    public void recordMiss() {
        misses.incrementAndGet();
    }

    public long hits() {
        return hits.get();
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }

    public Stats stats() {
        long h = hits.get();
        long m = misses.get();
        long total = h + m;
        return new Stats(enabled, h, m, exactHits.get(), semanticHits.get(), entries.size(),
                savedInputTokens.get(), savedOutputTokens.get(), savedCost.sum(),
                total == 0 ? 0 : h * 100.0 / total,
                threshold, ttlMillis / 1000, maxEntries,
                purposes.stream().map(Enum::name).sorted().toList(), maxTemperature);
    }

    /** 平台自述：把边界和假设一起返回，避免「省了多少」被脱离语境引用。 */
    public Map<String, Object> describe() {
        Stats s = stats();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", s.enabled());
        out.put("hits", s.hits());
        out.put("misses", s.misses());
        out.put("exactHits", s.exactHits());
        out.put("semanticHits", s.semanticHits());
        out.put("hitRatePercent", round2(s.hitRatePercent()));
        out.put("entries", s.entries());
        out.put("savedInputTokens", s.savedInputTokens());
        out.put("savedOutputTokens", s.savedOutputTokens());
        out.put("savedCost", round4(s.savedCost()));
        out.put("similarityThreshold", s.similarityThreshold());
        out.put("ttlSeconds", s.ttlSeconds());
        out.put("maxEntries", s.maxEntries());
        out.put("cacheablePurposes", s.cacheablePurposes());
        out.put("maxTemperature", s.maxTemperature());
        out.put("method", "归一化 prompt + 中文 2gram/英文词元 TF 余弦（词法-语义近似，非 embedding）");
        out.put("boundary", "默认只缓存 PRD 需求澄清：不缓存 CODE（否则「重新生成」会拿到旧版本）、"
                + "不缓存 MODIFY/FIX（会把失败输出喂回自检修复闭环）。"
                + "savedCost 按本会走到的档位单价估算，不是账单金额；命中不产生模型调用、不计入 TokenMeter。");
        return out;
    }

    public record Stats(boolean enabled, long hits, long misses, long exactHits, long semanticHits,
                        int entries, long savedInputTokens, long savedOutputTokens, double savedCost,
                        double hitRatePercent, double similarityThreshold, long ttlSeconds,
                        int maxEntries, List<String> cacheablePurposes, double maxTemperature) {
    }

    // ───────────────────────── 内部：键、相似度、淘汰 ─────────────────────────

    private String scopeOf(LlmRequest r) {
        return r.purpose().name() + "|" + r.model() + "|"
                + Math.round(r.temperature() * 100) + "|"
                + sha256(normalize(r.systemPrompt()));
    }

    private String keyOf(LlmRequest r, String scope) {
        return scope + "|" + sha256(normalize(r.userPrompt()));
    }

    private static String sha256(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", digest[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    /**
     * 归一化：小写 + 去掉全部标点和空白。
     *
     * <p>刻意「删掉」而不是「换成空格」：中文里夹的空格不应打断 2-gram 切分，
     * 英文词边界被合并（todo list → todolist）在本场景下反而让精确命中更容易。
     * 这是词法近似的已知简化，不是 embedding。
     */
    static String normalize(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    /** TF 向量并做 L2 归一化，两个向量点积即余弦相似度。 */
    static Map<String, Double> vectorOf(String text) {
        String n = normalize(text);
        Map<String, Double> tf = new HashMap<>();
        Matcher latin = LATIN.matcher(n);
        while (latin.find()) {
            add(tf, latin.group());
        }
        Matcher cjk = CJK.matcher(n);
        while (cjk.find()) {
            String seg = cjk.group();
            if (seg.length() == 1) {
                add(tf, seg);
            } else {
                for (int i = 0; i < seg.length() - 1; i++) {
                    add(tf, seg.substring(i, i + 2));
                }
            }
        }
        double norm = 0;
        for (double v : tf.values()) {
            norm += v * v;
        }
        norm = Math.sqrt(norm);
        if (norm > 0) {
            final double d = norm;
            tf.replaceAll((k, v) -> v / d);
        }
        return tf;
    }

    private static void add(Map<String, Double> tf, String token) {
        tf.merge(token, 1.0, Double::sum);
    }

    static double cosine(Map<String, Double> a, Map<String, Double> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        Map<String, Double> small = a.size() <= b.size() ? a : b;
        Map<String, Double> large = small == a ? b : a;
        double dot = 0;
        for (Map.Entry<String, Double> e : small.entrySet()) {
            Double v = large.get(e.getKey());
            if (v != null) {
                dot += e.getValue() * v;
            }
        }
        return Math.max(0, Math.min(dot, 1));
    }

    private boolean expired(Entry e, long now) {
        return now - e.createdAt > ttlMillis;
    }

    private void evictIfNeeded() {
        if (entries.size() < maxEntries) {
            return;
        }
        long now = System.currentTimeMillis();
        List<Entry> all = new ArrayList<>(entries.values());
        // 先清过期项；仍满则淘汰最久未访问的一条（容量默认 256，线性扫描足够）
        for (Entry e : all) {
            if (expired(e, now)) {
                entries.remove(e.key);
            }
        }
        if (entries.size() >= maxEntries) {
            entries.values().stream()
                    .min((x, y) -> Long.compare(x.lastAccess, y.lastAccess))
                    .ifPresent(e -> entries.remove(e.key));
        }
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }

    private static final class Entry {
        final String key;
        final String scope;
        final String normalizedPrompt;
        final Map<String, Double> vector;
        final LlmResponse response;
        final long createdAt;
        final AtomicInteger hitCount = new AtomicInteger();
        volatile long lastAccess;

        Entry(String key, String scope, String normalizedPrompt, Map<String, Double> vector,
              LlmResponse response, long createdAt) {
            this.key = key;
            this.scope = scope;
            this.normalizedPrompt = normalizedPrompt;
            this.vector = Map.copyOf(vector);
            this.response = response;
            this.createdAt = createdAt;
            this.lastAccess = createdAt;
        }
    }
}
