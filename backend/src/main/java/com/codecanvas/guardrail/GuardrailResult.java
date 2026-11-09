package com.codecanvas.guardrail;

import java.util.Map;

/**
 * 单条护栏规则的判定结果（P0-3）。
 *
 * <p>四种结局必须区分清楚，否则「降级」会被当成「通过」写进轨迹，是数据可信度问题：
 * <ul>
 *   <li>{@link Outcome#PASS}：确实检查过且通过；</li>
 *   <li>{@link Outcome#REJECT}：硬拦截，不应继续往下走；</li>
 *   <li>{@link Outcome#REPAIR}：可修复，把具体原因回注给模型做<b>定向</b>修复；</li>
 *   <li>{@link Outcome#FALLBACK}：规则自身异常或能力未启用，如实标注，绝不假装通过。</li>
 * </ul>
 */
public record GuardrailResult(
        String ruleId,
        Phase phase,
        Outcome outcome,
        String reason,
        Map<String, Object> details,
        long latencyMs) {

    /** 两级拦截链：输入侧拦需求，输出侧拦产物。 */
    public enum Phase {
        INPUT, OUTPUT
    }

    public enum Outcome {
        PASS, REJECT, REPAIR, FALLBACK
    }

    /** 耗时由执行器统一回填，规则本身不关心计时。 */
    public GuardrailResult withLatency(long ms) {
        return new GuardrailResult(ruleId, phase, outcome, reason, details, ms);
    }

    public static GuardrailResult pass(String ruleId, Phase phase, String reason) {
        return new GuardrailResult(ruleId, phase, Outcome.PASS, reason, Map.of(), 0);
    }

    public static GuardrailResult reject(String ruleId, Phase phase, String reason) {
        return new GuardrailResult(ruleId, phase, Outcome.REJECT, reason, Map.of(), 0);
    }

    public static GuardrailResult repair(String ruleId, Phase phase, String reason,
                                         Map<String, Object> details) {
        return new GuardrailResult(ruleId, phase, Outcome.REPAIR, reason,
                details == null ? Map.of() : details, 0);
    }

    public static GuardrailResult fallback(String ruleId, Phase phase, String reason) {
        return new GuardrailResult(ruleId, phase, Outcome.FALLBACK, reason, Map.of(), 0);
    }
}
