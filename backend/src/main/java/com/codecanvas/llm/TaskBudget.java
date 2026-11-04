package com.codecanvas.llm;

/**
 * 单次任务的预算约束（三元组）。任一维度为 null 表示该项不限制。
 *
 * <p>来自请求方下发（{@code AgentRunRequest.budget()}）；若请求未带，则回落到
 * {@link BudgetPolicy} 的默认预算（启用且有默认值时）。所有维度为 null 等价于"无限预算"。
 */
public record TaskBudget(Double maxCostYuan, Long maxLatencyMs, Integer maxTokens) {

    /** 无限预算：所有维度不限制。 */
    public static TaskBudget unbounded() {
        return new TaskBudget(null, null, null);
    }

    public boolean hasCost() {
        return maxCostYuan != null && maxCostYuan > 0;
    }

    public boolean hasLatency() {
        return maxLatencyMs != null && maxLatencyMs > 0;
    }

    public boolean hasTokens() {
        return maxTokens != null && maxTokens > 0;
    }

    /** 是否至少在一个维度上有限制。 */
    public boolean isBounded() {
        return hasCost() || hasLatency() || hasTokens();
    }
}
