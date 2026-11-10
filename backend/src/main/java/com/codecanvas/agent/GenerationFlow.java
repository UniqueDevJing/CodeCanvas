package com.codecanvas.agent;

import com.codecanvas.context.ContextAssembler;
import com.codecanvas.context.PromptLibrary;
import com.codecanvas.common.CodeExtractor;
import com.codecanvas.common.HtmlValidator;
import com.codecanvas.llm.BudgetPolicy;
import com.codecanvas.llm.LlmGateway;
import com.codecanvas.llm.LlmPurpose;
import com.codecanvas.llm.LlmRequest;
import com.codecanvas.llm.RouteDecision;
import com.codecanvas.llm.TaskBudget;
import com.codecanvas.llm.TokenMeter;
import com.codecanvas.mcp.McpClientBridge;
import com.codecanvas.agent.tool.AgentWorkspace;
import com.codecanvas.agent.tool.Tool;
import com.codecanvas.agent.tool.ToolRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 代码生成流（T1/C1 从 AgentOrchestrator 拆出的组件）：
 * 任务规划（模型路由）→ 预算感知调度（C2）→ 两条可切换执行路径
 * （C5 REACT 自主循环 / PIPELINE 固定流水线，互为兜底）→ 主文件落工作区。
 * 配图绑定（W2.5）由 {@link ImageBindingFlow} 负责。
 */
@Component
public class GenerationFlow {

    public static final String MAIN_FILE = "index.html";

    private final LlmGateway gateway;
    private final ContextAssembler contextAssembler;
    private final BudgetPolicy budgetPolicy;
    private final MultiFileGeneration multiFileGeneration;

    /** C5：ReAct 自主循环的步数上限（三重终止条件之一） */
    @Value("${codecanvas.react.max-steps:10}")
    private int reactMaxSteps;

    /** P0-2：外部 MCP Server 工具桥；缺省 null → 工具集与改造前完全一致 */
    @Autowired(required = false)
    private McpClientBridge mcpClientBridge;

    public GenerationFlow(LlmGateway gateway, ContextAssembler contextAssembler,
                          BudgetPolicy budgetPolicy, MultiFileGeneration multiFileGeneration) {
        this.gateway = gateway;
        this.contextAssembler = contextAssembler;
        this.budgetPolicy = budgetPolicy;
        this.multiFileGeneration = multiFileGeneration;
    }

    /** 生成阶段产物（供校验/自检修复/交付阶段继续消费） */
    public record Outcome(RouteDecision genRoute,
                          LlmGateway.CallResult generateCall,
                          AgentWorkspace workspace,
                          ToolRegistry tools,
                          String userPrompt,
                          int rawContextTokens,
                          TaskBudget budget,
                          BudgetPolicy.BudgetDecision budgetDecision) {
    }

