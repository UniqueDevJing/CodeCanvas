package com.codecanvas.llm;

/**
 * 路由决策结果。带上 reason 是为了让"为什么这次用便宜模型"这件事在界面上和面试里都说得清。
 *
 * @param tier   选中的档位
 * @param model  目标模型名
 * @param score  复杂度得分
 * @param reason 决策依据（人类可读）
 */
public record RouteDecision(ModelTier tier, String model, int score, String reason) {
}
