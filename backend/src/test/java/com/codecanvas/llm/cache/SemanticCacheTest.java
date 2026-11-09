package com.codecanvas.llm.cache;

import com.codecanvas.llm.LlmPurpose;
import com.codecanvas.llm.LlmRequest;
import com.codecanvas.llm.LlmResponse;
import com.codecanvas.llm.ModelTier;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticCacheTest {

    private static LlmRequest req(String user) {
        return LlmRequest.of(LlmPurpose.PRD, "qwen3-coder-next", "你是需求澄清助手", user);
    }

    private static LlmResponse resp(String text) {
        return LlmResponse.of(text, 120, 60, "qwen3-coder-next", "openai", false);
    }

    private static SemanticCache cache() {
        return new SemanticCache(true, 0.78, 3600, 16, "PRD", 0.35);
    }

    @Test
    @DisplayName("归一化去标点空白；同义改写相似度高，不同需求相似度低")
    void normalizationAndSimilarity() {
        assertThat(SemanticCache.normalize("做一个 待办清单！APP"))
                .isEqualTo(SemanticCache.normalize("做一个待办清单app"));

        Map<String, Double> base = SemanticCache.vectorOf("帮我做一个待办清单应用");
        double near = SemanticCache.cosine(base,
                SemanticCache.vectorOf("做一个待办清单应用"));
        double far = SemanticCache.cosine(base,
                SemanticCache.vectorOf("帮我做一个天气预报页面"));

        assertThat(near).as("同一需求的轻微改写应高度相似").isGreaterThan(0.70);
        assertThat(far).as("不同需求必须明显低于阈值").isLessThan(0.60);
        assertThat(near).isGreaterThan(far + 0.2);
        assertThat(SemanticCache.cosine(base, base)).isGreaterThan(0.9999);
    }

    @Test
    @DisplayName("两级命中：完全相同 → EXACT；措辞改写 → SEMANTIC")
    void exactAndSemanticHits() {
        SemanticCache c = cache();
        c.store(req("帮我做一个待办清单应用"), resp("PRD: 待办清单"));

        SemanticCache.Hit exact = c.lookup(req("帮我做一个待办清单应用")).orElseThrow();
        assertThat(exact.matchType()).isEqualTo(SemanticCache.MatchType.EXACT);
        assertThat(exact.similarity()).isEqualTo(1.0);
        assertThat(exact.response().text()).isEqualTo("PRD: 待办清单");

        SemanticCache.Hit near = c.lookup(req("做一个待办清单应用")).orElseThrow();
        assertThat(near.matchType()).isEqualTo(SemanticCache.MatchType.SEMANTIC);
        assertThat(near.similarity()).isBetween(0.70, 1.0);
        assertThat(near.response().text()).isEqualTo("PRD: 待办清单");
    }

    @Test
    @DisplayName("不同需求 / 不同模型 / 不同系统提示词 / 不同用途 都不串味")
    void scopeIsolation() {
        SemanticCache c = cache();
        LlmRequest original = req("帮我做一个待办清单应用");
        c.store(original, resp("PRD: 待办清单"));

        assertThat(c.lookup(req("帮我做一个天气预报页面"))).isEmpty();
        assertThat(c.lookup(LlmRequest.of(LlmPurpose.PRD, "qwen3-coder-flash",
                "你是需求澄清助手", "帮我做一个待办清单应用"))).isEmpty();
        assertThat(c.lookup(LlmRequest.of(LlmPurpose.PRD, "qwen3-coder-next",
                "另一套系统提示词", "帮我做一个待办清单应用"))).isEmpty();
        assertThat(c.lookup(LlmRequest.of(LlmPurpose.CODE, "qwen3-coder-next",
                "你是需求澄清助手", "帮我做一个待办清单应用"))).isEmpty();
    }

    @Test
    @DisplayName("安全边界：只缓存 PRD；CODE/MODIFY/FIX 与高温采样一律不缓存")
    void eligibilityBoundaries() {
        SemanticCache c = cache();
        assertThat(c.eligible(req("做一个页面"))).isTrue();
        assertThat(c.eligible(LlmRequest.of(LlmPurpose.CODE, "m", "s", "u"))).isFalse();
        assertThat(c.eligible(LlmRequest.of(LlmPurpose.MODIFY, "m", "s", "u"))).isFalse();
        assertThat(c.eligible(LlmRequest.of(LlmPurpose.FIX, "m", "s", "u"))).isFalse();
        assertThat(c.eligible(new LlmRequest(LlmPurpose.PRD, "m", "s", "u", 0.9, 8192))).isFalse();
        assertThat(c.eligible(new LlmRequest(LlmPurpose.PRD, "m", "s", "  ", 0.3, 8192))).isFalse();

        c.store(LlmRequest.of(LlmPurpose.FIX, "m", "s", "修复报错"), resp("失败输出"));
        assertThat(c.size()).isZero();
    }

    @Test
    @DisplayName("空输出不入库；TTL 过期后不再命中")
    void blankAndExpiry() throws Exception {
        SemanticCache c = cache();
        c.store(req("需求一"), LlmResponse.of("  ", 10, 5, "m", "p", true));
        assertThat(c.size()).isZero();

        SemanticCache expiring = new SemanticCache(true, 0.78, 0, 16, "PRD", 0.35);
        expiring.store(req("需求一"), resp("PRD"));
        Thread.sleep(5);
        assertThat(expiring.lookup(req("需求一"))).isEmpty();
    }

    @Test
    @DisplayName("容量上限触发 LRU 淘汰，不会无限增长")
    void capacityBounded() {
        SemanticCache c = new SemanticCache(true, 0.78, 3600, 2, "PRD", 0.35);
        c.store(req("做一个待办清单应用"), resp("A"));
        c.store(req("做一个天气预报页面"), resp("B"));
        c.store(req("做一个记账本页面"), resp("C"));

        assertThat(c.size()).isLessThanOrEqualTo(2);
    }

    @Test
    @DisplayName("命中台账：hits/misses、节省 token、估算成本、边界说明一并返回")
    void statsAndDescribe() {
        SemanticCache c = cache();
        c.recordMiss();
        c.store(req("帮我做一个待办清单应用"), resp("PRD"));
        SemanticCache.Hit hit = c.lookup(req("帮我做一个待办清单应用")).orElseThrow();
        c.recordHit(hit, ModelTier.LIGHT, 0.0123);

        SemanticCache.Stats s = c.stats();
        assertThat(s.hits()).isEqualTo(1);
        assertThat(s.misses()).isEqualTo(1);
        assertThat(s.exactHits()).isEqualTo(1);
        assertThat(s.hitRatePercent()).isEqualTo(50.0);
        assertThat(s.savedInputTokens()).isEqualTo(120);
        assertThat(s.savedOutputTokens()).isEqualTo(60);
        assertThat(s.savedCost()).isEqualTo(0.0123);
        assertThat(s.cacheablePurposes()).containsExactly("PRD");

        Map<String, Object> doc = c.describe();
        assertThat(doc.get("method").toString()).contains("非 embedding");
        assertThat(doc.get("boundary").toString())
                .contains("不缓存 CODE", "不缓存 MODIFY/FIX", "不计入 TokenMeter");
    }

    @Test
    @DisplayName("开关关闭后完全不参与，lookup 恒空")
    void disabled() {
        SemanticCache c = new SemanticCache(false, 0.78, 3600, 16, "PRD", 0.35);
        assertThat(c.enabled()).isFalse();
        assertThat(c.eligible(req("做一个页面"))).isFalse();
        c.store(req("做一个页面"), resp("PRD"));
        assertThat(c.lookup(req("做一个页面"))).isEmpty();
        assertThat(c.size()).isZero();
    }
}
