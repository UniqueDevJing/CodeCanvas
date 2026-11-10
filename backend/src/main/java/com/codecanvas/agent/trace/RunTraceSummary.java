package com.codecanvas.agent.trace;

import java.util.List;

/** 运行摘要（列表页用，不含事件明细） */
public record RunTraceSummary(
        String runId,
        String projectId,
        long startedAt,
        long durationMs,
        boolean ok,
        String error,
        String trigger,
        String requirement,
        int toolCalls,
        int healAttempts,
        boolean recovered,
        String tier,
        double cost,
        int promptTokens,
        int completionTokens,
        int eventCount,
        String status,
        String statusNote) {

    public static RunTraceSummary of(RunTrace t) {
        return new RunTraceSummary(t.runId(), t.projectId(), t.startedAt(), t.durationMs(),
                t.ok(), t.error(), t.trigger(), t.requirement(), t.toolCalls(), t.healAttempts(),
                t.recovered(), t.tier(), t.cost(), t.promptTokens(), t.completionTokens(),
                t.events().size(), t.status(), t.statusNote());
    }
}
