package com.codecanvas.llm;

import com.codecanvas.config.LlmProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时的模型可用性自检。
 *
 * <p>策略（先便宜后准确）：
 * <ol>
 *   <li>优先 {@code GET /models}——不消耗 token，能一次判断"目录里有没有这个名字"；</li>
 *   <li>接口不支持 {@code /models}（404/405）时，退回单次极小的
 *       {@code POST /chat/completions}（{@code max_tokens=1}），成本可忽略；</li>
 *   <li>无密钥 / 网络不通 / 显式 mock 模式 → 一律记 {@code UNKNOWN} 并跳过，
 *       **不假装可用，也不误报不可用**。</li>
 * </ol>
 *
 * <p>失败绝不阻断启动：自检是诊断能力，不是启动依赖。
 */
@Component
public class ModelAvailabilityProbe {

    private static final Logger log = LoggerFactory.getLogger(ModelAvailabilityProbe.class);

    private final LlmProperties properties;
    private final LlmGateway gateway;
    private final ModelAvailability availability;
    private final HttpClient http;

    public ModelAvailabilityProbe(LlmProperties properties,
                                  LlmGateway gateway,
                                  ModelAvailability availability) {
        this.properties = properties;
        this.gateway = gateway;
        this.availability = availability;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(4))
                .version(HttpClient.Version.HTTP_1_1)   // 与 VisualGatewayClient 同因：避开 h2c 升级
                .build();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            probeAll();
        } catch (RuntimeException e) {
            log.warn("[model-probe] 启动自检异常（不影响启动）：{}", e.toString());
        }
    }

    /** 探测配置中的全部模型（去重），返回不可用清单。 */
    public List<String> probeAll() {
        LlmProperties.Models models = properties.getModels();
        Set<String> configured = new LinkedHashSet<>();
        configured.add(models.getFlagship());
        configured.add(models.getStandard());
        configured.add(models.getLight());

        if (!gateway.usingRealModel()) {
            for (String model : configured) {
                availability.record(model, ModelAvailability.Status.UNKNOWN,
                        "当前为离线模式（未配置可用密钥或 mode=mock），未探测");
            }
            log.info("[model-probe] 离线模式，跳过模型可用性探测");
            return List.of();
        }

        List<String> catalog = fetchCatalog();
        for (String model : configured) {
            if (model == null || model.isBlank()) {
                continue;
            }
            probeOne(model, catalog);
        }

        List<String> bad = availability.unavailableModels(List.copyOf(configured));
        if (!bad.isEmpty()) {
            log.warn("[model-probe] 以下模型在账号上不存在，生成会先撞 404 再回退：{}；"
                    + "请改 CODECANVAS_MODEL_* 为可用模型（如 qwen3-coder-flash / qwen-plus）", bad);
        } else {
            log.info("[model-probe] 模型自检通过：{}", configured);
        }
        return bad;
    }

    private void probeOne(String model, List<String> catalog) {
        if (catalog != null) {
            boolean present = catalog.stream().anyMatch(m -> m.equalsIgnoreCase(model));
            availability.record(model, present
                            ? ModelAvailability.Status.AVAILABLE
                            : ModelAvailability.Status.UNAVAILABLE,
                    present ? "目录中存在" : "目录中不存在（/models 未列出该名字）");
            return;
        }
        availability.record(model, completionProbe(model), "按 /chat/completions 探测");
    }

    /** 拉取模型目录；失败返回 null（表示"目录不可用，需要逐模型探测"）。 */
    private List<String> fetchCatalog() {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint("/models")))
                    .timeout(Duration.ofSeconds(6))
                    .header("Authorization", "Bearer " + properties.getOpenai().getApiKey())
                    .GET()
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return null;
            }
            String body = response.body();
            return java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([^\"]+)\"")
                    .matcher(body).results().map(m -> m.group(1)).toList();
        } catch (Exception e) {
            return null;
        }
    }

    /** 单模型极小请求探测：只看 404/未找到类错误，不关心正文。 */
    private ModelAvailability.Status completionProbe(String model) {
        try {
            String body = "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                    + "\"max_tokens\":1}";
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint("/chat/completions")))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.getOpenai().getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 200) {
                return ModelAvailability.Status.AVAILABLE;
            }
            String text = response.body() == null ? "" : response.body().toLowerCase();
            if (code == 404 || text.contains("model_not_found") || text.contains("model not exist")) {
                return ModelAvailability.Status.UNAVAILABLE;
            }
            return ModelAvailability.Status.UNKNOWN;
        } catch (Exception e) {
            return ModelAvailability.Status.UNKNOWN;
        }
    }

    private String endpoint(String suffix) {
        String base = properties.getOpenai().getBaseUrl();
        String trimmed = base == null ? "" : base.replaceAll("/+$", "");
        return trimmed + suffix;
    }
}