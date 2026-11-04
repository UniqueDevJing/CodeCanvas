package com.codecanvas.agent.trace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentListener 装饰器：把一次运行的全部回调录制成 {@link RunEvent} 轨迹，
 * 同时原样转发给真实监听器（SSE）——对编排零侵入，现场行为不变。
 *
 * <p>delta（流式 token）不逐条记录：按文件聚合字符数，结束时补一条汇总事件，
 * 避免几千条 delta 把轨迹撑爆。轨迹只存"发生了什么"，不存"产出了什么"。
 */
public class RunTraceRecorder implements com.codecanvas.agent.AgentListener {

    private final String runId;
    private final com.codecanvas.agent.AgentListener delegate;
    private final long start = System.currentTimeMillis();
    private final List<RunEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

    private volatile String projectId;
    private volatile String trigger;
    private volatile String strategy;
    private volatile String requirement;
    private volatile String currentStage;
    private final Map<String, Long> deltaChars = new LinkedHashMap<>();   // path -> 聚合字符数
    private final Map<String, Integer> deltaCount = new LinkedHashMap<>(); // path -> delta 条数

    private volatile long finishedAt;
    private volatile boolean ok;
    private volatile String error;
    private volatile int toolCalls;
    private volatile int healAttempts;
    private volatile boolean recovered;
    private volatile String model;
    private volatile String tier;
    private volatile double cost;
    private volatile double baselineCost;
    private volatile int promptTokens;
    private volatile int completionTokens;
    private volatile int contextInjected;
    private volatile int contextRaw;
    private volatile String versionId;
    private volatile String versionLabel;

    public RunTraceRecorder(String runId, com.codecanvas.agent.AgentListener delegate) {
        this.runId = runId;
        this.delegate = delegate;
    }

    /** 传入触发上下文（编排层在 run 开始时调用一次） */
    public void begin(String trigger, String strategy, String requirement) {
        this.trigger = trigger;
        this.strategy = strategy;
        this.requirement = requirement;
    }

    // ---------- 转发 + 录制 ----------

    @Override
    public void onSession(String pid, boolean created) {
        this.projectId = pid;
        events.add(RunEvent.of(start, "session", currentStage,
                (created ? "创建项目 " : "继续项目 ") + pid,
                Map.of("projectId", pid == null ? "" : pid, "created", created)));
        delegate.onSession(pid, created);
    }

    @Override
    public void onStage(com.codecanvas.agent.AgentStage stage, String detail) {
        this.currentStage = stage.name();
        events.add(RunEvent.of(start, "stage", stage.name(),
                stage.label() + "：" + detail,
                Map.of("stage", stage.name(), "label", stage.label(),
                        "index", stage.index(), "total", com.codecanvas.agent.AgentStage.total(),
                        "detail", detail == null ? "" : detail)));
        delegate.onStage(stage, detail);
    }

    @Override
    public void onStatus(String text) {
        events.add(RunEvent.of(start, "status", currentStage, text, Map.of()));
        delegate.onStatus(text);
    }

    @Override
    public void onToolCall(String tool, Map<String, Object> args, String result) {
        toolCalls++;
        String summary = result == null ? "" : (result.length() > 160 ? result.substring(0, 160) + "…" : result);
        events.add(RunEvent.of(start, "tool", currentStage, tool + " → " + summary,
                Map.of("tool", tool, "args", args == null ? Map.of() : args,
                        "result", result == null ? "" : result)));
        delegate.onToolCall(tool, args, result);
    }

    @Override
    public void onFile(String path, String action, int bytes) {
        events.add(RunEvent.of(start, "file", currentStage, action + " " + path + "（" + bytes + "B）",
                Map.of("path", path, "action", action, "bytes", bytes)));
        delegate.onFile(path, action, bytes);
    }

