package com.codecanvas.agent.trace;

import java.util.List;

/** 两次运行的 diff 结果（C3：回放任意一次运行并对比两次运行） */
public record RunDiff(
        String from,
        String to,
        RunTraceSummary fromSummary,
        RunTraceSummary toSummary,
        List<StageTiming> fromStages,
        List<StageTiming> toStages,
        List<String> notes) {

    /** 某阶段的时间点与与前一阶段的间隔 */
    public record StageTiming(String stage, long atMs, long gapMs) {
    }
}
