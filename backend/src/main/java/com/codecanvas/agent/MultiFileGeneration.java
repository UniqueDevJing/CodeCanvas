package com.codecanvas.agent;

import com.codecanvas.agent.tool.AgentWorkspace;
import com.codecanvas.context.PromptLibrary;
import com.codecanvas.llm.BudgetPolicy;
import com.codecanvas.llm.LlmGateway;
import com.codecanvas.llm.LlmPurpose;
import com.codecanvas.llm.LlmRequest;
import com.codecanvas.llm.RouteDecision;
import com.codecanvas.llm.TaskBudget;
import com.codecanvas.llm.TokenMeter;
import com.codecanvas.project.Project;
import com.codecanvas.project.model.FileChangeSet;
import com.codecanvas.project.model.ProjectPlan;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 多文件生成流（C3）：规划优先的多文件创建与变更集驱动的多文件修改。
 *
 * <p>两条路径任何一步解析失败都返回 null，由 {@link GenerationFlow} 降级回
 * 单文件流水线——多文件是增强，不是单例路径的前置依赖。
 */
@Component
public class MultiFileGeneration {

    /** 规划调用的输出上限：8 个文件 × purpose 说明，远小于代码生成 */
    private static final int PLAN_MAX_TOKENS = 2048;

    private final LlmGateway gateway;
    private final BudgetPolicy budgetPolicy;

    public MultiFileGeneration(LlmGateway gateway, BudgetPolicy budgetPolicy) {
        this.gateway = gateway;
        this.budgetPolicy = budgetPolicy;
    }

    /** 规划生成结果：entryCall 取入口文件那次调用（模型/档位口径沿用原单文件语义）。 */
    public record PlanOutcome(LlmGateway.CallResult entryCall, ProjectPlan plan, int fileCount) {
    }

    /** 变更集修改结果。 */
    public record ModifyOutcome(LlmGateway.CallResult call, FileChangeSet.ApplyResult apply) {
    }

    /**
     * 首轮 CODE：先出规划，再逐文件流式生成。
     *
     * @return 成功返回结果；规划失败/文件数超限/入口未产出 返回 null（降级）
     */
    public PlanOutcome generateByPlan(Project project, String prd, String requirement,
                                      RouteDecision route, TaskBudget budget,
                                      AgentWorkspace workspace,
                                      AgentListener listener, TokenMeter.RunMeter run) {
        LlmGateway.CallResult planCall = gateway.call(
                new LlmRequest(LlmPurpose.CODE, route.model(), PromptLibrary.PLAN_SYSTEM,
                        PromptLibrary.planUserPrompt(prd, requirement), 0.2, PLAN_MAX_TOKENS),
                route, run);
        ProjectPlan plan = ProjectPlan.parse(planCall.response().text());
        if (plan == null) {
            listener.onStatus("本次需求用单文件实现更合适，按单页面生成");
            return null;
        }
        listener.onStage(AgentStage.PLAN, "规划出 " + plan.files().size() + " 个文件（入口 "
                + plan.entry() + "），逐个生成");

        // 入口排在最后一个生成：其余文件就绪后，入口的引用清单才是确定的
        List<ProjectPlan.PlanFile> order = new ArrayList<>(plan.files());
        order.removeIf(f -> f.path().equals(plan.entry()));
        ProjectPlan.PlanFile entryFile = plan.files().stream()
                .filter(f -> f.path().equals(plan.entry())).findFirst().orElse(null);
        if (entryFile == null) {
            return null;
        }
        order.add(entryFile);

        List<String> written = new ArrayList<>();
        LlmGateway.CallResult entryCall = null;
        for (ProjectPlan.PlanFile target : order) {
            if (budget != null && budget.hasCost()
                    && budgetPolicy.exceeded(budget, run.snapshot())) {
                listener.onStatus("预算吃紧，多文件生成中止，已产出 " + written.size() + " 个文件");
                break;
            }
            listener.onStatus("正在生成 " + target.path());
            LlmGateway.CallResult call = gateway.callStreaming(
                    new LlmRequest(LlmPurpose.CODE, route.model(), PromptLibrary.CODE_SYSTEM,
                            PromptLibrary.planFileUserPrompt(plan, target, written), 0.3, 8192),
                    route,
                    delta -> listener.onDelta(target.path(), delta),
                    run);
            String content = com.codecanvas.common.CodeExtractor.extractCode(call.response().text());
            if (content == null || content.isBlank()) {
                listener.onStatus(target.path() + " 没有产出内容，跳过");
                continue;
            }
            workspace.write(target.path(), content);
            listener.onFile(target.path(), "create", GenerationFlow.byteLengthOf(content));
            written.add(target.path());
            if (target.path().equals(plan.entry())) {
                entryCall = call;
            }
        }
        if (entryCall == null) {
            return null;
        }
        return new PlanOutcome(entryCall, plan, written.size());
    }

    /**
     * 增量 MODIFY：一次调用产出 FileChangeSet JSON，解析成功即批量应用。
     *
     * @return 成功返回结果；解析失败/应用失败/无实际写入 返回 null（降级）
     */
    public ModifyOutcome modifyByChangeSet(Project project, String prd, String requirement,
                                           RouteDecision route, AgentWorkspace workspace,
                                           AgentListener listener, TokenMeter.RunMeter run,
                                           String changeSummary) {
        List<com.codecanvas.project.ProjectFile> allFiles = workspace.snapshot();
        LlmGateway.CallResult call = gateway.call(
                new LlmRequest(LlmPurpose.MODIFY, route.model(), PromptLibrary.MULTI_FILE_MODIFY_SYSTEM,
                        PromptLibrary.multiFileModifyUserPrompt(prd, requirement, allFiles,
                                changeSummary), 0.3, 8192),
                route, run);
        FileChangeSet changeSet = FileChangeSet.parse(call.response().text());
        if (changeSet == null) {
            return null;
        }
        FileChangeSet.ApplyResult apply = changeSet.applyTo(workspace);
        if (!apply.ok() || apply.totalWrites() == 0) {
            return null;
        }
        listener.onStatus("本轮修改了 " + apply.totalWrites() + " 个文件"
                + (apply.deleted() > 0 ? "，删除 " + apply.deleted() + " 个" : ""));
        changeSet.changes().forEach(change -> {
            if (change.action() == com.codecanvas.project.model.FileAction.DELETE) {
                listener.onFile(change.path(), "delete", 0);
            } else {
                String content = workspace.read(change.path());
                listener.onFile(change.path(), "update",
                        content == null ? 0 : GenerationFlow.byteLengthOf(content));
            }
        });
        return new ModifyOutcome(call, apply);
    }
}
