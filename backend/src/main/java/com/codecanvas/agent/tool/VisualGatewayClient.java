package com.codecanvas.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 视觉能力网关的 Java 客户端（W1 网关 ↔ W2 CodeCanvas 的桥）。
 *
 * <p>职责边界：**只负责"提交 + 等待 + 取产物元数据"**，不做任何提示词工程、不缓存图片、
 * 不重试业务语义（网关自己保证幂等）。这样两边都能独立测试。
 *
 * <p>关键取舍：
 * <ul>
 *   <li>**没有配置就禁用**：{@code baseUrl} 为空时 {@link #enabled()} 返回 false，
 *       上层工具据此返回 DISABLED 而不是抛异常——"视觉能力"是增强项，不该让主链路 500；</li>
 *   <li>**有界等待**：Agent 的工具调用是同步的，所以这里给整条链路一个总预算，
 *       超时返回 degraded 结果，让生成继续走"无配图"路径。</li>
 * </ul>
 */
public class VisualGatewayClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient http;
    private final Duration requestTimeout;
    private final Duration totalBudget;
    private final long pollIntervalMs;

    public VisualGatewayClient(String baseUrl) {
        this(baseUrl, Duration.ofSeconds(10), Duration.ofSeconds(90), 200);
    }

    public VisualGatewayClient(String baseUrl, Duration requestTimeout, Duration totalBudget, long pollIntervalMs) {
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim().replaceAll("/+$", "");
        this.requestTimeout = requestTimeout;
        this.totalBudget = totalBudget;
        this.pollIntervalMs = Math.max(10, pollIntervalMs);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** 未配置网关时返回 false —— 调用方必须优雅降级，而不是把异常抛给用户。 */
    public boolean enabled() {
        return !baseUrl.isBlank();
    }

    public record Result(
            String assetId,
            String url,
            String absoluteUrl,
            int width,
            int height,
            double estimatedUsd,
            boolean degraded,
            String note) {

        public static Result disabled() {
            return new Result(null, null, null, 0, 0, 0.0, true, "visual-gateway-not-configured");
        }

        public static Result degraded(String note) {
            return new Result(null, null, null, 0, 0, 0.0, true, note);
        }
    }

    public Result generate(String prompt, String aspect, String role, Long seed) throws IOException, InterruptedException {
        if (!enabled()) {
            return Result.disabled();
        }
        long deadline = System.nanoTime() + totalBudget.toNanos();

        ObjectNode body = MAPPER.createObjectNode();
        body.put("prompt", prompt == null ? "" : prompt);
        body.put("aspect", aspect == null || aspect.isBlank() ? "16:9" : aspect);
        body.put("role", role == null || role.isBlank() ? "illustration" : role);
        if (seed != null) {
            body.put("seed", seed);
        }

        HttpResponse<String> submitted = post("/v1/images", MAPPER.writeValueAsString(body));
        if (submitted.statusCode() == 400) {
            return Result.degraded("prompt-rejected");
        }
        if (submitted.statusCode() != 202) {
            return Result.degraded("submit-failed-" + submitted.statusCode());
        }
        String jobId = MAPPER.readTree(submitted.body()).path("job_id").asText("");
        if (jobId.isBlank()) {
            return Result.degraded("submit-no-job-id");
        }

        while (System.nanoTime() < deadline) {
            HttpResponse<String> polled = get("/v1/images/" + jobId);
            if (polled.statusCode() != 200) {
                return Result.degraded("poll-failed-" + polled.statusCode());
            }
            JsonNode job = MAPPER.readTree(polled.body());
            String status = job.path("status").asText("");
            if ("SUCCEEDED".equals(status)) {
                JsonNode artifact = job.path("artifacts").path(0);
                if (artifact.isMissingNode() || artifact.path("asset_id").asText("").isBlank()) {
                    return Result.degraded("succeeded-without-artifact");
                }
                String url = artifact.path("url").asText("");
                double usd = job.path("cost").path("estimated_usd").asDouble(0.0);
                return new Result(
                        artifact.path("asset_id").asText(),
                        url,
                        baseUrl + url,
                        artifact.path("width").asInt(0),
                        artifact.path("height").asInt(0),
                        usd,
                        false,
                        "ok");
            }
            if ("FAILED".equals(status) || "CANCELED".equals(status)) {
                return Result.degraded(status.toLowerCase() + ":" + job.path("error").asText(""));
            }
            Thread.sleep(pollIntervalMs);
        }
        return Result.degraded("client-timeout");
    }

    private HttpResponse<String> post(String path, String json) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}