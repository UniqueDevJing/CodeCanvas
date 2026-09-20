package com.codecanvas.llm;

import com.codecanvas.common.TokenEstimator;
import com.codecanvas.config.LlmProperties;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import org.springframework.stereotype.Component;

/**
 * token 与成本计量。
 *
 * <p>存在的意义：分级路由如果看不到成本，就等于没有优化依据。这里对每次调用按档位单价累计，
 * 路由策略的收益才是可观测、可验证的。
 *
 * <p><b>反事实对照</b>：每次调用除了记实际花费，还会按「旗舰档单价」对同一批 token 再算一笔
 * {@code baselineCost}。两条曲线相减就是分级路由省下的钱。只报"我做了分级路由"没有说服力，
 * 能说出"同一批 token 若全走旗舰要花多少、实际花了多少"才算把优化量化了。
 *
 * <p><b>这个数字的边界</b>（必须一起展示，否则就是假数字）：
 * <ul>
 *   <li>节省比例取决于各档位单价之比，而单价来自 {@code llm.pricing} 配置——
 *       配置成占位值时，绝对值不可对外引用，比例也只在该配置下成立；</li>
 *   <li>离线引擎不产生费用，两条曲线都记 0，不会污染结果；</li>
 *   <li>同样的 token 走不同档位，产出其实不一样。这个对照回答的是
 *       「假设质量不掉的前提下省了多少」，不回答「换档会不会变差」。</li>
 * </ul>
 */
@Component
public class TokenMeter {

    /** 离线引擎的提供方标识：它不产生真实费用。 */
    private static final String OFFLINE_PROVIDER = "mock";

    /** 计算反事实所用的基准档位：全部按旗舰档计费。 */
    private static final ModelTier BASELINE_TIER = ModelTier.FLAGSHIP;

    /** 展示给用户的假设说明，跟着数字一起走，避免被单独引用。 */
    public static final String ASSUMPTION =
            "假设同一批 token 全部按旗舰档单价计费，再与实际花费对照。"
            + "比例取决于各档位单价之比（llm.pricing 配置）；若那里是占位价，请先换成厂商真实价目表。"
            + "离线引擎不产生费用，不计入。";

    private final LlmProperties properties;

    private final AtomicLong globalInput = new AtomicLong();
    private final AtomicLong globalOutput = new AtomicLong();
    private final AtomicLong globalCalls = new AtomicLong();
    private final DoubleAdder globalCost = new DoubleAdder();
    /** 反事实成本：同样的 token 若全部走旗舰档会花多少。 */
    private final DoubleAdder globalBaselineCost = new DoubleAdder();
    /** 各档位的调用分布：回答"省在哪一档"，这部分与单价无关，是客观事实。 */
    private final Map<ModelTier, TierStat> tierStats = new EnumMap<>(ModelTier.class);

    public TokenMeter(LlmProperties properties) {
        this.properties = properties;
        for (ModelTier tier : ModelTier.values()) {
            tierStats.put(tier, new TierStat());
        }
    }

    /** 记录一次调用，返回本次用量明细。 */
    public Usage record(ModelTier tier, LlmResponse response) {
        int input = response.inputTokens();
        int output = response.outputTokens();
        double cost = costOf(tier, input, output);
        double baseline = costOf(BASELINE_TIER, input, output);
        // 离线引擎不产生真实费用，两条曲线都记 0，避免把"模拟值"当成真金白银展示
        if (OFFLINE_PROVIDER.equalsIgnoreCase(response.providerId())) {
            cost = 0;
            baseline = 0;
        }

        globalInput.addAndGet(input);
        globalOutput.addAndGet(output);
        globalCalls.incrementAndGet();
        globalCost.add(cost);
        globalBaselineCost.add(baseline);
        tierStats.get(tier).add(input, output, cost, baseline);

        return new Usage(response.model(), tier, input, output, cost, baseline, true);
    }

    public Snapshot globalSnapshot() {
        return new Snapshot(globalCalls.get(), globalInput.get(), globalOutput.get(),
                globalCost.sum(), globalBaselineCost.sum());
    }

