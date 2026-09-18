package com.codecanvas.agent;

import com.codecanvas.agent.tool.AgentWorkspace;
import com.codecanvas.agent.tool.ToolRegistry;
import com.codecanvas.common.CodeExtractor;
import com.codecanvas.common.HtmlValidator;
import com.codecanvas.common.TokenEstimator;
import com.codecanvas.context.ContextAssembler;
import com.codecanvas.context.PromptLibrary;
import com.codecanvas.llm.LlmGateway;
import com.codecanvas.llm.LlmPurpose;
import com.codecanvas.llm.LlmRequest;
import com.codecanvas.llm.RouteDecision;
import com.codecanvas.llm.TokenMeter;
import com.codecanvas.project.Project;
import com.codecanvas.project.ProjectFile;
import com.codecanvas.project.ProjectService;
import com.codecanvas.project.VersionSnapshot;
import com.codecanvas.sandbox.IframeSandboxExecutor;
import com.codecanvas.sandbox.SandboxedPreview;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Agent 编排核心。
 *
 * <p>把"一句话 → 可运行的软件"拆成五个可观测、可单独调试的阶段：
 * 需求澄清 → 任务规划 → 代码生成 → 质量校验 → 自检修复。
 *
 * <p>两个关键设计：
 * <ol>
 *   <li><b>自检闭环</b>：生成结果必须先过静态校验才有资格交付；不通过就把具体问题回注给模型定点修复，
 *       最多 3 次。这样"生成的代码跑不起来"从用户体验问题变成一个可度量的恢复率指标。</li>
 *   <li><b>上下文工程</b>：多轮迭代时不把历史对话和全部历史代码原样塞进提示词，而是注入
 *       结构化需求 + 历史变更摘要 + 相关文件。轮次越多，省得越多。</li>
 * </ol>
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    /** 自检修复的最大重试次数：再多就说明这个需求超出了当前模型的能力，继续重试只是烧钱。 */
    private static final int MAX_HEAL_ATTEMPTS = 3;

    private static final String MAIN_FILE = "index.html";

    private final ProjectService projectService;
    private final LlmGateway gateway;
    private final ContextAssembler contextAssembler;
    private final TokenMeter meter;
    private final IframeSandboxExecutor sandbox;

    public AgentOrchestrator(ProjectService projectService,
                             LlmGateway gateway,
                             ContextAssembler contextAssembler,
                             TokenMeter meter,
                             IframeSandboxExecutor sandbox) {
        this.projectService = projectService;
        this.gateway = gateway;
        this.contextAssembler = contextAssembler;
        this.meter = meter;
        this.sandbox = sandbox;
    }

    public void run(AgentRunRequest request, AgentListener listener) {
        TokenMeter.RunMeter run = meter.newRunMeter();
        try {
            doRun(request, listener, run);
        } catch (Exception e) {
            log.error("Agent 运行失败", e);
            listener.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private void doRun(AgentRunRequest request, AgentListener listener, TokenMeter.RunMeter run) {
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
        // 用户在一个已有项目里又重新完整描述了一遍应用（"做一个XX"），意图是重建而不是打补丁。
        // 不做这个区分的话，模型会把新需求当成对旧页面的局部修改，出来的东西谁都不满意。
        boolean rebuild = !firstTurn && !runtimeErrorRun && isRebuildIntent(requirement);

        // ============ 1. 需求澄清 ============
        listener.onStage(AgentStage.CLARIFY, firstTurn
                ? "正在理解你的需求"
                : (rebuild ? "你要的是一个新应用，正在重新理解需求"
                           : "沿用之前确认过的需求，不重复确认"));
        if (rebuild) {
            listener.onStatus("看起来你在描述一个全新的应用，我会重新做一个，而不是在原来页面上打补丁");
        }

        String prd = project.prd();
        if (firstTurn || rebuild || prd == null || prd.isBlank()) {
            RouteDecision prdRoute = gateway.router().route(LlmPurpose.PRD, requirement, true, 0);
            listener.onStatus("正在整理需求");
            LlmGateway.CallResult prdCall = gateway.call(
                    LlmRequest.of(LlmPurpose.PRD, prdRoute.model(),
                            PromptLibrary.PRD_SYSTEM, PromptLibrary.prdUserPrompt(requirement)),
                    prdRoute, run);
            prd = prdCall.response().text().trim();
            project = projectService.updatePrd(project, prd);
            listener.onTokens(usage(run, prdCall, prdRoute, 0, 0));
        }

        // ============ 2. 任务规划 ============
        LlmPurpose purpose = runtimeErrorRun ? LlmPurpose.FIX : ((firstTurn || rebuild) ? LlmPurpose.CODE : LlmPurpose.MODIFY);
        RouteDecision route = gateway.router().route(purpose, requirement + " " + prd, firstTurn,
                project.files().size());
        // 注意：不要往界面上抛"档位""打分明细"这类内部信息。
        // 模型路由的收益要看成本和质量数据，而不是让用户读一遍打分公式。
        listener.onStage(AgentStage.PLAN, (firstTurn || rebuild)
                ? "已经想清楚要做成什么样，开始写代码"
                : "已确认要改哪些地方");

        AgentWorkspace workspace = new AgentWorkspace();
        workspace.seed(project.files());
        ToolRegistry tools = new ToolRegistry(workspace);
        Map<String, Object> listArgs = Map.of();
        listener.onToolCall("list_files", listArgs, tools.call("list_files", listArgs));

        // ============ 3. 代码生成 ============
        ContextAssembler.Selection selection = contextAssembler.select(project.files(), requirement);
        int rawContextTokens = rawContextTokens(project, requirement);

        String systemPrompt = switch (purpose) {
            case CODE -> PromptLibrary.CODE_SYSTEM;
            case MODIFY -> PromptLibrary.MODIFY_SYSTEM;
            default -> PromptLibrary.FIX_SYSTEM;
        };
        String userPrompt = buildUserPrompt(purpose, prd, requirement, workspace, selection, request, project);

        listener.onStage(AgentStage.GENERATE, purpose == LlmPurpose.FIX
                ? "正在修复报错"
                : "正在写页面代码");

        LlmGateway.CallResult generateCall = gateway.callStreaming(
                new LlmRequest(purpose, route.model(), systemPrompt, userPrompt, 0.3, 8192),
                route,
                delta -> listener.onDelta(MAIN_FILE, delta),
                run);

        String html = CodeExtractor.extractHtml(generateCall.response().text());
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
        listener.onFile(MAIN_FILE, previous.isBlank() ? "create" : "update", byteLengthOf(html));

        // ============ 4. 质量校验 ============
        listener.onStage(AgentStage.VALIDATE, "正在检查页面能不能正常打开");
        List<HtmlValidator.Issue> issues = validate(tools, listener, workspace);

        // ============ 5. 自检修复 ============
        int healAttempts = 0;
        while (!issues.isEmpty() && healAttempts < MAX_HEAL_ATTEMPTS) {
            healAttempts++;
            String errorText = HtmlValidator.describe(issues);
            listener.onStage(AgentStage.HEAL, "发现一处问题，正在自动修复（第 " + healAttempts + " 次）");
            listener.onHeal(healAttempts, issues.get(0).message(), "已定位问题，正在做最小改动修复");

            RouteDecision healRoute = gateway.router().route(LlmPurpose.FIX, errorText, false,
                    workspace.paths().size());
            LlmGateway.CallResult fixCall = gateway.call(
                    new LlmRequest(LlmPurpose.FIX, healRoute.model(), PromptLibrary.FIX_SYSTEM,
                            PromptLibrary.fixUserPrompt(prd, workspace.snapshot(), errorText), 0.2, 8192),
                    healRoute, run);

            String fixed = CodeExtractor.extractHtml(fixCall.response().text());
            if (fixed.isBlank() || !fixed.toLowerCase().contains("<html")) {
                listener.onStatus("这次修复没有成功，我保留当前版本");
                break;
            }
            workspace.write(MAIN_FILE, fixed);
            listener.onFile(MAIN_FILE, "update", byteLengthOf(fixed));
            issues = validate(tools, listener, workspace);
        }

        boolean recovered = issues.isEmpty();
        if (!recovered) {
            listener.onStatus("还有 " + issues.size() + " 处问题没能自动修好，页面可以打开，你可以继续告诉我哪里不对");
        }

        // ============ 提交版本 ============
        String summary = summarize(purpose, requirement, prd, recovered, healAttempts, request);
        TokenMeter.Snapshot snapshot = run.snapshot();
        project = projectService.saveVersion(project, workspace.snapshot(), summary, requirement,
                healAttempts, snapshot.cost(), snapshot.inputTokens(), snapshot.outputTokens(),
                generateCall.response().model(), generateCall.tier().label());

        // ============ 沙箱预览 ============
        SandboxedPreview preview = sandbox.prepare(workspace.mainContent(), project.id(),
                project.currentVersionId());

        // 实际注入量按真正发出去的提示词算，不做美化
        ContextAssembler.ContextReport contextReport = ContextAssembler.report(
                TokenEstimator.estimate(userPrompt), rawContextTokens);
        UsageView usageView = usage(run, generateCall, route,
                contextReport.injectedTokens(), contextReport.rawTokens());

        listener.onTokens(usageView);
        listener.onResult(new AgentResult(
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
                generateCall.response().model(),
                generateCall.tier().label(),
                tools.trace(),
                // 质检报告按最终交付的内容生成，而不是按中间某次尝试
                HtmlValidator.report(workspace.mainContent()),
                usageView));
    }

    /**
     * 判断用户是不是在重新描述一个完整的应用。
     *
     * <p>信号很朴素：已有的项目里，用户又说了一句"做一个XX / 写一个XX"。
     * 这类句子的语义是"我要的东西是 XX"，而不是"把这个页面改成 XX"。
     * 把它和增量修改区分开，是保证多轮迭代不跑偏的关键一步。
     */
    private boolean isRebuildIntent(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            return false;
        }
        String text = requirement.trim();
        for (String prefix : REBUILD_PREFIXES) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static final List<String> REBUILD_PREFIXES = List.of(
            "做一个", "做个", "写一个", "写个", "设计一个", "设计个", "生成一个", "生成个",
            "创建一个", "创建个", "帮我做一个", "帮我写一个", "重新做", "重新生成", "换成做一个");

    private List<HtmlValidator.Issue> validate(ToolRegistry tools, AgentListener listener, AgentWorkspace workspace) {
        Map<String, Object> args = Map.of("path", MAIN_FILE);
        String result = tools.call("validate_html", args);
        listener.onToolCall("validate_html", args, result);
        return HtmlValidator.validate(workspace.read(MAIN_FILE));
    }

    private String buildUserPrompt(LlmPurpose purpose,
                                   String prd,
                                   String requirement,
                                   AgentWorkspace workspace,
                                   ContextAssembler.Selection selection,
                                   AgentRunRequest request,
                                   Project project) {
        return switch (purpose) {
            case CODE -> PromptLibrary.codeUserPrompt(prd, requirement);
            case MODIFY -> PromptLibrary.modifyUserPrompt(prd, requirement, selection.files(),
                    workspace.historySummary(project.versions()), selection.truncationNote());
            default -> PromptLibrary.fixUserPrompt(prd, selection.files(), describeRuntimeErrors(request));
        };
    }

    /** 运行时报错在提示词里的呈现形式。 */
    private String describeRuntimeErrors(AgentRunRequest request) {
        if (request.errors() == null || request.errors().isEmpty()) {
            return "页面在浏览器中运行时报错，具体信息未知，请检查脚本中可能中断执行的地方。";
        }
        StringBuilder sb = new StringBuilder();
        for (AgentRunRequest.RuntimeError error : request.errors()) {
            sb.append("- ").append(error.message());
            if (error.line() > 0) {
                sb.append("（").append(error.source()).append(" 第 ").append(error.line()).append(" 行）");
            }
            sb.append('\n');
            if (error.stack() != null && !error.stack().isBlank()) {
                String stack = error.stack();
                sb.append("  调用栈：")
                        .append(stack.length() > 400 ? stack.substring(0, 400) + "…" : stack)
                        .append('\n');
            }
        }
        return sb.toString().trim();
    }

    /**
     * 估算"完全不做上下文压缩"时这一轮要喂给模型的全部内容，用来量化上下文工程的收益。
     *
     * <p>口径：历史每一轮的<b>需求原文</b> + 历史每一轮的<b>模型输出（完整代码）</b> + 本轮需求。
     * 与之对比的是实际注入量（结构化 PRD + 变更摘要 + 本轮相关文件 + 本轮需求）。
     * 首轮没有历史可压缩，比例天然为 0；从第二轮起差距随轮次线性拉大——
     * 这正是多轮迭代不做上下文管理会失控的原因。
     */
    private int rawContextTokens(Project project, String requirement) {
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

    private UsageView usage(TokenMeter.RunMeter run,
                            LlmGateway.CallResult call,
                            RouteDecision route,
                            int injectedTokens,
                            int rawTokens) {
        TokenMeter.Snapshot total = run.snapshot();
        ContextAssembler.ContextReport report = ContextAssembler.report(injectedTokens, rawTokens);
        return new UsageView(
                call.response().inputTokens(),
                call.response().outputTokens(),
                total.cost(),
                meter.globalSnapshot().cost(),
                call.tier().name(),
                call.response().model(),
                call.provider(),
                call.degraded(),
                route.reason(),
                report.injectedTokens(),
                report.rawTokens(),
                report.savedPercent());
    }

    private String summarize(LlmPurpose purpose,
                             String requirement,
                             String prd,
                             boolean recovered,
                             int healAttempts,
                             AgentRunRequest request) {
        String core = "";
        for (String line : prd.split("\n")) {
            if (line.startsWith("核心能力")) {
                core = line.replace("核心能力：", "").trim();
                break;
            }
        }
        String base = switch (purpose) {
            case CODE -> core.isEmpty() ? "已完成首版生成" : "已生成初版：包含 " + core;
            case MODIFY -> "已按需求调整：" + brief(requirement, 40);
            case FIX -> "已自动修复运行时报错：" + brief(firstRuntimeError(request), 60);
            default -> "已完成";
        };
        if (healAttempts > 0) {
            base = base + "；静态校验触发 " + healAttempts + " 次修复" + (recovered ? "后通过" : "后仍有残留问题");
        }
        return base;
    }

    private String firstRuntimeError(AgentRunRequest request) {
        if (request.errors() == null || request.errors().isEmpty()) {
            return "页面运行时报错";
        }
        return request.errors().get(0).message();
    }

    private String brief(String text, int max) {
        if (text == null) {
            return "";
        }
        String single = text.replace('\n', ' ').trim();
        return single.length() > max ? single.substring(0, max) + "…" : single;
    }

    private int byteLengthOf(String text) {
        return text == null ? 0 : text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    private String safe(String text) {
        return text == null ? "" : text.trim();
    }
}
