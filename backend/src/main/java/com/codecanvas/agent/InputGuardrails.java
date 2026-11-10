package com.codecanvas.agent;

import com.codecanvas.acceptance.AcceptanceSpec;
import com.codecanvas.guardrail.GuardrailChain;
import com.codecanvas.guardrail.GuardrailContext;
import com.codecanvas.guardrail.GuardrailResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 编排层两级拦截链（T1/C1 从 AgentOrchestrator 拆出的组件）。
 * P0-3：输入护栏在用户发起的运行入口短路拦截；产物侧护栏只在首次校验留痕一次，
 * 避免每轮修复都刷一条重复决策。
 */
@Component
public class InputGuardrails {

    @Autowired(required = false)
    private GuardrailChain guardrailChain;

    /**
     * 输入护栏：只对用户发起的需求生效——RUNTIME_ERROR 是系统内部触发的修复运行，
     * message 本来就是空的，不该被「空需求」规则误拦。
     *
     * @return true = 放行；false = 已拦截（onError 已回调）
     */
    public boolean checkInput(AgentRunRequest request, AgentListener listener) {
        boolean userInitiated = request.trigger() != AgentRunRequest.Trigger.RUNTIME_ERROR;
        if (guardrailChain == null || !userInitiated) {
            return true;
        }
        GuardrailChain.Verdict inVerdict = guardrailChain.evaluate(
                GuardrailResult.Phase.INPUT, GuardrailContext.input(safe(request.message())));
        listener.onToolCall("guardrail_input", inVerdict.toAuditMap(), inVerdict.summary());
        if (inVerdict.blocked()) {
            String reason = inVerdict.results().stream()
                    .filter(r -> r.outcome() == GuardrailResult.Outcome.REJECT)
                    .map(GuardrailResult::reason)
                    .findFirst()
                    .orElse("输入未通过护栏");
            listener.onError("输入未通过护栏：" + reason);
            return false;
        }
        if (inVerdict.degraded()) {
            listener.onStatus("输入护栏有规则降级（已留痕），本次按原有流程继续");
        }
        return true;
    }

    /** 产物侧输出护栏留痕（与自检修复判据同源：HtmlValidator） */
    public void auditArtifact(AgentListener listener, String html, AcceptanceSpec acceptance) {
        if (guardrailChain == null) {
            return;
        }
        GuardrailChain.Verdict artifactVerdict = guardrailChain.evaluate(
                GuardrailResult.Phase.OUTPUT,
                GuardrailContext.output(null, html, acceptance, 0));
        listener.onToolCall("guardrail_artifact", artifactVerdict.toAuditMap(),
                artifactVerdict.summary());
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }
}
