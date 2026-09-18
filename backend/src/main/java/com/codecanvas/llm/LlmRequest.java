package com.codecanvas.llm;

/**
 * 一次模型调用的入参。
 *
 * @param purpose      本次调用目的（PRD / CODE / MODIFY / FIX），离线模拟引擎据此选择行为，
 *                     真实模型忽略该字段（提示词里已经表达了意图）
 * @param model        具体模型名
 * @param systemPrompt 系统提示词
 * @param userPrompt   用户提示词（上下文工程组装后的内容）
 * @param temperature  采样温度
 * @param maxTokens    最大输出 token
 */
public record LlmRequest(
        LlmPurpose purpose,
        String model,
        String systemPrompt,
        String userPrompt,
        double temperature,
        int maxTokens) {

    public static LlmRequest of(LlmPurpose purpose, String model, String system, String user) {
        return new LlmRequest(purpose, model, system, user, 0.3, 8192);
    }
}
