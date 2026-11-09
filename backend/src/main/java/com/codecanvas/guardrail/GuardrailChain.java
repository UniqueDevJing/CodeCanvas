package com.codecanvas.guardrail;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * 两级拦截链执行器（P0-3）。
 *
 * <p>把原先散落在 5 个解析入口、2 套重试循环里的校验逻辑收口成一条可观测的链：
 * 按阶段过滤规则 → 按 order 顺序执行 → REJECT 立即短路 → 汇总成一份判定留痕。
 *
 * <p><b>fail-closed 的取舍</b>：规则自身抛异常时记 {@link GuardrailResult.Outcome#FALLBACK}
 * 并置 {@code degraded=true}，而不是静默放行，也不是直接拒绝生成。
 * 医疗分诊场景应该直接 block（triagent 的 applyAfterAdvices 就是这么做的）；
 * 代码生成场景里，护栏本身故障就让用户一个页面都拿不到，代价更高。
 * 所以这里选择「如实降级 + 强制留痕」，由调用方按场景决定要不要升级为硬拦截。
 */
@Service
public class GuardrailChain {

    private final List<GuardrailRule> rules;

    public GuardrailChain(List<GuardrailRule> rules) {
        this.rules = rules == null ? List.of() : rules.stream()
                .sorted(Comparator.comparingInt(GuardrailRule::order))
                .toList();
    }

    public Verdict evaluate(GuardrailResult.Phase phase, GuardrailContext context) {
        long chainStart = System.nanoTime();
        List<GuardrailResult> results = new ArrayList<>();
        boolean blocked = false;
        boolean degraded = false;

        for (GuardrailRule rule : rules) {
            if (rule.phase() != phase || !rule.appliesTo(context)) {
                continue;
            }
            long started = System.nanoTime();
            GuardrailResult result;
            try {
                result = rule.evaluate(context);
                if (result == null) {
                    result = GuardrailResult.fallback(rule.id(), phase, "规则返回了空判定");
                    degraded = true;
                }
            } catch (Exception e) {
                result = GuardrailResult.fallback(rule.id(), phase,
                        "规则执行异常，已如实降级: " + e.getMessage());
                degraded = true;
            }
            results.add(result.withLatency(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)));
            if (result.outcome() == GuardrailResult.Outcome.REJECT) {
                blocked = true;
                break; // 硬拦截后不再跑后续规则，避免无意义开销与二次副作用
            }
        }
        return new Verdict(phase, List.copyOf(results), blocked, degraded,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - chainStart));
    }

    /**
     * 一次链路判定的聚合结果。
     *
     * <p>{@link #toAuditMap()} 直接作为 {@code AgentListener.onToolCall} 的 args，
     * 复用已在契约快照里的 {@code tool} 事件 —— 不新增 SSE 事件名，也自动进了
     * RunTraceRecorder 的执行轨迹，时间旅行面板能回放每一次护栏决策。
     */
    public record Verdict(
            GuardrailResult.Phase phase,
            List<GuardrailResult> results,
            boolean blocked,
            boolean degraded,
            long latencyMs) {

        public List<GuardrailResult> repairs() {
            return results.stream()
                    .filter(r -> r.outcome() == GuardrailResult.Outcome.REPAIR)
                    .toList();
        }

        public boolean hasFallback() {
            return results.stream().anyMatch(r -> r.outcome() == GuardrailResult.Outcome.FALLBACK);
        }

        public long count(GuardrailResult.Outcome outcome) {
            return results.stream().filter(r -> r.outcome() == outcome).count();
        }

        public String summary() {
            return (phase == GuardrailResult.Phase.INPUT ? "输入护栏" : "输出护栏") + "："
                    + count(GuardrailResult.Outcome.PASS) + " 通过 / "
                    + count(GuardrailResult.Outcome.REPAIR) + " 待修复 / "
                    + count(GuardrailResult.Outcome.REJECT) + " 拦截 / "
                    + count(GuardrailResult.Outcome.FALLBACK) + " 降级（"
                    + latencyMs + "ms）";
        }

        public Map<String, Object> toAuditMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("phase", phase.name().toLowerCase());
            out.put("blocked", blocked);
            out.put("degraded", degraded);
            out.put("latencyMs", latencyMs);
            out.put("summary", summary());
            out.put("rules", results.stream().map(r -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", r.ruleId());
                row.put("outcome", r.outcome().name());
                row.put("reason", r.reason());
                row.put("latencyMs", r.latencyMs());
                return row;
            }).toList());
            return out;
        }
    }
}
