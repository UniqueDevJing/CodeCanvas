package com.codecanvas.agent;

import com.codecanvas.agent.tool.VisualGatewayClient;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配图槽位绑定（W2.5）：把模型输出的 {@code <img data-slot="hero" src="slot:hero">}
 * 换成真实产物 URL，失败则换成渐变占位图。
 *
 * <p>为什么要"槽位"而不是让模型直接写图片 URL：
 * 模型不知道网关地址、也不知道本次会不会有图；让它写死 URL 只会在网关不可用时留下裂图。
 * 槽位是**声明意图**（这里需要一张 hero 图），绑定是**运行时决策**，两件事分开。
 *
 * <p>失败时的兜底是**内联 SVG 渐变 data URI**，不是 404 或空 src：
 * 页面必须永远可渲染——这是"降级不能降级成事故"的具体体现。
 */
public final class ImageSlotBinder {

    /** 绑定时用的解析器（生产走网关，测试可直接给假数据）。 */
    public interface Resolver {
        VisualGatewayClient.Result resolve(String slot, String prompt, String aspect);
    }

    public record Bound(
            String slot,
            String assetId,
            String url,
            int width,
            int height,
            double costUsd,
            boolean degraded) {}

    public record Result(String html, List<Bound> bound, boolean changed) {}

    private static final Pattern IMG_TAG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SLOT = Pattern.compile("data-slot\\s*=\\s*[\"']([^\"']+)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROMPT = Pattern.compile("data-prompt\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern ASPECT = Pattern.compile("data-aspect\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern ALT = Pattern.compile("alt\\s*=\\s*[\"']([^\"']*)[\"']", Pattern.CASE_INSENSITIVE);
    private static final Pattern SRC = Pattern.compile("src\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);

    /** 深色渐变占位：页面永远有话可说，不会出现裂图图标。 */
    static final String PLACEHOLDER =
            "data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='9'%3E"
            + "%3Cdefs%3E%3ClinearGradient id='g' x1='0' y1='0' x2='1' y2='1'%3E"
            + "%3Cstop offset='0' stop-color='%23334155'/%3E%3Cstop offset='1' stop-color='%230f172a'/%3E"
            + "%3C/linearGradient%3E%3C/defs%3E%3Crect width='16' height='9' fill='url(%23g)'/%3E%3C/svg%3E";

    private ImageSlotBinder() {
    }

    public static Result bind(String html, Resolver resolver) {
        if (html == null || html.isBlank() || resolver == null) {
            return new Result(html, List.of(), false);
        }
        Matcher matcher = IMG_TAG.matcher(html);
        StringBuilder out = new StringBuilder(html.length() + 256);
        List<Bound> bound = new ArrayList<>();
        int last = 0;
        boolean changed = false;

        while (matcher.find()) {
            String tag = matcher.group();
            String slot = attr(SLOT, tag);
            if (slot == null || slot.isBlank()) {
                continue; // 不是槽位图（模型自己写的图），原样保留
            }
            String prompt = attrOr(PROMPT, tag, attrOr(ALT, tag, slot));
            String aspect = attrOr(ASPECT, tag, "16:9");

            VisualGatewayClient.Result result;
            try {
                result = resolver.resolve(slot, prompt, aspect);
            } catch (RuntimeException e) {
                result = null; // 解析器异常等价于"这次没有图"，不允许把异常漏到渲染链路
            }

            String src;
            Bound entry;
            if (result == null || result.degraded() || result.url() == null || result.url().isBlank()) {
                src = PLACEHOLDER;
                entry = new Bound(slot, null, PLACEHOLDER, 0, 0, 0.0, true);
            } else {
                src = result.url();
                entry = new Bound(slot, result.assetId(), result.url(),
                        result.width(), result.height(), result.estimatedUsd(), false);
            }

            out.append(html, last, matcher.start()).append(replaceSrc(tag, src));
            last = matcher.end();
            bound.add(entry);
            changed = true;
        }
        out.append(html.substring(last));
        return new Result(out.toString(), bound, changed);
    }

    private static String replaceSrc(String tag, String src) {
        Matcher matcher = SRC.matcher(tag);
        if (matcher.find()) {
            return matcher.replaceFirst("src=\"" + src + "\"");
        }
        return tag.replaceFirst("(?i)<img", "<img src=\"" + src + "\"");
    }

    private static String attrOr(Pattern pattern, String tag, String fallback) {
        String value = attr(pattern, tag);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String attr(Pattern pattern, String tag) {
        Matcher matcher = pattern.matcher(tag);
        return matcher.find() ? matcher.group(1) : null;
    }
}