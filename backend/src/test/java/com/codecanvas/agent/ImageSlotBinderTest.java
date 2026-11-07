package com.codecanvas.agent;

import com.codecanvas.agent.tool.VisualGatewayClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配图槽位绑定测试（W2.5）。
 *
 * <p>三条硬约束：
 * 1. 槽位图必须被换成真实 URL，且**只处理槽位图**（模型自己写的图不许动）；
 * 2. 网关不可用/降级时必须换成**渐变占位图**，不能留 404 或空 src；
 * 3. 解析器抛异常也不能把渲染链路带崩。
 */
class ImageSlotBinderTest {

    private static VisualGatewayClient.Result ok(String url) {
        return new VisualGatewayClient.Result("asset-1", url, "http://gw" + url, 1536, 864, 0.0012, false, "ok");
    }

    private static String page(String img) {
        return "<!DOCTYPE html><html><head><title>t</title></head><body>" + img + "</body></html>";
    }

    @Test
    @DisplayName("槽位图被替换为产物 URL，并保留 data-slot 供前端定位")
    void bindsSlotToRealUrl() {
        List<String> asked = new ArrayList<>();
        ImageSlotBinder.Result result = ImageSlotBinder.bind(
                page("<img data-slot=\"hero\" data-aspect=\"16:9\" alt=\"海边插画\" src=\"slot:hero\">"),
                (slot, prompt, aspect) -> {
                    asked.add(slot + "|" + prompt + "|" + aspect);
                    return ok("/v1/assets/abc.png");
                });

        assertThat(result.changed()).isTrue();
        assertThat(result.html()).contains("src=\"/v1/assets/abc.png\"");
        assertThat(result.html()).contains("data-slot=\"hero\"");
        assertThat(result.html()).doesNotContain("slot:hero");
        assertThat(asked).containsExactly("hero|海边插画|16:9");
        assertThat(result.bound()).singleElement().satisfies(b -> {
            assertThat(b.assetId()).isEqualTo("asset-1");
            assertThat(b.width()).isEqualTo(1536);
            assertThat(b.costUsd()).isEqualTo(0.0012);
            assertThat(b.degraded()).isFalse();
        });
    }

    @Test
    @DisplayName("data-prompt 优先于 alt 作为生成提示词")
    void promptPrefersDataPrompt() {
        List<String> prompts = new ArrayList<>();
        ImageSlotBinder.bind(
                page("<img data-slot=\"og\" data-prompt=\"深色科技风抽象背景\" alt=\"背景\" src=\"slot:og\">"),
                (slot, prompt, aspect) -> {
                    prompts.add(prompt);
                    return ok("/v1/assets/x.png");
                });
        assertThat(prompts).containsExactly("深色科技风抽象背景");
    }

    @Test
    @DisplayName("降级：网关不可用 → 换成渐变占位图，页面不缺图")
    void degradedReplacedByPlaceholder() {
        ImageSlotBinder.Result result = ImageSlotBinder.bind(
                page("<img data-slot=\"hero\" alt=\"图\" src=\"slot:hero\">"),
                (slot, prompt, aspect) -> VisualGatewayClient.Result.disabled());

        assertThat(result.html()).contains(ImageSlotBinder.PLACEHOLDER);
        assertThat(result.html()).doesNotContain("slot:hero");
        assertThat(result.bound()).singleElement().satisfies(b -> {
            assertThat(b.degraded()).isTrue();
            assertThat(b.assetId()).isNull();
        });
    }

    @Test
    @DisplayName("解析器抛异常 → 同样降级为占位图，不向外抛")
    void resolverExceptionDegrades() {
        ImageSlotBinder.Result result = ImageSlotBinder.bind(
                page("<img data-slot=\"hero\" alt=\"图\" src=\"slot:hero\">"),
                (slot, prompt, aspect) -> {
                    throw new IllegalStateException("boom");
                });
        assertThat(result.html()).contains(ImageSlotBinder.PLACEHOLDER);
        assertThat(result.bound()).hasSize(1);
    }

    @Test
    @DisplayName("非槽位图原样保留（模型自己写的图不许被改）")
    void leavesNonSlotImagesAlone() {
        String html = page("<img src=\"https://cdn.example.com/a.png\" alt=\"模型写的图\">");
        ImageSlotBinder.Result result = ImageSlotBinder.bind(html, (s, p, a) -> ok("/v1/assets/zzz.png"));

        assertThat(result.changed()).isFalse();
        assertThat(result.html()).isEqualTo(html);
        assertThat(result.bound()).isEmpty();
    }

    @Test
    @DisplayName("多槽位：逐个绑定，各有独立结果")
    void bindsMultipleSlots() {
        ImageSlotBinder.Result result = ImageSlotBinder.bind(
                page("<img data-slot=\"hero\" alt=\"主图\" src=\"slot:hero\">"
                        + "<img data-slot=\"card1\" alt=\"卡片\" src=\"slot:card1\">"),
                (slot, prompt, aspect) -> "hero".equals(slot)
                        ? ok("/v1/assets/hero.png")
                        : VisualGatewayClient.Result.disabled());

        assertThat(result.bound()).hasSize(2);
        assertThat(result.html()).contains("/v1/assets/hero.png");
        assertThat(result.html()).contains(ImageSlotBinder.PLACEHOLDER);
    }

    @Test
    @DisplayName("没有槽位/没有解析器/空文档 → 原样返回，changed=false")
    void noOpCases() {
        String plain = page("<h1>无图页面</h1>");
        assertThat(ImageSlotBinder.bind(plain, (s, p, a) -> ok("/x.png")).changed()).isFalse();
        assertThat(ImageSlotBinder.bind(plain, null).html()).isEqualTo(plain);
        assertThat(ImageSlotBinder.bind(null, (s, p, a) -> ok("/x.png")).changed()).isFalse();
    }

    @Test
    @DisplayName("src 缺失时补上 src 属性，而不是丢掉这张图")
    void addsSrcWhenMissing() {
        ImageSlotBinder.Result result = ImageSlotBinder.bind(
                page("<img data-slot=\"hero\" alt=\"图\">"), (s, p, a) -> ok("/v1/assets/n.png"));
        assertThat(result.html()).contains("<img src=\"/v1/assets/n.png\"");
    }
}