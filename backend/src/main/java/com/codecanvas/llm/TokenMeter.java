package com.codecanvas.llm;

import com.codecanvas.common.TokenEstimator;
import com.codecanvas.config.LlmProperties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import org.springframework.stereotype.Component;

/**
 * token 与成本计量。
 *
 * <p>存在的意义：分级路由如果看不到成本，就等于没有优化依据。这里对每次调用按档位单价累计，
 * 前端顶部把"本轮 / 累计成本"直接显示出来，路由策略的收益才是可观测、可验证的。
 */
@Component
public class TokenMeter {

    /** 离线引擎的提供方标识：它不产生真实费用。 */
    private static final String OFFLINE_PROVIDER = "mock";

    private final LlmProperties properties;

    private final AtomicLong globalInput = new AtomicLong();
    private final AtomicLong globalOutput = new AtomicLong();
    private final AtomicLong globalCalls = new AtomicLong();
    private final DoubleAdder globalCost = new DoubleAdder();

    public TokenMeter(LlmProperties properties) {
        this.properties = properties;
    }

    /** 记录一次调用，返回本次用量明细。 */
    public Usage record(ModelTier tier, LlmResponse response) {
        int input = response.inputTokens();
        int output = response.outputTokens();
        double cost = costOf(tier, input, output);
        // 离线引擎不产生真实费用，成本按 0 记，避免把"模拟值"当成真金白银展示
        boolean offline = OFFLINE_PROVIDER.equalsIgnoreCase(response.providerId());
        if (offline) {
            cost = 0;
        }

        globalInput.addAndGet(input);
        globalOutput.addAndGet(output);
        globalCalls.incrementAndGet();
        globalCost.add(cost);

        return new Usage(response.model(), tier, input, output, cost, true);
    }

    public Snapshot globalSnapshot() {
        return new Snapshot(globalCalls.get(), globalInput.get(), globalOutput.get(), globalCost.sum());
    }

    /** 估一次调用大概要花多少 token，用于上下文预算控制。 */
    public static int estimate(String text) {
        return TokenEstimator.estimate(text);
    }

    private double costOf(ModelTier tier, int input, int output) {
        LlmProperties.Pricing p = properties.getPricing();
        double inPrice;
        double outPrice;
        switch (tier) {
            case FLAGSHIP -> {
                inPrice = p.getFlagshipInput();
                outPrice = p.getFlagshipOutput();
            }
            case STANDARD -> {
                inPrice = p.getStandardInput();
                outPrice = p.getStandardOutput();
            }
            default -> {
                inPrice = p.getLightInput();
                outPrice = p.getLightOutput();
            }
        }
        return input / 1000.0 * inPrice + output / 1000.0 * outPrice;
    }

    /** 创建一次请求级别的计量器，用于统计"这一轮对话"的消耗。 */
    public RunMeter newRunMeter() {
        return new RunMeter(this);
    }

    /** 单次调用的用量明细。 */
    public record Usage(String model, ModelTier tier, int inputTokens, int outputTokens, double cost, boolean estimated) {
    }

    /** 累计用量快照。 */
    public record Snapshot(long calls, long inputTokens, long outputTokens, double cost) {
    }

    /** 请求级累计器。 */
    public static final class RunMeter {

        private final TokenMeter owner;
        private final AtomicLong input = new AtomicLong();
        private final AtomicLong output = new AtomicLong();
        private final AtomicLong calls = new AtomicLong();
        private final DoubleAdder cost = new DoubleAdder();

        private RunMeter(TokenMeter owner) {
            this.owner = owner;
        }

        public Usage record(ModelTier tier, LlmResponse response) {
            Usage usage = owner.record(tier, response);
            input.addAndGet(usage.inputTokens());
            output.addAndGet(usage.outputTokens());
            calls.incrementAndGet();
            cost.add(usage.cost());
            return usage;
        }

        public long inputTokens() {
            return input.get();
        }

        public long outputTokens() {
            return output.get();
        }

        public long calls() {
            return calls.get();
        }

        public double cost() {
            return cost.sum();
        }

        public TokenMeter.Snapshot snapshot() {
            return new TokenMeter.Snapshot(calls.get(), input.get(), output.get(), cost.sum());
        }
    }
}