    /**
     * 完整的成本台账：实际 / 反事实 / 差额 / 档位分布 / 所用单价。
     *
     * <p>把单价一起返回，是为了让这个数字可复核——别人可以拿真实的 token 量按自己的价目表重算。
     */
    public Ledger ledger() {
        long calls = globalCalls.get();
        long input = globalInput.get();
        long output = globalOutput.get();
        double cost = globalCost.sum();
        double baseline = globalBaselineCost.sum();

        List<TierLine> lines = new ArrayList<>();
        for (ModelTier tier : ModelTier.values()) {
            TierStat stat = tierStats.get(tier);
            lines.add(new TierLine(tier.name(), tier.label(), stat.calls.get(),
                    stat.input.get(), stat.output.get(), stat.cost.sum(), stat.baseline.sum()));
        }

        LlmProperties.Pricing p = properties.getPricing();
        PricingLine pricing = new PricingLine(p.getFlagshipInput(), p.getFlagshipOutput(),
                p.getStandardInput(), p.getStandardOutput(), p.getLightInput(), p.getLightOutput());

        return new Ledger(calls, input, output, cost, baseline, baseline - cost,
                savedPercent(cost, baseline), lines, pricing, ASSUMPTION);
    }

    /** 估一次调用大概要花多少 token，用于上下文预算控制。 */
    public static int estimate(String text) {
        return TokenEstimator.estimate(text);
    }

    /** 节省比例（0-100）。基准为 0 时返回 0，不做"看起来好看"的兜底。 */
    public static double savedPercent(double cost, double baseline) {
        if (baseline <= 0) {
            return 0;
        }
        return (baseline - cost) / baseline * 100.0;
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
    public record Usage(String model, ModelTier tier, int inputTokens, int outputTokens,
                        double cost, double baselineCost, boolean estimated) {
    }

    /** 累计用量快照。 */
    public record Snapshot(long calls, long inputTokens, long outputTokens,
                           double cost, double baselineCost) {
    }

    /** 成本台账（对外）。 */
    public record Ledger(long calls,
                         long inputTokens,
                         long outputTokens,
                         double cost,
                         double baselineCost,
                         double savedCost,
                         double savedPercent,
                         List<TierLine> tiers,
                         PricingLine pricing,
                         String assumption) {
    }

    /** 单个档位的分布。 */
    public record TierLine(String tier, String label, long calls,
                           long inputTokens, long outputTokens, double cost, double baselineCost) {
    }

    /** 计算所用的单价，随台账一起返回以便复核。 */
    public record PricingLine(double flagshipInput, double flagshipOutput,
                              double standardInput, double standardOutput,
                              double lightInput, double lightOutput) {
    }

    /** 请求级累计器。 */
    public static final class RunMeter {

        private final TokenMeter owner;
        private final AtomicLong input = new AtomicLong();
        private final AtomicLong output = new AtomicLong();
        private final AtomicLong calls = new AtomicLong();
        private final DoubleAdder cost = new DoubleAdder();
        private final DoubleAdder baseline = new DoubleAdder();

        private RunMeter(TokenMeter owner) {
            this.owner = owner;
        }

        public Usage record(ModelTier tier, LlmResponse response) {
            Usage usage = owner.record(tier, response);
            input.addAndGet(usage.inputTokens());
            output.addAndGet(usage.outputTokens());
            calls.incrementAndGet();
            cost.add(usage.cost());
            baseline.add(usage.baselineCost());
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

        public double baselineCost() {
            return baseline.sum();
        }

        public TokenMeter.Snapshot snapshot() {
            return new TokenMeter.Snapshot(calls.get(), input.get(), output.get(),
                    cost.sum(), baseline.sum());
        }
    }

    /** 单档位的可变累计器。 */
    private static final class TierStat {
        private final AtomicLong calls = new AtomicLong();
        private final AtomicLong input = new AtomicLong();
        private final AtomicLong output = new AtomicLong();
        private final DoubleAdder cost = new DoubleAdder();
        private final DoubleAdder baseline = new DoubleAdder();

        private void add(long in, long out, double c, double b) {
            calls.incrementAndGet();
            input.addAndGet(in);
            output.addAndGet(out);
            cost.add(c);
            baseline.add(b);
        }
    }
}
