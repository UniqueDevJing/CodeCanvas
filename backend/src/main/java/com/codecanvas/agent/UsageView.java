package com.codecanvas.agent;

/**
 * 推给前端的用量与路由信息。
 *
 * @param cost               本轮消耗（元）
 * @param totalCost          服务累计消耗（元）
 * @param tier               本次实际生效的档位
 * @param degraded           是否发生了降级或退到离线引擎（要如实显示，不能假装）
 * @param contextInjected    本次注入模型的 token
 * @param contextRaw         不做上下文工程时的 token 估算
 * @param contextSavedPercent 上下文压缩比例
 */
public record UsageView(
        int promptTokens,
        int completionTokens,
        double cost,
        double totalCost,
        String tier,
        String model,
        String provider,
        boolean degraded,
        String routeReason,
        int contextInjected,
        int contextRaw,
        int contextSavedPercent) {
}
