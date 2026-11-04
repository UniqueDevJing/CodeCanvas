package com.codecanvas.llm;

import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 预算感知调度：把 TokenMeter 的"事后计量"升级为"准入控制"。
 *
 * <p>职责：在规划阶段根据任务预算决定
 * <ol>
 *   <li><b>生成档位</b>：路由本想走的档位若在预算内就保留（保质量），超了就逐级降级
 *       （旗舰 → 标准 → 轻量）到第一个负担得起的档位；</li>
 *   <li><b>自检修复轮次</b>：预算吃紧时自动收减 HEAL 次数（修复调用规模与生成相近，
 *       按"剩余预算 / 单次成本"估算能撑几轮），避免"为修一个 bug 把整单预算烧光"；</li>
 *   <li><b>超预算处置</b>：连最便宜档的预估成本都超，则标记 overBudget（降级到最便宜档 +
 *       关闭修复），并计入计数器供成本台账展示。</li>
 * </ol>
 *
 * <p>延迟预算目前作为"约束被记录"的维度（用于台账/审计），成本与 token 为强制约束；
 * 预处理阶段无法精确估计 LLM 延迟，故不硬拦截，只据档位/输出规模给出提示。
 *
 * <p><b>向后兼容</b>：预算为 null 或全维度 unset 时，行为与改造前完全一致
 * （按路由原始档位、满额 HEAL）。
 */
@Component
public class BudgetPolicy {

    private final TokenMeter meter;
    private final ModelRouter router;
    private final boolean enabled;
    private final double defaultMaxCostYuan;
    private final long defaultMaxLatencyMs;
    private final int defaultMaxTokens;
    private final int maxHealAttempts;

    private final AtomicLong overBudgetTasks = new AtomicLong();

    public BudgetPolicy(TokenMeter meter,
                        ModelRouter router,
                        @Value("${codecanvas.budget.enabled:true}") boolean enabled,
                        @Value("${codecanvas.budget.default-max-cost-yuan:0}") double defaultMaxCostYuan,
                        @Value("${codecanvas.budget.default-max-latency-ms:0}") long defaultMaxLatencyMs,
                        @Value("${codecanvas.budget.default-max-tokens:0}") int defaultMaxTokens,
                        @Value("${codecanvas.budget.max-heal-attempts:3}") int maxHealAttempts) {
        this.meter = meter;
        this.router = router;
        this.enabled = enabled;
        this.defaultMaxCostYuan = defaultMaxCostYuan;
        this.defaultMaxLatencyMs = defaultMaxLatencyMs;
        this.defaultMaxTokens = defaultMaxTokens;
        this.maxHealAttempts = Math.max(0, maxHealAttempts);
    }

    /** 解析最终生效的预算：请求带则用请求，否则回落默认（启用且有默认值时）。 */
    public TaskBudget resolve(TaskBudget requestBudget) {
        if (requestBudget != null && requestBudget.isBounded()) {
            return requestBudget;
        }
        if (enabled && (defaultMaxCostYuan > 0 || defaultMaxLatencyMs > 0 || defaultMaxTokens > 0)) {
            return new TaskBudget(
                    defaultMaxCostYuan > 0 ? defaultMaxCostYuan : null,
                    defaultMaxLatencyMs > 0 ? defaultMaxLatencyMs : null,
                    defaultMaxTokens > 0 ? defaultMaxTokens : null);
        }
        return null;
    }

    /**
     * 规划阶段的预算决策。
     *
     * @param route               原始路由决策（档位/模型/理由）
     * @param budget              生效预算（null = 无限）
     * @param promptTokens        本次提示词预估 token 数
     * @param plannedOutputTokens 计划生成上限 token 数（用于成本预估）
     */
    public BudgetDecision decide(RouteDecision route, TaskBudget budget, int promptTokens, int plannedOutputTokens) {
        if (budget == null || !budget.hasCost()) {
            return new BudgetDecision(route, false, maxHealAttempts,
                    "无成本预算，按路由原始档位（" + route.tier().label() + "）");
        }
        double budgetCost = budget.maxCostYuan();
        ModelTier desired = route.tier();

        // 路由想要的档位若负担得起就保留（保质量），否则逐级降级到第一个负担得起的档位
        ModelTier chosen = desired;
        while (meter.estimateCost(chosen, promptTokens, plannedOutputTokens) > budgetCost
                && chosen != chosen.degrade()) {
            chosen = chosen.degrade();
        }
        double chosenCost = meter.estimateCost(chosen, promptTokens, plannedOutputTokens);

        // 连最便宜档都超：标记超预算（降级到最便宜档 + 关闭修复）
        boolean overBudget = meter.estimateCost(ModelTier.LIGHT, promptTokens, plannedOutputTokens) > budgetCost;
        if (overBudget) {
            overBudgetTasks.incrementAndGet();
        }

        int allowedHeal;
        if (overBudget) {
            allowedHeal = 0;
        } else {
            double remaining = budgetCost - chosenCost;
            double perHeal = chosenCost; // 修复调用规模与生成相近
            long fits = perHeal > 0 ? (long) Math.floor(remaining / perHeal) : maxHealAttempts;
            allowedHeal = (int) Math.max(0, Math.min(fits, maxHealAttempts));
        }

        RouteDecision adjusted = new RouteDecision(chosen, router.modelOf(chosen), route.score(), route.reason());
        String reason = String.format(
                "成本预算 %.4f 元 → 选 %s 档（预估 %.4f 元），允许修复 %d 轮%s",
                budgetCost, chosen.label(), chosenCost, allowedHeal,
                overBudget ? "（超预算，已降级并关闭修复）" : "");
        return new BudgetDecision(adjusted, overBudget, allowedHeal, reason);
    }

    /**
     * 运行中是否突破预算：成本或 token 任一维度越界即视为超预算。
     * 用于自检修复循环里及时止损，避免为修一个 bug 把整单预算烧光。
     */
    public boolean exceeded(TaskBudget budget, TokenMeter.Snapshot run) {
        if (budget == null) {
            return false;
        }
        if (budget.hasCost() && run.cost() > budget.maxCostYuan()) {
            return true;
        }
        if (budget.hasTokens()
                && (run.inputTokens() + run.outputTokens()) > budget.maxTokens()) {
            return true;
        }
        return false;
    }

    /** 超预算任务计数（含已降级处理的），供成本台账展示"预算满足率"。 */
    public long overBudgetCount() {
        return overBudgetTasks.get();
    }

    /** 单次预算决策的结果。 */
    public record BudgetDecision(RouteDecision route, boolean overBudget, int allowedHealAttempts, String reason) {
        public ModelTier tier() {
            return route.tier();
        }

        public String model() {
            return route.model();
        }
    }
}
