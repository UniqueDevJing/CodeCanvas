package com.codecanvas.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 视觉网关客户端测试（W2）。
 *
 * <p>用 JDK 自带 {@code com.sun.net.httpserver} 起一个假网关，**不引第三方 mock 库**，
 * 断言三件事：正常链路拿到产物、被安全拦截时不报错而是降级、网关挂了不拖垮主链路。
 */
class VisualGatewayClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HttpServer server;
    private final AtomicInteger polls = new AtomicInteger();
    private final AtomicReference<String> lastSubmitBody = new AtomicReference<>();

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void startServer(String submitResponse, int submitStatus, String pollStatus) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/images", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if ("/v1/images".equals(path) && "POST".equals(exchange.getRequestMethod())) {
                lastSubmitBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                respond(exchange, submitStatus, submitResponse);
                return;
            }
            if (path.startsWith("/v1/images/")) {
                polls.incrementAndGet();
                String status = pollStatus == null ? "RUNNING" : pollStatus;
                respond(exchange, 200, """
                        {"job_id":"j1","status":"%s","progress":1.0,
                         "artifacts":[{"asset_id":"a1b2c3d4e5f6a7b8","url":"/v1/assets/a1b2c3d4e5f6a7b8.png",
                                       "width":1536,"height":864,"bytes":1024,"content_type":"image/png"}],
                         "cost":{"gpu_seconds":12.5,"estimated_usd":0.00125},
                         "degraded":false,"cache_hit":false,"error":null}
                        """.formatted(status));
                return;
            }
            respond(exchange, 404, "{}");
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @BeforeEach
    void setUp() {
        polls.set(0);
        lastSubmitBody.set(null);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("正常链路：提交 → 轮询 → 拿到 assetId/尺寸/成本")
    void happyPath() throws Exception {
        startServer("{\"job_id\":\"j1\"}", 202, "SUCCEEDED");
        VisualGatewayClient client = new VisualGatewayClient(baseUrl(), Duration.ofSeconds(5), Duration.ofSeconds(5), 20);

        VisualGatewayClient.Result result = client.generate("海边星空下的动漫情侣", "16:9", "hero", 42L);

        assertThat(result.degraded()).isFalse();
        assertThat(result.assetId()).isEqualTo("a1b2c3d4e5f6a7b8");
        assertThat(result.url()).isEqualTo("/v1/assets/a1b2c3d4e5f6a7b8.png");
        assertThat(result.absoluteUrl()).isEqualTo(baseUrl() + result.url());
        assertThat(result.width()).isEqualTo(1536);
        assertThat(result.height()).isEqualTo(864);
        assertThat(result.estimatedUsd()).isEqualTo(0.00125);

        String body = lastSubmitBody.get();
        assertThat(body).contains("海边星空下的动漫情侣").contains("16:9").contains("hero").contains("42");
    }

    @Test
    @DisplayName("未配置网关 → enabled()=false，工具返回 DISABLED 而不是抛异常")
    void disabledWhenNotConfigured() {
        VisualGatewayClient client = new VisualGatewayClient("  ");
        assertThat(client.enabled()).isFalse();

        String out = new GenerateImageTool(client).execute(Map.of("prompt", "海边"));
        assertThat(out).contains("\"status\":\"DISABLED\"");
    }

    @Test
    @DisplayName("被安全拦截（400）→ 降级为 prompt-rejected，不重试不抛错")
    void promptRejectedIsDegraded() throws Exception {
        startServer("{\"error\":\"prompt-rejected\"}", 400, null);
        VisualGatewayClient client = new VisualGatewayClient(baseUrl(), Duration.ofSeconds(5), Duration.ofSeconds(5), 20);

        VisualGatewayClient.Result result = client.generate("生成一张X光片", "16:9", "hero", null);

        assertThat(result.degraded()).isTrue();
        assertThat(result.note()).isEqualTo("prompt-rejected");
    }

    @Test
    @DisplayName("网关 5xx → 降级，绝不把异常抛给 Agent")
    void serverErrorIsDegraded() throws Exception {
        startServer("{}", 500, null);
        VisualGatewayClient client = new VisualGatewayClient(baseUrl(), Duration.ofSeconds(5), Duration.ofSeconds(5), 20);

        VisualGatewayClient.Result result = client.generate("海边", "1:1", "icon", null);

        assertThat(result.degraded()).isTrue();
        assertThat(result.note()).startsWith("submit-failed-500");
    }

    @Test
    @DisplayName("任务失败 → 降级并带原因")
    void failedJobIsDegraded() throws Exception {
        startServer("{\"job_id\":\"j1\"}", 202, "FAILED");
        VisualGatewayClient client = new VisualGatewayClient(baseUrl(), Duration.ofSeconds(5), Duration.ofSeconds(5), 20);

        VisualGatewayClient.Result result = client.generate("海边", "1:1", "icon", null);

        assertThat(result.degraded()).isTrue();
        assertThat(result.note()).startsWith("failed");
    }

    @Test
    @DisplayName("一直 RUNNING → 客户端预算耗尽后降级为 client-timeout")
    void runningForeverTimesOut() throws Exception {
        startServer("{\"job_id\":\"j1\"}", 202, "RUNNING");
        VisualGatewayClient client = new VisualGatewayClient(baseUrl(), Duration.ofSeconds(5), Duration.ofMillis(300), 20);

        VisualGatewayClient.Result result = client.generate("海边", "1:1", "icon", null);

        assertThat(result.degraded()).isTrue();
        assertThat(result.note()).isEqualTo("client-timeout");
        assertThat(polls.get()).isGreaterThan(1);
    }

    @Test
    @DisplayName("工具输出是结构化 JSON，成功时含 assetId/url/width/height/costUsd")
    void toolReturnsStructuredJson() throws Exception {
        startServer("{\"job_id\":\"j1\"}", 202, "SUCCEEDED");
        VisualGatewayClient client = new VisualGatewayClient(baseUrl(), Duration.ofSeconds(5), Duration.ofSeconds(5), 20);

        String out = new GenerateImageTool(client).execute(Map.of("prompt", "插画", "aspect", "1:1", "role", "icon"));

        var node = MAPPER.readTree(out);
        assertThat(node.path("status").asText()).isEqualTo("OK");
        assertThat(node.path("assetId").asText()).isNotBlank();
        assertThat(node.path("width").asInt()).isEqualTo(1536);
        assertThat(node.path("costUsd").asDouble()).isEqualTo(0.00125);
    }

    @Test
    @DisplayName("工具拒绝空 prompt，不发起请求")
    void toolRejectsEmptyPrompt() throws Exception {
        startServer("{\"job_id\":\"j1\"}", 202, "SUCCEEDED");
        VisualGatewayClient client = new VisualGatewayClient(baseUrl(), Duration.ofSeconds(5), Duration.ofSeconds(5), 20);

        String out = new GenerateImageTool(client).execute(Map.of("prompt", "   "));

        assertThat(out).contains("\"status\":\"INVALID\"");
        assertThat(polls.get()).isZero();
    }
}