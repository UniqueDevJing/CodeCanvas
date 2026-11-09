package com.codecanvas.guardrail;

/**
 * 一条护栏规则。规则是<b>确定性</b>的：不调模型、不依赖外部服务，
 * 因此判定结果可重复、可单测、几乎零耗时。
 *
 * <p>只有「修复」动作才允许调用模型，且由编排层显式发起——把判定与修复分开，
 * 是拦截链和普通 if/else 校验的本质区别。
 */
public interface GuardrailRule {

    String id();

    GuardrailResult.Phase phase();

    /** 数字小的先执行；廉价的规则排前面，硬拦截一命中就短路。 */
    default int order() {
        return 100;
    }

    /**
     * 规则是否适用于当前上下文。不适用的直接跳过，不产生假 PASS / 假 FALLBACK
     * ——例如断言阶段根本没有 HTML，就不该记一条「HTML 未生成」。
     */
    default boolean appliesTo(GuardrailContext context) {
        return true;
    }

    GuardrailResult evaluate(GuardrailContext context);
}
