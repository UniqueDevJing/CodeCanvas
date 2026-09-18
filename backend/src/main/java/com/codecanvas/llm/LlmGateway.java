package com.codecanvas.llm;

import com.codecanvas.config.LlmProperties;
import com.codecanvas.llm.mock.MockLlmProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 模型网关：把"用哪个模型、失败怎么办"收敛在一个地方。
 *
 * <p>三件事：
 * <ol>
 *   <li><b>分级路由</b>：由 {@link ModelRouter} 决定这次走旗舰/标准/轻量档</li>
 *   <li><b>降级链</b>：高档位失败就退到下一档重试（旗舰 → 标准 → 轻量）</li>
 *   <li><b>离线兜底</b>：真实模型全部不可用时，退回离线引擎，保证链路不中断、演示不翻车</li>
 * </ol>
 *
 * <p>一个明确的边界：<b>流式输出一旦已经有内容发给用户，就不再重试</b>。
 * 因为重试会让前端收到两份拼接在一起的代码，比直接报错更糟。
 */
@Component
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    private final LlmProperties properties;
    private final ModelRouter router;
    private final LangChain4jProvider realProvider;
    private final MockLlmProvider mockProvider;
    private final TokenMeter meter;

    public LlmGateway(LlmProperties properties,
                      ModelRouter router,
                      LangChain4jProvider realProvider,
                      MockLlmProvider mockProvider,
                      TokenMeter meter) {
        this.properties = properties;
        this.router = router;
        this.realProvider = realProvider;
        this.mockProvider = mockProvider;
        this.meter = meter;
    }

    /** 当前是否真的连着模型。 */
    public boolean usingRealModel() {
        return !"mock".equalsIgnoreCase(properties.getMode()) && realProvider.available();
    }

    public String currentModeLabel() {
        if ("mock".equalsIgnoreCase(properties.getMode())) {
            return "离线演示模式（已强制指定）";
        }
        if (usingRealModel()) {
            return "已连接模型：" + properties.getActiveProvider();
        }
        return "离线演示模式（未检测到可用密钥，接入后自动切换）";
    }

    public ModelRouter router() {
        return router;
    }

    /**
     * 非流式调用，带降级链。
     */
    public CallResult call(LlmRequest request, RouteDecision route, TokenMeter.RunMeter runMeter) {
        LlmException lastError = null;
        for (ModelTier tier : fallbackChain(route.tier())) {
            LlmRequest attempt = withModel(request, router.modelOf(tier));
            for (LlmProvider provider : providers()) {
                try {
                    LlmResponse response = provider.chat(attempt);
                    if (response.text() == null || response.text().isBlank()) {
                        throw new LlmException("模型返回空内容");
                    }
                    TokenMeter.Usage usage = runMeter.record(tier, response);
                    boolean degraded = tier != route.tier() || !provider.id().equals(properties.getActiveProvider());
                    return new CallResult(response, tier, provider.id(), degraded,
                            usage == null ? null : usage);
                } catch (Exception e) {
                    lastError = new LlmException("[" + provider.id() + "/" + tier.label() + "] " + e.getMessage(), e);
                    log.warn("模型调用失败，尝试下一个候选: {}", lastError.getMessage());
                }
            }
        }
        throw lastError == null ? new LlmException("没有可用的模型提供方") : lastError;
    }

    /**
     * 流式调用，带降级链。
     *
     * @param onDelta 增量回调，直接推给前端
     */
    public CallResult callStreaming(LlmRequest request,
                                    RouteDecision route,
                                    Consumer<String> onDelta,
                                    TokenMeter.RunMeter runMeter) {
        LlmException lastError = null;
        for (ModelTier tier : fallbackChain(route.tier())) {
            LlmRequest attempt = withModel(request, router.modelOf(tier));
            for (LlmProvider provider : providers()) {
                StringBuilder emitted = new StringBuilder();
                AtomicBoolean anyDelta = new AtomicBoolean(false);
                CompletableFuture<LlmResponse> future = new CompletableFuture<>();
                CountDownLatch firstToken = new CountDownLatch(1);

                try {
                    long startedAt = System.currentTimeMillis();
                    provider.chatStream(attempt, new StreamListener() {
                        @Override
                        public void onDelta(String text) {
                            if (text == null || text.isEmpty()) {
                                return;
                            }
                            anyDelta.set(true);
                            firstToken.countDown();
                            emitted.append(text);
                            onDelta.accept(text);
                        }

                        @Override
                        public void onComplete(LlmResponse response) {
                            firstToken.countDown();
                            future.complete(response);
                        }

                        @Override
                        public void onError(Throwable error) {
                            firstToken.countDown();
                            future.completeExceptionally(error);
                        }
                    });

                    // 首字节超时：宁可快速换一个候选，也不让用户在卡死的模型上干等满整个超时
                    int firstTokenTimeout = Math.max(5, properties.getFirstTokenSeconds());
                    if (!firstToken.await(firstTokenTimeout, TimeUnit.SECONDS) && !anyDelta.get()) {
                        throw new LlmException("首字节超时（" + firstTokenTimeout + " 秒内无任何返回）");
                    }

                    long elapsedSeconds = (System.currentTimeMillis() - startedAt) / 1000;
                    long remaining = Math.max(15, timeoutSeconds() - elapsedSeconds);
                    LlmResponse response = future.get(remaining, TimeUnit.SECONDS);
                    String text = response.text() == null || response.text().isBlank()
                            ? emitted.toString()
                            : response.text();
                    if (text.isBlank()) {
                        throw new LlmException("模型返回空内容");
                    }
                    LlmResponse finalized = new LlmResponse(text, response.inputTokens(), response.outputTokens(),
                            response.model(), response.providerId(), response.estimated());
                    TokenMeter.Usage usage = runMeter.record(tier, finalized);
                    boolean degraded = tier != route.tier()
                            || !provider.id().equals(properties.getActiveProvider());
                    return new CallResult(finalized, tier, provider.id(), degraded, usage);
                } catch (Exception e) {
                    if (anyDelta.get()) {
                        // 已经有内容吐给前端了，重试只会得到重复内容，直接上抛
                        throw new LlmException("流式输出中断（已输出 " + emitted.length() + " 字符）: " + e.getMessage(), e);
                    }
                    lastError = new LlmException("[" + provider.id() + "/" + tier.label() + "] " + e.getMessage(), e);
                    log.warn("流式调用失败，尝试下一个候选: {}", lastError.getMessage());
                }
            }
        }
        throw lastError == null ? new LlmException("没有可用的模型提供方") : lastError;
    }

    /**
     * 候选提供方顺序：真实模型优先，离线引擎永远在最后兜底。
     */
    private List<LlmProvider> providers() {
        List<LlmProvider> list = new ArrayList<>();
        if (!"mock".equalsIgnoreCase(properties.getMode()) && realProvider.available()) {
            list.add(realProvider);
        }
        if (!"real".equalsIgnoreCase(properties.getMode())) {
            list.add(mockProvider);
        }
        if (list.isEmpty()) {
            list.add(mockProvider);
        }
        return list;
    }

    private List<ModelTier> fallbackChain(ModelTier start) {
        List<ModelTier> chain = new ArrayList<>();
        ModelTier tier = start;
        while (tier != null) {
            chain.add(tier);
            ModelTier next = tier.degrade();
            tier = next == tier ? null : next;
        }
        return chain;
    }

    private LlmRequest withModel(LlmRequest request, String model) {
        return new LlmRequest(request.purpose(), model, request.systemPrompt(),
                request.userPrompt(), request.temperature(), request.maxTokens());
    }

    private int timeoutSeconds() {
        return "anthropic".equalsIgnoreCase(properties.getActiveProvider())
                ? properties.getAnthropic().getTimeoutSeconds()
                : properties.getOpenai().getTimeoutSeconds();
    }

    /**
     * 一次调用的结果。
     *
     * @param response 模型输出
     * @param tier     实际生效的档位
     * @param provider 实际使用的提供方
     * @param degraded 是否发生了降级或兜底（用于在产品界面如实标注）
     */
    public record CallResult(LlmResponse response, ModelTier tier, String provider, boolean degraded,
                             TokenMeter.Usage usage) {
    }
}
