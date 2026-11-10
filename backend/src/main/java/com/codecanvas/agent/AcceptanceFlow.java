package com.codecanvas.agent;

import com.codecanvas.acceptance.AcceptanceSpec;
import com.codecanvas.context.PromptLibrary;
import com.codecanvas.guardrail.GuardrailChain;
import com.codecanvas.guardrail.GuardrailContext;
import com.codecanvas.guardrail.GuardrailResult;
import com.codecanvas.guardrail.StructuredOutputGuardrail;
import com.codecanvas.llm.LlmGateway;
import com.codecanvas.llm.LlmPurpose;
import com.codecanvas.llm.LlmRequest;
import com.codecanvas.llm.RouteDecision;
import com.codecanvas.llm.TokenMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.function.Function;

/**
 * C4 验收断言生成流（T1/C1 从 AgentOrchestrator 拆出的组件）。
 * 从结构化需求（PRD）推导验收断言，作为「生成完成」的客观判据；只在首次生成/重建时生成。
 * P0-3 输出护栏：解析 → 字段级校验 → 带违规原因的定向修复 → 复校 → 有界重试 → 诚实降级。
 */
@Component
public class AcceptanceFlow {

    private static final Logger log = LoggerFactory.getLogger(AcceptanceFlow.class);

    private final LlmGateway gateway;

    @Value("${codecanvas.acceptance.enabled:true}")
    private boolean acceptanceEnabled;

    @Autowired(required = false)
    private StructuredOutputGuardrail structuredOutputGuardrail;

    @Autowired(required = false)
    private GuardrailChain guardrailChain;

    public AcceptanceFlow(LlmGateway gateway) {
        this.gateway = gateway;
    }

    public boolean enabled() {
        return acceptanceEnabled;
    }

    /** 生成验收断言；失败不阻断交付（诚实降级为空断言集） */
    public AcceptanceSpec generate(boolean firstTurn, boolean rebuild,
                                   String requirement, String prd,
                                   TokenMeter.RunMeter run, AgentListener listener) {
        AcceptanceSpec acceptance = AcceptanceSpec.empty();
        if (!acceptanceEnabled || !(firstTurn || rebuild)) {
            return acceptance;
        }
        try {
            RouteDecision accRoute = gateway.router().route(LlmPurpose.PRD, requirement, true, 0);
            LlmGateway.CallResult accCall = gateway.call(
                    LlmRequest.of(LlmPurpose.PRD, accRoute.model(), PromptLibrary.ACCEPTANCE_SYSTEM,
                            PromptLibrary.acceptanceUserPrompt(prd)),
                    accRoute, run);
            String rawAcceptance = accCall.response().text();

            if (structuredOutputGuardrail != null) {
                Function<String, String> repairer = prompt -> {
                    RouteDecision rr = gateway.router().route(LlmPurpose.PRD, prompt, true, 0);
                    return gateway.call(LlmRequest.of(LlmPurpose.PRD, rr.model(),
                                    StructuredOutputGuardrail.REPAIR_SYSTEM, prompt),
                            rr, run).response().text();
                };
                StructuredOutputGuardrail.Enforcement enforced =
                        structuredOutputGuardrail.enforce(rawAcceptance, repairer, listener);
                acceptance = enforced.spec();
                if (enforced.downgraded()) {
                    listener.onStatus("验收断言经 " + enforced.repairAttempts()
                            + " 次定向修复仍未通过字段契约，已如实降级为不纳入判据");
                }
            } else {
                acceptance = AcceptanceSpec.parse(rawAcceptance);
            }

            // 决策留痕复用 tool 事件：不新增 SSE 事件名，也自动进 RunTraceRecorder 的执行轨迹
            if (guardrailChain != null) {
                GuardrailChain.Verdict outVerdict = guardrailChain.evaluate(
                        GuardrailResult.Phase.OUTPUT,
                        GuardrailContext.output(rawAcceptance, null, acceptance, 0));
                listener.onToolCall("guardrail_output", outVerdict.toAuditMap(), outVerdict.summary());
            }

            if (!acceptance.isEmpty()) {
                listener.onStatus("已从需求推导出 " + acceptance.checks().size()
                        + " 条验收断言，生成后会逐条自动核对");
            }
        } catch (AgentOrchestrator.CancelledException cancelled) {
            throw cancelled;
        } catch (Exception e) {
            log.warn("验收断言生成失败（不阻断交付）: {}", e.getMessage());
        }
        return acceptance;
    }
}
