package com.codecanvas.acceptance;

/**
 * 语义打分器抽象（C-A 验收断言语义档）。
 *
 * <p>实现方负责真实嵌入调用（如 DashScope text-embedding）并返回两段文本的语义相似度；
 * 求值器只依赖本接口，单测用脚本化假打分器驱动。失败时实现方应返回负数或抛出异常，
 * 由 {@link AcceptanceEvaluator} 统一按"语义断言暂不可用"诚实降级（不阻断交付）。
 */
public interface SemanticScorer {

    /**
     * 计算两段文本的语义相似度（0~1，越大越相关）。
     *
     * @return 相似度；不可用时返回 -1（调用方按未配置处理）
     */
    double similarity(String claim, String pageText);

    /** 模型标识（质检报告/日志用） */
    default String model() {
        return "";
    }
}
