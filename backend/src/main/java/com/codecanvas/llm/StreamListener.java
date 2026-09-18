package com.codecanvas.llm;

import java.util.function.Consumer;

/**
 * 流式回调。把提供方的差异收敛在这里，上层编排只关心"收到一段增量 / 结束 / 出错"。
 */
public interface StreamListener {

    /** 收到一段文本增量。 */
    void onDelta(String text);

    /** 正常结束，携带完整结果（含用量）。 */
    void onComplete(LlmResponse response);

    /** 出错。上层据此决定是否降级重试。 */
    void onError(Throwable error);

    /** 构造一个只关心增量的简单监听器。 */
    static StreamListener ofDeltas(Consumer<String> onDelta, Consumer<LlmResponse> onComplete) {
        return new StreamListener() {
            @Override
            public void onDelta(String text) {
                onDelta.accept(text);
            }

            @Override
            public void onComplete(LlmResponse response) {
                onComplete.accept(response);
            }

            @Override
            public void onError(Throwable error) {
                throw new LlmException("模型调用失败: " + error.getMessage(), error);
            }
        };
    }
}
