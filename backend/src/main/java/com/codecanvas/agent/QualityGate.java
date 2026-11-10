package com.codecanvas.agent;

import com.codecanvas.acceptance.AcceptanceEvaluator;
import com.codecanvas.acceptance.AcceptanceSpec;
import com.codecanvas.acceptance.SemanticScorer;
import com.codecanvas.agent.tool.AgentWorkspace;
import com.codecanvas.agent.tool.ToolRegistry;
import com.codecanvas.common.HtmlValidator;
import com.codecanvas.context.PromptLibrary;
import com.codecanvas.llm.BudgetPolicy;
import com.codecanvas.llm.LlmGateway;
import com.codecanvas.llm.LlmPurpose;
import com.codecanvas.llm.LlmRequest;
import com.codecanvas.llm.RouteDecision;
import com.codecanvas.llm.TaskBudget;
import com.codecanvas.llm.TokenMeter;
import com.codecanvas.verification.RuntimeReport;
import com.codecanvas.verification.RuntimeVerificationPolicy;
import com.codecanvas.verification.RuntimeVerifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 质量门（T1/C1 从 AgentOrchestrator 拆出的组件）：
 * 静态校验 + 运行时验证 + C4 验收断言逐条核对，未通过项统一并入问题清单；
 * 自检修复循环按预算决策的轮次上限回注问题 → 定点修复 → 重验，及时止损。
 */
@Component
public class QualityGate {

    private final LlmGateway gateway;
    private final BudgetPolicy budgetPolicy;
    private final RuntimeVerifier runtimeVerifier;
    private final RuntimeVerificationPolicy runtimePolicy;

    /** C-A：语义打分器（semantic-enabled=true 时注入；缺省 null → 语义断言诚实降级） */
    @Autowired(required = false)
    private SemanticScorer semanticScorer;

    public QualityGate(LlmGateway gateway,
                       BudgetPolicy budgetPolicy,
                       RuntimeVerifier runtimeVerifier,
                       RuntimeVerificationPolicy runtimePolicy) {
        this.gateway = gateway;
        this.budgetPolicy = budgetPolicy;
        this.runtimeVerifier = runtimeVerifier;
        this.runtimePolicy = runtimePolicy;
    }

    /** 质检报告 = 固定四维检查 + 验收断言逐条结果（C4/C-A） */
    public List<HtmlValidator.CheckResult> qualityReport(String html, AcceptanceSpec acceptance) {
        List<HtmlValidator.CheckResult> report = new ArrayList<>(HtmlValidator.report(html));
        report.addAll(AcceptanceEvaluator.evaluate(html, acceptance, semanticScorer));
        return List.copyOf(report);
    }

    /** 静态校验 + 运行时验证 + 验收断言核对，产出待修复问题清单 */
    public List<HtmlValidator.Issue> validate(ToolRegistry tools, AgentListener listener,
                                              AgentWorkspace workspace, AcceptanceSpec acceptance) {
        Map<String, Object> args = Map.of("path", GenerationFlow.MAIN_FILE);
        String result = tools.call("validate_html", args);
        listener.onToolCall("validate_html", args, result);
        List<HtmlValidator.Issue> issues =
                new ArrayList<>(HtmlValidator.validate(workspace.read(GenerationFlow.MAIN_FILE)));

        // 执行级验证：把生成的页面真在浏览器里跑一遍，把「能预览」变成「跑得过验收」。
        // 环境无浏览器时 verify 回 skipped 报告，不阻断交付，行为与改造前一致（仅静态校验）。
        if (runtimePolicy.enabled()) {
            RuntimeReport report = runtimeVerifier.verify(workspace.mainContent(), runtimePolicy.defaultSpec());
            listener.onToolCall("verify_runtime", Map.of("path", GenerationFlow.MAIN_FILE), report.summary());
            if (report.skipped()) {
                listener.onStatus("运行时验证不可用，已跳过，仅做静态校验");
            }
            issues.addAll(runtimePolicy.merge(List.of(), report));
        }

        // C4 验收断言：从需求推导的「完成判据」，逐条客观核对；未通过的并入问题清单，
        // 走同一条「回注 → 修复 → 重验」闭环——生成是否完成由断言说了算，而不是由模型自己说了算。
        if (acceptance != null && !acceptance.isEmpty()) {
            Map<String, Object> accArgs = Map.of("checks", acceptance.checks().size());
            List<HtmlValidator.CheckResult> accResults =
                    AcceptanceEvaluator.evaluate(workspace.read(GenerationFlow.MAIN_FILE), acceptance, semanticScorer);
            long passed = accResults.stream().filter(HtmlValidator.CheckResult::passed).count();
            listener.onToolCall("verify_acceptance", accArgs,
                    "验收断言 " + passed + "/" + accResults.size() + " 通过");
            issues.addAll(AcceptanceEvaluator.failing(accResults));
        }
        return issues;
    }

    /**
     * 自检修复闭环：把具体问题回注给模型定点修复，轮次由预算决策统一控制；
     * 预算耗尽及时止损（已交付当前最优版本）。
     *
     * @return 实际执行的修复轮次
     */
    public int heal(AgentListener listener, TokenMeter.RunMeter run, RouteDecision genRoute,
                    TaskBudget budget, BudgetPolicy.BudgetDecision budgetDecision,
                    String prd, AgentWorkspace workspace, ToolRegistry tools,
                    AcceptanceSpec acceptance, List<HtmlValidator.Issue> issues) {
        int healAttempts = 0;
        int allowedHeal = budgetDecision.allowedHealAttempts();
        while (!issues.isEmpty() && healAttempts < allowedHeal) {
            healAttempts++;
            String errorText = HtmlValidator.describe(issues);
            AgentOrchestrator.checkCancelled(listener);
            listener.onStage(AgentStage.HEAL, "发现一处问题，正在自动修复（第 " + healAttempts + " 次）");
            listener.onHeal(healAttempts, issues.get(0).message(), "已定位问题，正在做最小改动修复");

            LlmGateway.CallResult fixCall = gateway.call(
                    new LlmRequest(LlmPurpose.FIX, genRoute.model(), PromptLibrary.FIX_SYSTEM,
                            PromptLibrary.fixUserPrompt(prd, workspace.snapshot(), errorText), 0.2, 8192),
                    genRoute, run);

            String fixed = com.codecanvas.common.CodeExtractor.extractHtml(fixCall.response().text());
            if (fixed.isBlank() || !fixed.toLowerCase().contains("<html")) {
                listener.onStatus("这次修复没有成功，我保留当前版本");
                break;
            }
            workspace.write(GenerationFlow.MAIN_FILE, fixed);
            listener.onFile(GenerationFlow.MAIN_FILE, "update", GenerationFlow.byteLengthOf(fixed));
            issues.clear();
            issues.addAll(validate(tools, listener, workspace, acceptance));
            if (budgetPolicy.exceeded(budget, run.snapshot())) {
                listener.onStatus("已达成本/Token 预算上限，停止自检修复（已交付当前最优版本）");
                break;
            }
        }
        return healAttempts;
    }
}
