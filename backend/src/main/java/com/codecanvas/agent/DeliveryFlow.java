package com.codecanvas.agent;

import com.codecanvas.acceptance.AcceptanceSpec;
import com.codecanvas.agent.tool.AgentWorkspace;
import com.codecanvas.common.TokenEstimator;
import com.codecanvas.context.ContextAssembler;
import com.codecanvas.llm.LlmGateway;
import com.codecanvas.llm.LlmPurpose;
import com.codecanvas.llm.TokenMeter;
import com.codecanvas.project.Project;
import com.codecanvas.project.ProjectService;
import com.codecanvas.sandbox.SandboxExecutorResolver;
import com.codecanvas.sandbox.SandboxedPreview;
import org.springframework.stereotype.Component;

/**
 * 交付流（T1/C1 从 AgentOrchestrator 拆出的组件）：
 * 版本落库（含成本台账）→ 沙箱预览 → 上下文收益口径 → 用量视图 → AgentResult。
 */
@Component
public class DeliveryFlow {

    private final ProjectService projectService;
    private final SandboxExecutorResolver sandbox;
    private final TokenMeter meter;
    private final QualityGate qualityGate;

    public DeliveryFlow(ProjectService projectService,
                        SandboxExecutorResolver sandbox,
                        TokenMeter meter,
                        QualityGate qualityGate) {
        this.projectService = projectService;
        this.sandbox = sandbox;
        this.meter = meter;
        this.qualityGate = qualityGate;
    }

    /** 交付产物：落库后的项目（含新版本）与最终 AgentResult */
    public record Delivery(Project project, AgentResult result) {
    }

    public Delivery deliver(AgentRunRequest request, AgentListener listener, TokenMeter.RunMeter run,
                            Project project, LlmPurpose purpose, String requirement, String prd,
                            GenerationFlow.Outcome gen, AgentWorkspace workspace,
                            int healAttempts, boolean recovered, AcceptanceSpec acceptance) {
        String summary = PromptSupport.summarize(purpose, requirement, prd, recovered, healAttempts, request);
        TokenMeter.Snapshot snapshot = run.snapshot();
        project = projectService.saveVersion(project, workspace.snapshot(), summary, requirement,
                healAttempts, snapshot.cost(), snapshot.inputTokens(), snapshot.outputTokens(),
                gen.generateCall().response().model(), gen.generateCall().tier().label());

        // 沙箱预览
        SandboxedPreview preview = sandbox.prepare(workspace.mainContent(), project.id(),
                project.currentVersionId());

        // 实际注入量按真正发出去的提示词算，不做美化
        ContextAssembler.ContextReport contextReport = ContextAssembler.report(
                TokenEstimator.estimate(gen.userPrompt()), gen.rawContextTokens());
        UsageView usageView = UsageSupport.usage(run, meter, gen.generateCall(), gen.genRoute(),
                contextReport.injectedTokens(), contextReport.rawTokens());

        listener.onTokens(usageView);
        AgentResult result = new AgentResult(
                project.id(),
                project.currentVersionId(),
                project.currentVersion() == null ? "" : project.currentVersion().label(),
                summary,
                workspace.snapshot(),
                preview.html(),
                preview.mode(),
                preview.note(),
                healAttempts,
                recovered,
                gen.generateCall().response().model(),
                gen.generateCall().tier().label(),
                gen.tools().trace(),
                // 质检报告按最终交付的内容生成，而不是按中间某次尝试；
                // C4 验收断言的核对结果一并进入报告（前端质检面板直接渲染）
                qualityGate.qualityReport(workspace.mainContent(), acceptance),
                usageView);
        return new Delivery(project, result);
    }
}
