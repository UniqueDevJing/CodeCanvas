package com.codecanvas.agent;

import com.codecanvas.acceptance.AcceptanceSpec;
import com.codecanvas.agent.tool.AgentWorkspace;
import com.codecanvas.common.HtmlValidator;
import com.codecanvas.llm.LlmGateway;
import com.codecanvas.llm.LlmPurpose;
import com.codecanvas.llm.LlmRequest;
import com.codecanvas.llm.TokenMeter;
import com.codecanvas.project.Project;
import com.codecanvas.project.ProjectService;
import com.codecanvas.agent.trace.RunTrace;
import com.codecanvas.agent.trace.RunTraceRecorder;
import com.codecanvas.agent.trace.RunTraceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agent 编排核心（T1/C1 瘦身版）：职责收窄为「五阶段流程编排」——
 * 需求澄清 → 任务规划与代码生成 → 配图绑定 → 质量校验与自检修复 → 交付。
 * 具体策略已拆为可独立测试的组件：
 * {@link InputGuardrails} 两级拦截链入口、{@link AcceptanceFlow} C4 验收断言、
 * {@link GenerationFlow} 预算调度与 REACT/流水线生成、{@link ImageBindingFlow} 配图槽位绑定、
 * {@link QualityGate} 质量门与自检修复闭环、{@link DeliveryFlow} 版本落库与用量台账。
 * 两个关键设计：
 * <ol>
 *   <li><b>自检闭环</b>：生成结果必须先过静态校验才有资格交付；不通过就把具体问题回注给模型定点修复，
 *       轮次由预算策略统一控制。「生成的代码跑不起来」从体验问题变成可度量的恢复率指标。</li>
 *   <li><b>上下文工程</b>：多轮迭代不把历史对话和全部历史代码塞进提示词，而是注入
 *       结构化需求 + 历史变更摘要 + 相关文件。</li>
 * </ol>
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final ProjectService projectService;
    private final LlmGateway gateway;
    private final TokenMeter meter;
    private final RunTraceStore traceStore;
    private final AcceptanceFlow acceptanceFlow;
    private final GenerationFlow generationFlow;
    private final ImageBindingFlow imageBindingFlow;
    private final QualityGate qualityGate;
    private final DeliveryFlow deliveryFlow;
    private final InputGuardrails guardrails;

    public AgentOrchestrator(ProjectService projectService,
                             LlmGateway gateway,
                             TokenMeter meter,
                             RunTraceStore traceStore,
                             AcceptanceFlow acceptanceFlow,
                             GenerationFlow generationFlow,
                             ImageBindingFlow imageBindingFlow,
                             QualityGate qualityGate,
                             DeliveryFlow deliveryFlow,
                             InputGuardrails guardrails) {
        this.projectService = projectService;
        this.gateway = gateway;
        this.meter = meter;
        this.traceStore = traceStore;
        this.acceptanceFlow = acceptanceFlow;
        this.generationFlow = generationFlow;
        this.imageBindingFlow = imageBindingFlow;
        this.qualityGate = qualityGate;
        this.deliveryFlow = deliveryFlow;
        this.guardrails = guardrails;
    }

    public void run(AgentRunRequest request, AgentListener listener) {
        if (gateway.isOfflineFallback()) {
            listener.onStatus("⚠️ 当前离线演示模式（" + gateway.currentModeLabel()
                    + "）：未检测到模型 API Key，生成的是内置模板页面，不会按你的需求定制。"
                    + "配置 CODECANVAS_OPENAI_KEY 后重启即可使用真实模型。");
        }
        // C3 执行轨迹：每次运行一个 runId，录制全部回调后落盘，供回放与 diff
        RunTraceRecorder recorder = new RunTraceRecorder(RunTrace.newId(), listener);
        recorder.begin(String.valueOf(request.trigger()),
                request.strategy() == null ? "PIPELINE" : request.strategy().name(),
                request.message());
        TokenMeter.RunMeter run = meter.newRunMeter();
        // P1-6：按运行维度统计语义缓存命中并如实留痕（同项目串行生成，差值法可靠）。
        long cacheHitsBefore = gateway.cacheHits();
        try {
            doRun(request, recorder, run);
            long cacheDelta = gateway.cacheHits() - cacheHitsBefore;
            if (cacheDelta > 0) {
                recorder.onStatus("语义缓存命中 " + cacheDelta
                        + " 次：复用需求理解结果，本次未产生模型调用");
            }
            traceStore.save(recorder.finish(null));
        } catch (CancelledException cancelled) {
            throw cancelled;
        } catch (Exception e) {
            log.error("Agent 运行失败", e);
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            traceStore.save(recorder.finish(message));
            listener.onError(message);
        }
    }

    private void doRun(AgentRunRequest request, AgentListener listener, TokenMeter.RunMeter run) {
        // ============ P0-3 输入护栏 ============
        if (!guardrails.checkInput(request, listener)) {
            return;
        }

        // ============ 会话 ============
        Project project;
        boolean created = false;
        if (request.projectId() == null || request.projectId().isBlank()) {
            project = projectService.create(safe(request.message()));
            created = true;
        } else {
            project = projectService.require(request.projectId());
        }
        listener.onSession(project.id(), created);

        boolean runtimeErrorRun = request.trigger() == AgentRunRequest.Trigger.RUNTIME_ERROR;
        boolean firstTurn = project.isEmpty();
        String requirement = runtimeErrorRun ? "修复页面运行时报错" : safe(request.message());
        // 用户在已有项目里重新完整描述了一遍应用（「做一个XX」）→ 意图是重建而不是打补丁。
        boolean rebuild = !firstTurn && !runtimeErrorRun && PromptSupport.isRebuildIntent(requirement);

        // ============ 1. 需求澄清 ============
        checkCancelled(listener);
        listener.onStage(AgentStage.CLARIFY, firstTurn
                ? "正在理解你的需求"
                : (rebuild ? "你要的是一个新应用，正在重新理解需求"
                           : "沿用之前确认过的需求，不重复确认"));
        if (rebuild) {
            listener.onStatus("看起来你在描述一个全新的应用，我会重新做一个，而不是在原来页面上打补丁");
        }
        String prd = project.prd();
        if (firstTurn || rebuild || prd == null || prd.isBlank()) {
            var prdRoute = gateway.router().route(LlmPurpose.PRD, requirement, true, 0);
            listener.onStatus("正在整理需求");
            var prdCall = gateway.call(
                    LlmRequest.of(LlmPurpose.PRD, prdRoute.model(),
                            com.codecanvas.context.PromptLibrary.PRD_SYSTEM,
                            com.codecanvas.context.PromptLibrary.prdUserPrompt(requirement)),
                    prdRoute, run);
            prd = prdCall.response().text().trim();
            project = projectService.updatePrd(project, prd);
            listener.onTokens(UsageSupport.usage(run, meter, prdCall, prdRoute, 0, 0));
        }

        // ============ C4 验收断言 ============
        AcceptanceSpec acceptance = acceptanceFlow.generate(firstTurn, rebuild, requirement, prd, run, listener);

        // ============ 2. 任务规划 + 3. 代码生成（REACT/流水线 + 配图绑定）============
        LlmPurpose purpose = runtimeErrorRun ? LlmPurpose.FIX
                : ((firstTurn || rebuild) ? LlmPurpose.CODE : LlmPurpose.MODIFY);
        GenerationFlow.Outcome gen = generationFlow.run(request, listener, run, project, prd,
                requirement, purpose, firstTurn, rebuild);
        AgentWorkspace workspace = gen.workspace();
        imageBindingFlow.bind(workspace, gen.tools(), listener);

        // ============ 4. 质量校验 + 5. 自检修复 ============
        checkCancelled(listener);
        listener.onStage(AgentStage.VALIDATE, "正在检查页面能不能正常打开");
        QualityGate.GateReport gate = qualityGate.validate(gen.tools(), listener,
                workspace, acceptance);
        java.util.List<HtmlValidator.Issue> issues = gate.issues();
        guardrails.auditArtifact(listener, workspace.read(GenerationFlow.MAIN_FILE), acceptance);

        int healAttempts = qualityGate.heal(listener, run, gen.genRoute(), gen.budget(),
                gen.budgetDecision(), prd, workspace, gen.tools(), acceptance, issues);
        boolean recovered = issues.isEmpty();
        if (!recovered) {
            listener.onStatus("还有 " + issues.size() + " 处问题没能自动修好，页面可以打开，你可以继续告诉我哪里不对");
        }

        // ============ 提交版本 + 沙箱预览 + 用量台账 ============
        DeliveryFlow.Delivery delivery = deliveryFlow.deliver(request, listener, run, project,
                purpose, requirement, prd, gen, workspace, healAttempts, recovered, acceptance,
                gate.runtimeStatus());
        listener.onResult(delivery.result());
    }

    /**
     * 客户端已断开（公开给控制器识别）：属于正常终止，不该按错误上报。
     */
    public static final class CancelledException extends RuntimeException {
        public CancelledException() {
            super("client-cancelled");
        }
    }

    public static void checkCancelled(AgentListener listener) {
        if (listener.isCancelled()) {
            throw new CancelledException();
        }
    }

    private static String safe(String text) {
        return text == null ? "" : text.trim();
    }
}
