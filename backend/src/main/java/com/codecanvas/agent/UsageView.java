package com.codecanvas.agent;

/**
 * 推给前端的用量与路由信息。
 *
 * <p>成本给两组：实际花费与「同一批 token 全走旗舰档」的反事实花费。
 * 两个数一起给，前端才能显示"省了多少"，而不是只报一个好看的实际值。
 *
 * @param cost               本轮实际消耗
 * @param baselineCost       本轮若全部按旗舰档单价计费的花费
 * @param savedPercent       本轮节省比例（基准为 0 时为 0，不做兜底修饰）
 * @param totalCost          服务累计实际消耗
 * @param totalBaselineCost  服务累计的反事实花费
 * @param totalSavedPercent  服务累计节省比例
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
        double baselineCost,
        double savedPercent,
        double totalCost,
        double totalBaselineCost,
        double totalSavedPercent,
        String tier,
        String model,
        String provider,
        boolean degraded,
        String routeReason,
        int contextInjected,
        int contextRaw,
        int contextSavedPercent) {
}
