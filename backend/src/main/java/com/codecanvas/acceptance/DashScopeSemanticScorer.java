package com.codecanvas.acceptance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * DashScope 语义打分器（C-A）：OpenAI 兼容 /embeddings 协议，对断言描述与页面可见文本
 * 各做一次嵌入，返回余弦相似度。
 *
 * <p>降级边界：未配 key / 超时 / 非 200 / 响应缺字段 → 返回 -1，
 * 求值器按"语义打分暂不可用"诚实降级（不阻断交付，不假装通过）。
 * codecanvas.acceptance.semantic-enabled=false（默认）时本 Bean 不加载。
 */
@Component
@ConditionalOnProperty(name = "codecanvas.acceptance.semantic-enabled", havingValue = "true")
public class DashScopeSemanticScorer implements SemanticScorer {

    private static final Logger log = LoggerFactory.getLogger(DashScopeSemanticScorer.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public DashScopeSemanticScorer(
            @Value("${codecanvas.llm.openai.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}") String baseUrl,
            @Value("${codecanvas.llm.openai.api-key:}") String apiKey,
            @Value("${codecanvas.acceptance.semantic-model:text-embedding-v3}") String model) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.model = model;
        if (this.apiKey.isBlank()) {
            log.warn("[acceptance-semantic] 未配置嵌入 API key，语义断言将按'打分暂不可用'降级");
        }
    }

    @Override
    public double similarity(String claim, String pageText) {
        if (apiKey.isBlank() || claim == null || claim.isBlank() || pageText == null || pageText.isBlank()) {
            return -1;
        }
        try {
            float[] a = embed(clip(claim));
            float[] b = embed(clip(pageText));
            if (a == null || b == null || a.length != b.length) {
                return -1;
            }
            double dot = 0;
            double na = 0;
            double nb = 0;
            for (int i = 0; i < a.length; i++) {
                dot += (double) a[i] * b[i];
                na += (double) a[i] * a[i];
                nb += (double) b[i] * b[i];
            }
            if (na == 0 || nb == 0) {
                return -1;
            }
            return dot / (Math.sqrt(na) * Math.sqrt(nb));
        } catch (Exception e) {  // noqa — 降级边界: 打分不可用不抛出
            log.debug("[acceptance-semantic] 嵌入调用失败: {}", e.getMessage());
            return -1;
        }
    }

    private static String clip(String s) {
        return s.length() > 4000 ? s.substring(0, 4000) : s;
    }

    private float[] embed(String text) throws Exception {
        String payload = mapper.writeValueAsString(Map.of(
                "model", model,
                "input", List.of(text),
                "dimensions", 1024));
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/embeddings"))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        HttpResponse<String> resp = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            return null;
        }
        JsonNode arr = mapper.readTree(resp.body()).path("data").path(0).path("embedding");
        if (!arr.isArray() || arr.isEmpty()) {
            return null;
        }
        float[] v = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            v[i] = (float) arr.get(i).asDouble();
        }
        return v;
    }

    @Override
    public String model() {
        return model;
    }
}
