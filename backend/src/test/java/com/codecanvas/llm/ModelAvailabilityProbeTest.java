package com.codecanvas.llm;

import com.codecanvas.config.LlmProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型可用性自检测试（启动流程的一部分）。
 *
 * <p>要证明的是"**配置里写了 ≠ 账号上存在**"这件事能被自动发现：
 * <ul>
 *   <li>{@code /models} 可列目录 → 直接判定存在/不存在，不烧 token；</li>
 *   <li>{@code /models} 不可用 → 退回单次极小 completion，404 即判不可用；</li>
 *   <li>网络不通 / 离线模式 → {@code UNKNOWN}，**不误报不可用**。</li>
 * </ul>
 */
class ModelAvailabilityProbeTest {

    private HttpServer server;
    private final ModelAvailability availability = new ModelAvailability();

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private String startServer(boolean catalogSupported) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/models", exchange -> {
            if (!catalogSupported) {
                respond(exchange, 404, "{\"error\":\"not found\"}");
                return;
            }
            respond(exchange, 200, "{\"data\":[{\"id\":\"qwen3-coder-flash\"},{\"id\":\"qwen-plus\"}]}");
        });
        server.createContext("/chat/completions", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("not-exist-model")) {
                respond(exchange, 404, "{\"error\":{\"message\":\"Model not exist.\",\"code\":\"model_not_found\"}}");
            } else {
                respond(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}");
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private LlmProperties props(String baseUrl, String flagship, String standard, String light) {
        LlmProperties properties = new LlmProperties();
        properties.setMode("real");
        properties.getOpenai().setBaseUrl(baseUrl);
        properties.getOpenai().setApiKey("sk-test");
        properties.getModels().setFlagship(flagship);
        properties.getModels().setStandard(standard);
        properties.getModels().setLight(light);
        return properties;
    }

    /** 用一个真实可用性判定所需的最小假 gateway：只覆写 usingRealModel。 */
    private LlmGateway gateway(boolean real) {
        return new LlmGateway(props("http://127.0.0.1:1", "m", "m", "m"), null, null, null, null) {
            @Override
            public boolean usingRealModel() {
                return real;
            }
        };
    }

    @Test
    @DisplayName("/models 可列目录时：存在→AVAILABLE，不存在→UNAVAILABLE，且不消耗 completion")
    void usesCatalogWhenAvailable() throws IOException {
        String base = startServer(true);
        ModelAvailabilityProbe probe = new ModelAvailabilityProbe(
                props(base, "qwen3-coder-flash", "qwen3-coder-next", "qwen-plus"), gateway(true), availability);

        List<String> bad = probe.probeAll();

        assertThat(bad).containsExactly("qwen3-coder-next");
        assertThat(availability.get("qwen3-coder-flash").status())
                .isEqualTo(ModelAvailability.Status.AVAILABLE);
        assertThat(availability.get("qwen3-coder-next").status())
                .isEqualTo(ModelAvailability.Status.UNAVAILABLE);
    }

    @Test
    @DisplayName("/models 不支持时：退回 completion 探测，404 判不可用")
    void fallsBackToCompletionProbe() throws IOException {
        String base = startServer(false);
        ModelAvailabilityProbe probe = new ModelAvailabilityProbe(
                props(base, "not-exist-model", "qwen-plus", "qwen-plus"), gateway(true), availability);

        List<String> bad = probe.probeAll();

        assertThat(bad).containsExactly("not-exist-model");
        assertThat(availability.get("qwen-plus").status()).isEqualTo(ModelAvailability.Status.AVAILABLE);
    }

    @Test
    @DisplayName("离线模式：不探测、全部 UNKNOWN，且不报不可用")
    void offlineModeSkipsProbe() {
        ModelAvailabilityProbe probe = new ModelAvailabilityProbe(
                props("http://127.0.0.1:1", "a", "b", "c"), gateway(false), availability);

        assertThat(probe.probeAll()).isEmpty();
        assertThat(availability.get("a").status()).isEqualTo(ModelAvailability.Status.UNKNOWN);
        assertThat(availability.get("a").detail()).contains("离线");
    }

    @Test
    @DisplayName("网络不通：UNKNOWN，绝不误报 UNAVAILABLE")
    void networkFailureIsUnknown() {
        ModelAvailabilityProbe probe = new ModelAvailabilityProbe(
                props("http://127.0.0.1:1", "m1", "m2", "m3"), gateway(true), availability);

        assertThat(probe.probeAll()).isEmpty();
        assertThat(availability.get("m1").status()).isEqualTo(ModelAvailability.Status.UNKNOWN);
    }

    @Test
    @DisplayName("登记表按配置顺序去重输出，未探测项标 UNKNOWN")
    void registryOrdersAndDedupes() {
        availability.record("modelA", ModelAvailability.Status.AVAILABLE, "ok");
        var list = availability.all(List.of("modelA", "modelA", "modelB"));
        assertThat(list).extracting(ModelAvailability.ModelProbe::model).containsExactly("modelA", "modelB");
        assertThat(list.get(1).status()).isEqualTo(ModelAvailability.Status.UNKNOWN);
    }
}