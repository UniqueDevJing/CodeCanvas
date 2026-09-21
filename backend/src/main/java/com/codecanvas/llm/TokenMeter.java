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
 * <p><b>反事实对照</b>：每次调用除了记实际花费，还会按「池里单价最高的那一档」对同一批 token
 * 再算一笔 {@code baselineCost}。两条曲线相减，就是"没有做成本优化"时大概会多花多少。
 *
 * <p><b>为什么基准按单价取、而不是按"旗舰档"取</b>：档位名（旗舰/标准/轻量）是路由标签，
 * 不是价格标签。实测这个 coder 系列里，"旗舰"档的 {@code coder-next} 反而**最便宜**
 * （1 元/百万，而标准档的 coder-plus 是 4 元）——按名字取基准会把结论算反。
 * 所以基准取"实际单价最高的档位"，并且把它是谁一起返回。
 *
 * <p><b>这个数字的边界</b>（必须一起展示，否则就是假数字）：
 * <ul>
 *   <li>比例取决于各档位单价之比，而单价来自 {@code llm.pricing} 配置；
 *       该配置现已按厂商真实价目表录入，并注明来源与核对日期；</li>
 *   <li>阶梯计价未细分：厂商按单次请求输入 token 分档定价，这里只取单一单价，
 *       长输入场景会低估成本；</li>
 *   <li>离线引擎不产生费用，两条曲线都记 0，不会污染结果；</li>
 *   <li>同样的 token 走不同档位，产出其实不一样。这个对照回答的是
 *       「假设质量不掉的前提下省了多少」，不回答「换档会不会变差」。</li>
 * </ul>
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
    /** 反事实成本：同样的 token 若走了池里最贵的档位会花多少。 */
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
        double baseline = baselineCost(input, output);
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
                p.getStandardInput(), p.getStandardOutput(), p.getLightInput(), p.getLightOutput(),
                p.getBaselineInput(), p.getBaselineOutput());

        return new Ledger(calls, input, output, cost, baseline, baseline - cost,
                savedPercent(cost, baseline), lines, pricing,
                p.getBaselineNote(), assumption());
    }

    /** 估一次调用大概要花多少 token，用于上下文预算控制。 */
    public static int estimate(String text) {
        return TokenEstimator.estimate(text);
    }

    /**
     * 反事实基准：这批 token 若按**参照价**计费会花多少。
     *
     * <p>参照价来自 {@code llm.pricing.baseline-*}，是一个显式声明的价格，不是某一档的实际单价。
     * 默认值是改造前挂在标准档的 qwen3-coder-plus —— 也就是"没做选型修正时会走的那条路"。
     */
    private double baselineCost(int input, int output) {
        LlmProperties.Pricing p = properties.getPricing();
        return input / 1000.0 * p.getBaselineInput() + output / 1000.0 * p.getBaselineOutput();
    }

    /** 假设说明：跟数字一起返回，避免被脱离语境单独引用。 */
    public String assumption() {
        String note = properties.getPricing().getBaselineNote();
        return "基准 = 同一批 token 按【" + (note == null || note.isBlank() ? "参照模型" : note) + "】的单价计费。"
                + "这个基准回答的是「不做模型选型、按名字直觉挑一个会多花多少」，"
                + "不是「分级路由省了多少」——实测这个 coder 系列里\"旗舰\"命名的 coder-next 反而最便宜，"
                + "所以真正的收益来自**选型修正**，路由分流本身在这个模型池上几乎不产生价差。"
                + "单价来自厂商价目表（coder 系列 0<Token≤32K 档），阶梯计价未细分。"
                + "离线引擎不产生费用，不计入。";
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
                         // 基准是谁（参照模型），它不对应某一档，所以要显式报出来
                         String baselineNote,
                         String assumption) {
    }

    /** 单个档位的分布。 */
    public record TierLine(String tier, String label, long calls,
                           long inputTokens, long outputTokens, double cost, double baselineCost) {
    }

    /** 计算所用的单价，随台账一起返回以便复核。 */
    public record PricingLine(double flagshipInput, double flagshipOutput,
                              double standardInput, double standardOutput,
                              double lightInput, double lightOutput,
                              double baselineInput, double baselineOutput) {
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
