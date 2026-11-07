package com.codecanvas.agent;

/**
 * 配图预算（W2.6）：给"生成图"这条烧钱路径加一个**每次运行的上限**。
 *
 * <p>为什么必须单独有预算而不是只靠网关计价：
 * 网关只负责"这次花了多少"，不负责"这次最多能花多少"。Agent 在自愈回路里可能反复重试，
 * 没有上限就会出现"改一个 CSS 顺手生成 12 张图"的情况。
 *
 * <p>语义是**预留-结算**：调用前按估值预留，成功后再按实际成本对齐，
 * 失败则把预留退回——这样并发/重试都不会把预算算错。
 */
public final class ImageBudget {

    private final double maxUsd;
    private final double estimateUsd;
    private double reservedUsd;
    private double spentUsd;
    private int generated;
    private int degradedByBudget;

    public ImageBudget(double maxUsd, double estimateUsd) {
        this.maxUsd = Math.max(0.0, maxUsd);
        this.estimateUsd = Math.max(0.0, estimateUsd);
    }

    /** 从环境变量构造：默认每次运行 0.02 美元、单张估值 0.003 美元。 */
    public static ImageBudget fromEnv() {
        return new ImageBudget(
                readDouble("CODECANVAS_IMAGE_MAX_USD", 0.02),
                readDouble("CODECANVAS_IMAGE_ESTIMATE_USD", 0.003));
    }

    private static double readDouble(String key, double fallback) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback; // 配错了也别让主链路崩，退回默认值
        }
    }

    /** 预留一次生成额度；返回 false 表示预算已耗尽，调用方必须降级。 */
    public boolean tryReserve() {
        if (spentUsd + reservedUsd + estimateUsd > maxUsd) {
            degradedByBudget++;
            return false;
        }
        reservedUsd += estimateUsd;
        return true;
    }

    /** 成功后按实际成本结算（差额退回/补记）；失败时用 settle(0) 退回预留。 */
    public void settle(double actualUsd) {
        reservedUsd = Math.max(0.0, reservedUsd - estimateUsd);
        if (actualUsd > 0) {
            spentUsd += actualUsd;
            generated++;
        }
    }

    public boolean exhausted() {
        return spentUsd + reservedUsd + estimateUsd > maxUsd;
    }

    public double maxUsd() {
        return maxUsd;
    }

    public double spentUsd() {
        return spentUsd;
    }

    public int generated() {
        return generated;
    }

    public int degradedByBudget() {
        return degradedByBudget;
    }

    /** 给 SSE/状态行用的一句话摘要。 */
    public String summary() {
        return String.format("配图预算 $%.4f/$%.4f，已出图 %d 张", spentUsd, maxUsd, generated);
    }
}