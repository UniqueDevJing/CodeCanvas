package com.codecanvas.it;

import com.codecanvas.agent.ImageSlotBinder;
import com.codecanvas.agent.tool.VisualGatewayClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真机链路测试（W1.5）：Java 客户端 → 视觉网关 → 真 ComfyUI → 真 PNG。
 *
 * <p><b>默认跳过</b>（需要真跑网关与 ComfyUI），CI 里不执行；本地联调时：
 * <pre>
 *   set VISUAL_GATEWAY_URL=http://127.0.0.1:8089
 *   mvn -o -pl backend test -Dtest=VisualChainManualTest
 * </pre>
 *
 * <p>它验证的不是"能编译"，而是**跨进程、跨语言、带 GPU 的真实链路**：
 * 出图 → 内容寻址落盘 → Java 侧拿到产物 URL → 绑定进 HTML → 该 URL 真的能取回 PNG。
 */
@Tag("manual")
class VisualChainManualTest {

    private static final String BASE = System.getenv("VISUAL_GATEWAY_URL");

    @Test
    @DisplayName("真机：提交→出图→绑定进 HTML→按 URL 取回 PNG")
    void realChainProducesRenderableImage() throws Exception {
        Assumptions.assumeTrue(BASE != null && !BASE.isBlank(),
                "VISUAL_GATEWAY_URL 未设置，跳过真机联调");

        VisualGatewayClient client = new VisualGatewayClient(
                BASE, Duration.ofSeconds(30), Duration.ofSeconds(240), 1000);

        VisualGatewayClient.Result result = client.generate(
                "serene anime couple under starry sky by the sea, digital art, soft light",
                "1:1", "hero", 42L);

        assertThat(result.degraded()).as("网关返回降级: %s", result.note()).isFalse();
        assertThat(result.url()).startsWith("/v1/assets/");
        assertThat(result.width()).isEqualTo(1024);
        assertThat(result.height()).isEqualTo(1024);
        assertThat(result.assetId()).hasSize(64);

        // 产物 URL 真的能取回 PNG，且不是空壳（真实生成的文件显著大于占位图）
        HttpResponse<byte[]> asset = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(BASE + result.url()))
                        .timeout(Duration.ofSeconds(30)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(asset.statusCode()).isEqualTo(200);
        byte[] png = asset.body();
        assertThat(png.length).as("真实生成图应远大于占位图").isGreaterThan(100_000);
        assertThat(png[0] & 0xFF).isEqualTo(0x89);
        assertThat(new String(png, 1, 3, java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("PNG");

        // 绑定进页面：槽位被换成真实 URL，页面里不再有 slot: 残留
        String page = "<!DOCTYPE html><html><head><title>t</title></head><body>"
                + "<img data-slot=\"hero\" data-aspect=\"1:1\" alt=\"海边插画\" src=\"slot:hero\">"
                + "</body></html>";
        ImageSlotBinder.Result bound = ImageSlotBinder.bind(page, (slot, prompt, aspect) ->
                {
                    try {
                        return client.generate(prompt, aspect, slot, 42L);
                    } catch (Exception e) {
                        return VisualGatewayClient.Result.degraded("client-error");
                    }
                });

        assertThat(bound.changed()).isTrue();
        // 注意：绑定器用 <img> 的 alt 作为生成提示词，所以第二张图与上面那张不是同一张，
        // 这里断言"换成了合法产物 URL"，而不是与上一次结果相等。
        assertThat(bound.html()).containsPattern("/v1/assets/[0-9a-f]{64}\\.png");
        assertThat(bound.html()).doesNotContain("slot:hero");
        assertThat(bound.html()).doesNotContain("data:image/svg+xml");
        assertThat(bound.bound()).singleElement().satisfies(b -> {
            assertThat(b.degraded()).isFalse();
            assertThat(b.costUsd()).isGreaterThan(0.0);
        });
    }
}