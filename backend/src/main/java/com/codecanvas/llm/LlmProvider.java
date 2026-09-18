package com.codecanvas.llm;

/**
 * 模型提供方抽象。
 *
 * <p>平台里所有"和模型说话"的地方都只依赖这个接口。真实厂商（OpenAI 兼容协议 / Anthropic 协议）
 * 与离线模拟引擎都实现它，因此本地没有密钥也能把整条链路跑通，换厂商不需要动业务代码。
 */
public interface LlmProvider {

    /** 提供方标识，例如 openai / anthropic / mock。 */
    String id();

    /** 是否具备可用条件（密钥、端点等都已配置）。 */
    boolean available();

    /** 同步调用，返回完整结果。 */
    LlmResponse chat(LlmRequest request);

    /** 流式调用，增量通过 listener 回调。 */
    void chatStream(LlmRequest request, StreamListener listener);
}
