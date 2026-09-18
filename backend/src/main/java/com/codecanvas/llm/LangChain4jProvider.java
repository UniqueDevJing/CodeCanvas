package com.codecanvas.llm;

import com.codecanvas.common.TokenEstimator;
import com.codecanvas.config.LlmProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.TokenUsage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 真实模型接入层，基于 LangChain4j 统一两种协议：
 * <ul>
 *   <li>OpenAI 兼容协议：DeepSeek / GLM / 通义 / 硅基流动等，换厂商只改 baseUrl 与模型名</li>
 *   <li>Anthropic 协议：Claude 官方端点，以及各类 Anthropic 兼容代理</li>
 * </ul>
 *
 * <p>模型实例按"模型名"缓存复用，避免每次请求都重建客户端。
 */
@Component
public class LangChain4jProvider implements LlmProvider {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jProvider.class);

    private final LlmProperties properties;
    private final Map<String, ChatModel> chatModels = new ConcurrentHashMap<>();
    private final Map<String, StreamingChatModel> streamingModels = new ConcurrentHashMap<>();

    public LangChain4jProvider(LlmProperties properties) {
        this.properties = properties;
    }

    @Override
    public String id() {
        return properties.getActiveProvider();
    }

    @Override
    public boolean available() {
        return endpoint().configured();
    }

    @Override
    public LlmResponse chat(LlmRequest request) {
        ChatResponse response = chatModel(request.model()).chat(toMessages(request));
        String text = response.aiMessage() == null ? "" : response.aiMessage().text();
        TokenUsage usage = response.tokenUsage();
        int in = tokenCount(usage, true, request.systemPrompt() + request.userPrompt());
        int out = tokenCount(usage, false, text);
        boolean estimated = usage == null || usage.inputTokenCount() == null;
        return LlmResponse.of(text, in, out, request.model(), id(), estimated);
    }

    @Override
    public void chatStream(LlmRequest request, StreamListener listener) {
        StringBuilder buffer = new StringBuilder();
        String promptText = request.systemPrompt() + request.userPrompt();

        streamingModel(request.model()).chat(toMessages(request), new StreamingChatResponseHandler() {

            @Override
            public void onPartialResponse(String partialResponse) {
                if (partialResponse == null || partialResponse.isEmpty()) {
                    return;
                }
                buffer.append(partialResponse);
                listener.onDelta(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                String text = buffer.length() > 0 ? buffer.toString() : response.aiMessage().text();
                TokenUsage usage = response.tokenUsage();
                int in = tokenCount(usage, true, promptText);
                int out = tokenCount(usage, false, text);
                boolean estimated = usage == null || usage.inputTokenCount() == null;
                listener.onComplete(LlmResponse.of(text, in, out, request.model(), id(), estimated));
            }

            @Override
            public void onError(Throwable error) {
                log.warn("模型流式调用失败 provider={} model={} err={}", id(), request.model(), error.toString());
                listener.onError(error);
            }
        });
    }

    private List<ChatMessage> toMessages(LlmRequest request) {
        return List.of(
                SystemMessage.from(request.systemPrompt()),
                UserMessage.from(request.userPrompt()));
    }

    private int tokenCount(TokenUsage usage, boolean input, String fallbackText) {
        if (usage != null) {
            Integer value = input ? usage.inputTokenCount() : usage.outputTokenCount();
            if (value != null) {
                return value;
            }
        }
        return TokenEstimator.estimate(fallbackText);
    }

    private LlmProperties.Endpoint endpoint() {
        return "anthropic".equalsIgnoreCase(properties.getActiveProvider())
                ? properties.getAnthropic()
                : properties.getOpenai();
    }

    private ChatModel chatModel(String modelName) {
        return chatModels.computeIfAbsent(modelName, name -> {
            LlmProperties.Endpoint endpoint = endpoint();
            Duration timeout = Duration.ofSeconds(endpoint.getTimeoutSeconds());
            if ("anthropic".equalsIgnoreCase(properties.getActiveProvider())) {
                return AnthropicChatModel.builder()
                        .baseUrl(endpoint.getBaseUrl())
                        .apiKey(endpoint.getApiKey())
                        .modelName(name)
                        .maxTokens(8192)
                        .temperature(0.3)
                        .timeout(timeout)
                        .logRequests(false)
                        .logResponses(false)
                        .build();
            }
            return OpenAiChatModel.builder()
                    .baseUrl(endpoint.getBaseUrl())
                    .apiKey(endpoint.getApiKey())
                    .modelName(name)
                    .maxTokens(8192)
                    .temperature(0.3)
                    .timeout(timeout)
                    .logRequests(false)
                    .logResponses(false)
                    .build();
        });
    }

    private StreamingChatModel streamingModel(String modelName) {
        return streamingModels.computeIfAbsent(modelName, name -> {
            LlmProperties.Endpoint endpoint = endpoint();
            Duration timeout = Duration.ofSeconds(endpoint.getTimeoutSeconds());
            if ("anthropic".equalsIgnoreCase(properties.getActiveProvider())) {
                return AnthropicStreamingChatModel.builder()
                        .baseUrl(endpoint.getBaseUrl())
                        .apiKey(endpoint.getApiKey())
                        .modelName(name)
                        .maxTokens(8192)
                        .temperature(0.3)
                        .timeout(timeout)
                        .logRequests(false)
                        .logResponses(false)
                        .build();
            }
            return OpenAiStreamingChatModel.builder()
                    .baseUrl(endpoint.getBaseUrl())
                    .apiKey(endpoint.getApiKey())
                    .modelName(name)
                    .maxTokens(8192)
                    .temperature(0.3)
                    .timeout(timeout)
                    .logRequests(false)
                    .logResponses(false)
                    .build();
        });
    }
}
