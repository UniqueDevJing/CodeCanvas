package com.codecanvas.agent.trace;

import java.util.List;
import java.util.UUID;

/**
 * 一次运行的完整执行轨迹（C3）：可整单落盘、回放、diff。
 *
 * <p>summary 字段（toolCalls / healAttempts / cost / tokens / recovered…）在运行结束时
 * 由 {@link RunTraceRecorder} 汇总生成，供列表页与 diff 直接比较，不必遍历 events。
 *
 * <p>注意：轨迹里刻意<b>不存</b> previewHtml 与文件内容（体积大且可从版本快照取回），
 * 只存"发生了什么"，不存"产出了什么"。
 */
public record RunTrace(
        String runId,
        String projectId,
        long startedAt,
        long finishedAt,
        long durationMs,
        boolean ok,
        String error,
        String trigger,
        String requirement,
        int toolCalls,
        int healAttempts,
        boolean recovered,
        String model,
        String tier,
        double cost,
        double baselineCost,
        int promptTokens,
        int completionTokens,
        int contextInjected,
        int contextRaw,
        String versionId,
        String versionLabel,
        List<RunEvent> events) {

    /** 生成 runId：时间前缀便于文件名排序。 */
    public static String newId() {
        return "r" + Long.toString(System.currentTimeMillis(), 36) + "-"
                + UUID.randomUUID().toString().substring(0, 8);
    }
}
