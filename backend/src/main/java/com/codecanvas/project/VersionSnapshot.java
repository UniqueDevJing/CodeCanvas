package com.codecanvas.project;

import java.time.Instant;
import java.util.List;

/**
 * 一次生成的版本快照。
 *
 * <p>为什么必须存快照：多轮迭代最大的风险是"改坏了回不去"。每轮把文件全文落一份快照，
 * 用户就能随时退回任意一版——这比在提示词里叮嘱模型"不要破坏已有功能"可靠得多。
 *
 * <p>取舍说明：当前按全文存储（单文件场景体积可忽略），文件多、体积大时应改为存增量 diff。
 *
 * @param requirement  本轮用户说的原话。存它是为了能精确算出"不做上下文压缩要花多少 token"
 * @param healAttempts 本版触发的自检修复次数
 * @param cost         本版消耗的模型成本（元）
 */
public record VersionSnapshot(
        String id,
        String label,
        String summary,
        String requirement,
        Instant createdAt,
        List<ProjectFile> files,
        int healAttempts,
        double cost,
        long inputTokens,
        long outputTokens,
        String model,
        String tierLabel) {

    public ProjectFile mainFile() {
        return files == null || files.isEmpty() ? null : files.get(0);
    }
}