    @Override
    public void onDelta(String path, String text) {
        deltaChars.merge(path == null ? "" : path, (long) (text == null ? 0 : text.length()), Long::sum);
        deltaCount.merge(path == null ? "" : path, 1, Integer::sum);
        delegate.onDelta(path, text);
    }

    @Override
    public void onTokens(com.codecanvas.agent.UsageView usage) {
        if (usage != null) {
            promptTokens = usage.promptTokens();
            completionTokens = usage.completionTokens();
            cost = usage.cost();
            baselineCost = usage.baselineCost();
            model = usage.model();
            tier = usage.tier();
            contextInjected = usage.contextInjected();
            contextRaw = usage.contextRaw();
        }
        events.add(RunEvent.of(start, "tokens", currentStage,
                "tokens " + usage.promptTokens() + "+" + usage.completionTokens()
                        + "，成本 ¥" + String.format("%.4f", usage.cost()),
                Map.of("promptTokens", usage.promptTokens(), "completionTokens", usage.completionTokens(),
                        "cost", usage.cost(), "tier", usage.tier() == null ? "" : usage.tier(),
                        "model", usage.model() == null ? "" : usage.model())));
        delegate.onTokens(usage);
    }

    @Override
    public void onHeal(int attempt, String reason, String detail) {
        healAttempts = Math.max(healAttempts, attempt);
        events.add(RunEvent.of(start, "heal", currentStage,
                "第 " + attempt + " 次修复：" + reason,
                Map.of("attempt", attempt, "reason", reason == null ? "" : reason,
                        "detail", detail == null ? "" : detail)));
        delegate.onHeal(attempt, reason, detail);
    }

    @Override
    public void onResult(com.codecanvas.agent.AgentResult result) {
        ok = true;
        recovered = result.recovered();
        versionId = result.versionId();
        versionLabel = result.versionLabel();
        events.add(RunEvent.of(start, "result", currentStage,
                result.summary() == null ? "生成完成" : result.summary(),
                Map.of("projectId", result.projectId() == null ? "" : result.projectId(),
                        "versionId", result.versionId() == null ? "" : result.versionId(),
                        "versionLabel", result.versionLabel() == null ? "" : result.versionLabel(),
                        "healAttempts", result.healAttempts(),
                        "recovered", result.recovered(),
                        "qualityChecks", result.quality() == null ? 0 : result.quality().size())));
        // 注意：不把 previewHtml/files 存进轨迹（体积大，且可从版本快照取回）
        delegate.onResult(result);
    }

    @Override
    public void onError(String message) {
        if (!ok) {
            error = message;
        }
        events.add(RunEvent.of(start, "error", currentStage, message, Map.of()));
        delegate.onError(message);
    }

    // ---------- 收尾 ----------

    /** 运行结束（正常或异常）后调用；聚合 delta 并生成最终轨迹。 */
    public RunTrace finish(String errorMessage) {
        finishedAt = System.currentTimeMillis();
        if (errorMessage != null && error == null) {
            error = errorMessage;
        }
        ok = error == null && ok;
        // delta 聚合汇总（单条，避免逐 token 事件撑爆轨迹）
        deltaChars.forEach((path, chars) -> events.add(RunEvent.of(start, "delta_summary", "GENERATE",
                "流式输出 " + path + "：" + chars + " 字符 / " + deltaCount.get(path) + " 段",
                Map.of("path", path, "chars", chars, "deltas", deltaCount.get(path)))));
        return new RunTrace(runId, projectId, start, finishedAt, Math.max(0, finishedAt - start),
                ok, error, trigger == null ? "" : trigger, strategy == null ? "" : strategy,
                requirement == null ? "" : requirement,
                toolCalls, healAttempts, recovered, model == null ? "" : model, tier == null ? "" : tier,
                cost, baselineCost, promptTokens, completionTokens, contextInjected, contextRaw,
                versionId == null ? "" : versionId, versionLabel == null ? "" : versionLabel,
                List.copyOf(events));
    }
}