    public Outcome run(AgentRunRequest request, AgentListener listener, TokenMeter.RunMeter run,
                       com.codecanvas.project.Project project, String prd, String requirement,
                       LlmPurpose purpose, boolean firstTurn, boolean rebuild) {
        // ============ 2. 任务规划 ============
        RouteDecision route = gateway.router().route(purpose, requirement + " " + prd, firstTurn,
                project.files().size());
        // 注意：不要往界面上抛「档位」「打分明细」这类内部信息；模型路由的收益要看成本和质量数据。
        AgentOrchestrator.checkCancelled(listener);
        listener.onStage(AgentStage.PLAN, (firstTurn || rebuild)
                ? "已经想清楚要做成什么样，开始写代码"
                : "已确认要改哪些地方");

        AgentWorkspace workspace = new AgentWorkspace();
        workspace.seed(project.files());
        ToolRegistry tools = new ToolRegistry(workspace);
        // P0-2：外部 MCP Server 的工具按「运行」注入，与内置工具并列。
        if (mcpClientBridge != null) {
            List<Tool> remote = mcpClientBridge.externalTools();
            remote.forEach(tools::register);
            if (!remote.isEmpty()) {
                listener.onStatus("已通过 MCP 接入 " + remote.size()
                        + " 个外部工具，自主模式（ReAct）可直接调用");
            }
        }
        Map<String, Object> listArgs = Map.of();
        listener.onToolCall("list_files", listArgs, tools.call("list_files", listArgs));

        // ============ 3. 代码生成 ============
        ContextAssembler.Selection selection = contextAssembler.select(project.files(), requirement);
        int rawContextTokens = UsageSupport.rawContextTokens(project, requirement);
        String systemPrompt = switch (purpose) {
            case CODE -> PromptLibrary.CODE_SYSTEM;
            case MODIFY -> PromptLibrary.MODIFY_SYSTEM;
            default -> PromptLibrary.FIX_SYSTEM;
        };
        String userPrompt = buildUserPrompt(purpose, prd, requirement, workspace, selection, request, project);

        // ============ 预算感知调度（C2）============
        // 规划阶段就按预算决定档位与自检修复轮次；预算吃紧自动收减 HEAL 轮次。
        TaskBudget budget = budgetPolicy.resolve(request.budget());
        BudgetPolicy.BudgetDecision budgetDecision = budgetPolicy.decide(
                route, budget, com.codecanvas.common.TokenEstimator.estimate(userPrompt), 8192);
        RouteDecision genRoute = budgetDecision.route();
        if (budgetDecision.overBudget()) {
            listener.onStatus("预算不足：预估成本超过预算上限，已降级到最便宜档位并关闭自检修复以保证可用");
        } else if (budget != null && budget.hasCost()) {
            listener.onStatus("预算约束已生效：" + budgetDecision.reason());
        }

        AgentOrchestrator.checkCancelled(listener);
        listener.onStage(AgentStage.GENERATE, purpose == LlmPurpose.FIX
                ? "正在修复报错"
                : "正在写页面代码");

        // C5：REACT 路径——模型自己决定「先写什么、何时校验、要不要重写」；
        // 产不出页面时自动退回流水线兜底。
        LlmGateway.CallResult generateCall = null;
        if (request.strategy() == AgentRunRequest.Strategy.REACT) {
            listener.onStatus("自主模式（ReAct）：模型将自行决定调用工具的顺序");
            final LlmGateway.CallResult[] lastCall = new LlmGateway.CallResult[1];
            com.codecanvas.agent.react.ReActAgent reactAgent = new com.codecanvas.agent.react.ReActAgent(prompt -> {
                LlmGateway.CallResult r = gateway.call(
                        new LlmRequest(LlmPurpose.CODE, genRoute.model(),
                                PromptLibrary.REACT_SYSTEM, prompt, 0.3, 8192),
                        genRoute, run);
                lastCall[0] = r;
                return r.response().text();
            }, tools, reactMaxSteps);
            com.codecanvas.agent.react.ReActAgent.Outcome outcome = reactAgent.run(
                    requirement + (prd == null || prd.isBlank() ? "" : "\n【结构化需求】\n" + prd),
                    () -> !budgetPolicy.exceeded(budget, run.snapshot()),
                    new com.codecanvas.agent.react.ReActAgent.Validation() {
                        @Override
                        public boolean passed() {
                            return HtmlValidator.validate(workspace.read(MAIN_FILE)).isEmpty();
                        }

                        @Override
                        public String detail() {
                            return HtmlValidator.describe(HtmlValidator.validate(workspace.read(MAIN_FILE)));
                        }
                    },
                    (tool, brief) -> listener.onToolCall(tool, Map.of(), brief));
            generateCall = lastCall[0];
            listener.onStatus("自主模式结束（" + outcome.stopReason() + "）："
                    + outcome.steps() + " 轮 / " + outcome.toolCalls() + " 次工具调用"
                    + (outcome.completed() ? "，页面已通过校验" : "，转入固定校验与修复流程"));
        }

        if (generateCall == null || workspace.read(MAIN_FILE).isBlank()) {
            // C3 多文件路径：首轮 CODE 先规划再逐文件生成；增量 MODIFY 先试变更集。
            // 任何一步降级（返回 null）都落到下方单文件流水线。
            if (purpose == LlmPurpose.CODE) {
                MultiFileGeneration.PlanOutcome multi = multiFileGeneration.generateByPlan(
                        project, prd, requirement, genRoute, budget, workspace, listener, run);
                if (multi != null) {
                    listener.onStatus("多文件项目已生成（" + multi.fileCount() + " 个文件）");
                    return new Outcome(genRoute, multi.entryCall(), workspace, tools, userPrompt,
                            rawContextTokens, budget, budgetDecision);
                }
            } else if (purpose == LlmPurpose.MODIFY) {
                MultiFileGeneration.ModifyOutcome modify = multiFileGeneration.modifyByChangeSet(
                        project, prd, requirement, genRoute, workspace, listener, run,
                        workspace.historySummary(project.versions()));
                if (modify != null) {
                    return new Outcome(genRoute, modify.call(), workspace, tools, userPrompt,
                            rawContextTokens, budget, budgetDecision);
                }
            }

            // 流水线路径（默认；也是 REACT/多文件产不出内容时的兜底）
            LlmGateway.CallResult pipelineCall = gateway.callStreaming(
                    new LlmRequest(purpose, genRoute.model(), systemPrompt, userPrompt, 0.3, 8192),
                    genRoute,
                    delta -> listener.onDelta(MAIN_FILE, delta),
                    run);
            generateCall = pipelineCall;

            String html = CodeExtractor.extractHtml(pipelineCall.response().text());
            String previous = project.mainFileContent();
            if (html.isBlank() || (!html.toLowerCase().contains("<html") && !previous.isBlank())) {
                listener.onStatus("这次没生成出完整页面，我先保留上一版再检查一遍");
                html = previous.isBlank() ? html : previous;
            }
            Map<String, Object> writeArgs = new LinkedHashMap<>();
            writeArgs.put("path", MAIN_FILE);
            writeArgs.put("content", html);
            tools.call("write_file", writeArgs);
            listener.onToolCall("write_file", Map.of("path", MAIN_FILE, "chars", html.length()),
                    "已写入 " + MAIN_FILE + "（" + html.length() + " 字符）");
            listener.onFile(MAIN_FILE, previous.isBlank() ? "create" : "update",
                    byteLengthOf(html));
        } else {
            listener.onFile(MAIN_FILE, "update", byteLengthOf(workspace.read(MAIN_FILE)));
        }

        return new Outcome(genRoute, generateCall, workspace, tools, userPrompt,
                rawContextTokens, budget, budgetDecision);
    }

    private String buildUserPrompt(LlmPurpose purpose,
                                   String prd,
                                   String requirement,
                                   AgentWorkspace workspace,
                                   ContextAssembler.Selection selection,
                                   AgentRunRequest request,
                                   com.codecanvas.project.Project project) {
        return switch (purpose) {
            case CODE -> PromptLibrary.codeUserPrompt(prd, requirement);
            case MODIFY -> PromptLibrary.modifyUserPrompt(prd, requirement, selection.files(),
                    workspace.historySummary(project.versions()), selection.truncationNote());
            default -> PromptLibrary.fixUserPrompt(prd, selection.files(),
                    PromptSupport.describeRuntimeErrors(request));
        };
    }

    static int byteLengthOf(String text) {
        return text == null ? 0 : text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }
}
