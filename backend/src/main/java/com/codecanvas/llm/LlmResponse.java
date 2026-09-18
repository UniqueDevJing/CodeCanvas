package com.codecanvas.llm;

/**
 * 一次模型调用的结果。
 *
 * @param text         模型输出文本
 * @param inputTokens  输入 token 数
 * @param outputTokens 输出 token 数
 * @param model        实际使用的模型名（降级后可能与请求的不同）
 * @param providerId   实际使用的提供方标识
 * @param estimated    token 数是否为估算值（离线引擎与部分厂商不返回用量时需要估算）
 */
public record LlmResponse(
        String text,
        int inputTokens,
        int outputTokens,
        String model,
        String providerId,
        boolean estimated) {

    public static LlmResponse of(String text, int in, int out, String model, String provider, boolean estimated) {
        return new LlmResponse(text, in, out, model, provider, estimated);
    }
}
