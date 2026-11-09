package com.codecanvas.llm.cache;

import com.codecanvas.config.LlmProperties;
import com.codecanvas.llm.LangChain4jProvider;
import com.codecanvas.llm.LlmGateway;
import com.codecanvas.llm.LlmPurpose;
import com.codecanvas.llm.LlmRequest;
import com.codecanvas.llm.LlmResponse;
import com.codecanvas.llm.ModelRouter;
import com.codecanvas.llm.ModelTier;
import com.codecanvas.llm.RouteDecision;
import com.codecanvas.llm.StreamListener;
import com.codecanvas.llm.TokenMeter;
import com.codecanvas.llm.mock.MockLlmProvider;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网关层集成：验证「命中不产生模型调用、不计入 TokenMeter」这条核心承诺，
 * 以及流式命中按块回放、FIX 阶段绝不缓存、缓存缺席时零回归。
 */
class SemanticCacheGatewayTest {

    private static final String MODEL = "qwen3-coder-next";
    private static final String SYSTEM = "你是需求澄清助手";

    private final LlmProperties properties = new LlmProperties();
    private final TokenMeter meter = new TokenMeter(properties);
    private final ModelRouter router = mock(ModelRouter.class);
    private final LangChain4jProvider realProvider = mock(LangChain4jProvider.class);
    private final MockLlmProvider mockProvider = mock(MockLlmProvider.class);
    private final SemanticCache cache = new SemanticCache(true, 0.78, 3600, 16, "PRD", 0.35);
    private final RouteDecision route = new RouteDecision(ModelTier.LIGHT, MODEL, 1, "单测固定轻量档");

    private final List<String> deltas = new ArrayList<>();

    @BeforeEach
    void setUp() {
        when(router.modelOf(any(ModelTier.class))).thenReturn(MODEL);
        when(realProvider.available()).thenReturn(false);
        when(mockProvider.id()).thenReturn("mock");
        when(mockProvider.available()).thenReturn(true);
        when(mockProvider.chat(any())).thenReturn(
                LlmResponse.of("PRD: 待办清单", 120, 60, MODEL, "mock", true));
        doAnswer(invocation -> {
            StreamListener listener = invocation.getArgument(1);
            listener.onDelta("PRD: 待办清单");
            listener.onComplete(LlmResponse.of("PRD: 待办清单", 120, 60, MODEL, "mock", true));
            return null;
        }).when(mockProvider).chatStream(any(), any());
        deltas.clear();
    }

    private LlmGateway gateway() {
        return new LlmGateway(properties, router, realProvider, mockProvider, meter, cache);
    }

    private static LlmRequest prd() {
        return LlmRequest.of(LlmPurpose.PRD, MODEL, SYSTEM, "帮我做一个待办清单应用");
    }

    @Test
    @DisplayName("非流式：第二次相同需求命中缓存，provider 只被调一次、TokenMeter 只记一次")
    void secondCallSkipsModel() {
        LlmGateway gateway = gateway();
        TokenMeter.RunMeter run = meter.newRunMeter();

        LlmGateway.CallResult first = gateway.call(prd(), route, run);
        LlmGateway.CallResult second = gateway.call(prd(), route, run);

        assertThat(first.response().text()).isEqualTo("PRD: 待办清单");
        assertThat(second.response().text()).isEqualTo("PRD: 待办清单");
        verify(mockProvider, times(1)).chat(any());
        assertThat(run.calls()).isEqualTo(1);
        assertThat(cache.stats().hits()).isEqualTo(1);
        assertThat(cache.stats().misses()).isEqualTo(1);
        assertThat(cache.stats().savedInputTokens()).isEqualTo(120);
        assertThat(gateway.cacheHits()).isEqualTo(1);
    }

    @Test
    @DisplayName("流式：命中时按块回放完整文本，前端仍收到连续 delta")
    void streamingHitReplaysText() {
        LlmGateway gateway = gateway();
        TokenMeter.RunMeter run = meter.newRunMeter();

        LlmGateway.CallResult first = gateway.callStreaming(prd(), route, deltas::add, run);
        assertThat(String.join("", deltas)).isEqualTo("PRD: 待办清单");

        deltas.clear();
        LlmGateway.CallResult second = gateway.callStreaming(prd(), route, deltas::add, run);

        assertThat(String.join("", deltas)).isEqualTo("PRD: 待办清单");
        assertThat(second.response().text()).isEqualTo(first.response().text());
        verify(mockProvider, times(1)).chatStream(any(), any());
        assertThat(run.calls()).isEqualTo(1);
        assertThat(cache.stats().hits()).isEqualTo(1);
    }

    @Test
    @DisplayName("FIX 自检修复阶段绝不缓存：两次调用都必须真的打到模型")
    void fixIsNeverCached() {
        LlmGateway gateway = gateway();
        TokenMeter.RunMeter run = meter.newRunMeter();
        LlmRequest fix = LlmRequest.of(LlmPurpose.FIX, MODEL, SYSTEM, "修复 undefined 变量");

        gateway.call(fix, route, run);
        gateway.call(fix, route, run);

        verify(mockProvider, times(2)).chat(any());
        assertThat(run.calls()).isEqualTo(2);
        assertThat(cache.stats().hits()).isZero();
        assertThat(cache.size()).isZero();
    }

    @Test
    @DisplayName("缓存缺席（兼容 5 参数构造）时零回归：每次都直连模型")
    void noCacheFallsBackToOriginalBehavior() {
        LlmGateway gateway = new LlmGateway(properties, router, realProvider, mockProvider, meter);
        TokenMeter.RunMeter run = meter.newRunMeter();

        gateway.call(prd(), route, run);
        gateway.call(prd(), route, run);

        verify(mockProvider, times(2)).chat(any());
        assertThat(run.calls()).isEqualTo(2);
        assertThat(gateway.cacheHits()).isZero();
        assertThat(gateway.cache()).isNull();
    }
}
