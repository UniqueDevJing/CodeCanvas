package com.codecanvas.agent;

import com.codecanvas.llm.LlmGateway;
import com.codecanvas.llm.LlmPurpose;
import com.codecanvas.llm.RouteDecision;
import com.codecanvas.llm.TokenMeter;
import com.codecanvas.common.TokenEstimator;
import com.codecanvas.context.ContextAssembler;
import com.codecanvas.project.Project;
import com.codecanvas.project.ProjectFile;
import com.codecanvas.project.VersionSnapshot;

/**
 * 用量与上下文收益口径（T1/C1 从 AgentOrchestrator 拆出的静态支撑）。
 * 实际注入量按真正发出去的提示词算，不做美化；对比基准是「完全不做上下文压缩」的体量。
 */
public final class UsageSupport {

    private UsageSupport() {
    }

    public static UsageView usage(TokenMeter.RunMeter run,
                                  TokenMeter meter,
                                  LlmGateway.CallResult call,
                                  RouteDecision route,
                                  int injectedTokens,
                                  int rawTokens) {
        TokenMeter.Snapshot runTotal = run.snapshot();
        TokenMeter.Snapshot global = meter.globalSnapshot();
        ContextAssembler.ContextReport report = ContextAssembler.report(injectedTokens, rawTokens);
        return new UsageView(
                call.response().inputTokens(),
                call.response().outputTokens(),
                runTotal.cost(),
                runTotal.baselineCost(),
                TokenMeter.savedPercent(runTotal.cost(), runTotal.baselineCost()),
                global.cost(),
                global.baselineCost(),
                TokenMeter.savedPercent(global.cost(), global.baselineCost()),
                call.tier().name(),
                call.response().model(),
                call.provider(),
                call.degraded(),
                route.reason(),
                report.injectedTokens(),
                report.rawTokens(),
                report.savedPercent());
    }

    /**
     * 估算「完全不做上下文压缩」时这一轮要喂给模型的全部内容，用来量化上下文工程的收益。
     * 口径：历史每一轮的需求原文 + 历史每一轮的模型输出（完整代码）+ 本轮需求。
     */
    public static int rawContextTokens(Project project, String requirement) {
        int raw = TokenEstimator.estimate(requirement);
        if (project.versions() != null) {
            for (VersionSnapshot version : project.versions()) {
                raw += TokenEstimator.estimate(version.requirement());
                if (version.files() == null) {
                    continue;
                }
                for (ProjectFile file : version.files()) {
                    raw += TokenEstimator.estimate(file.content());
                }
            }
        }
        return raw;
    }
}
