package com.codecanvas.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * {@code generateImage} 工具：把"视觉能力网关"暴露成一个 Agent 可调用的动作。
 *
 * <p>返回给 Agent 的永远是**结构化 JSON**（不是自然语言），这样模型能判断成功/失败，
 * trace 里也能被机器审计。三条纪律：
 * <ol>
 *   <li>未配置 → {@code DISABLED}，不抛异常（视觉是增强，不是必需）；</li>
 *   <li>降级 → {@code DEGRADED} + 原因，调用方据此走"无配图"分支；</li>
 *   <li>成功 → 带 {@code assetId/url/width/height/cost}，前端可直接 <img src>。</li>
 * </ol>
 */
public class GenerateImageTool implements Tool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final VisualGatewayClient client;

    public GenerateImageTool(VisualGatewayClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return "generateImage";
    }

    @Override
    public String description() {
        return "为页面生成配图（hero/icon/illustration/og）。参数：prompt(必填，中文描述), "
                + "aspect(1:1|16:9|9:16|4:3|3:4), role, seed(可选)。返回 JSON："
                + "status=OK 时含 assetId/url/width/height/costUsd。";
    }

    @Override
    public String execute(Map<String, Object> args) {
        ObjectNode out = MAPPER.createObjectNode();
        String prompt = asString(args.get("prompt"));
        if (prompt.isBlank()) {
            out.put("status", "INVALID");
            out.put("note", "prompt 必填");
            return out.toString();
        }
        if (!client.enabled()) {
            out.put("status", "DISABLED");
            out.put("note", "visual-gateway-not-configured");
            return out.toString();
        }
        try {
            Long seed = args.get("seed") instanceof Number n ? n.longValue() : null;
            VisualGatewayClient.Result result = client.generate(
                    prompt, asString(args.get("aspect")), asString(args.get("role")), seed);
            if (result.degraded()) {
                out.put("status", "DEGRADED");
                out.put("note", result.note());
                return out.toString();
            }
            out.put("status", "OK");
            out.put("assetId", result.assetId());
            out.put("url", result.url());
            out.put("absoluteUrl", result.absoluteUrl());
            out.put("width", result.width());
            out.put("height", result.height());
            out.put("costUsd", result.estimatedUsd());
            return out.toString();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.put("status", "DEGRADED");
            out.put("note", "interrupted");
            return out.toString();
        } catch (Exception e) {
            out.put("status", "DEGRADED");
            out.put("note", "client-error:" + e.getClass().getSimpleName());
            return out.toString();
        }
    }

    private static String asString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}