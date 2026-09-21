package com.codecanvas.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.codecanvas.config.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 成本反事实的算术。
 *
 * <p>这些断言同时也是"节省比例是怎么算出来的"的说明书：
 * 基准 = 同一批 token 按**显式声明的参照价**计费，节省 = (基准 - 实际) / 基准。
 * 算不出来（基准为 0）就老实返回 0，不做兜底修饰。
 *
 * <p>其中 {@link #baselineIsDeclaredNotGuessed()} 钉住了一条容易算错的规则：
 * 基准必须按**实际单价**取，不能按档位名取。
 */
class TokenMeterTest {

    private final LlmProperties props = new LlmProperties();
    private final TokenMeter meter = new TokenMeter(props);

    private static LlmResponse resp(int in, int out, String provider) {
        return LlmResponse.of("code", in, out, "test-model", provider, false);
    }

    @Test
    @DisplayName("基准是显式声明的参照价，不跟着档位名走")
    void baselineIsDeclaredNotGuessed() {
        meter.record(ModelTier.FLAGSHIP, resp(1000, 1000, "openai"));
        TokenMeter.Ledger ledger = meter.ledger();
        LlmProperties.Pricing p = props.getPricing();

        double actual = p.getFlagshipInput() + p.getFlagshipOutput();
        double baseline = p.getBaselineInput() + p.getBaselineOutput();
        assertEquals(actual, ledger.cost(), 1e-9);
        assertEquals(baseline, ledger.baselineCost(), 1e-9);
        assertTrue(ledger.baselineNote() != null && !ledger.baselineNote().isBlank(),
                "基准是谁必须报出来，否则不知道在跟什么比");

        // 这条是这个项目的关键事实：coder 系列里"旗舰"命名的 coder-next 单价
        // 反而低于参照模型，按档位名取基准会把结论算反。
        assertTrue(baseline > actual,
                "旗舰档单价应当低于参照价；若哪天价格表变了，这条断言会提醒重新核对");
    }

    @Test
    @DisplayName("节省比例可被外部复算")
    void savingIsRecomputable() {
        meter.record(ModelTier.STANDARD, resp(4000, 2000, "openai"));
        TokenMeter.Ledger ledger = meter.ledger();
        LlmProperties.Pricing p = props.getPricing();

        double expectedActual = 4.0 * p.getStandardInput() + 2.0 * p.getStandardOutput();
        double expectedBaseline = 4.0 * p.getBaselineInput() + 2.0 * p.getBaselineOutput();
        assertEquals(expectedActual, ledger.cost(), 1e-9);
        assertEquals(expectedBaseline, ledger.baselineCost(), 1e-9);
        assertEquals((expectedBaseline - expectedActual) / expectedBaseline * 100.0,
                ledger.savedPercent(), 1e-9);
    }

    @Test
    @DisplayName("离线引擎不产生费用，两条曲线都记 0")
    void offlineEngineCostsNothing() {
        meter.record(ModelTier.FLAGSHIP, resp(5000, 5000, "mock"));
        TokenMeter.Ledger ledger = meter.ledger();
        assertEquals(0.0, ledger.cost(), 1e-9);
        assertEquals(0.0, ledger.baselineCost(), 1e-9);
        assertEquals(0.0, ledger.savedPercent(), 1e-9, "基准为 0 时返回 0，不编一个好看的比例出来");
    }

    @Test
    @DisplayName("台账要带上档位分布与所用单价，否则这个数字无法复核")
    void ledgerCarriesEvidence() {
        meter.record(ModelTier.FLAGSHIP, resp(100, 100, "openai"));
        meter.record(ModelTier.LIGHT, resp(200, 200, "openai"));
        meter.record(ModelTier.LIGHT, resp(300, 300, "openai"));
        TokenMeter.Ledger ledger = meter.ledger();

        TokenMeter.TierLine light = ledger.tiers().stream()
                .filter(t -> t.tier().equals("LIGHT")).findFirst().orElseThrow();
        assertEquals(2, light.calls());
        assertEquals(500, light.inputTokens());
        assertEquals("轻量", light.label(), "档位标签直接复用 ModelTier 的，别另立一套");
        assertEquals(3, ledger.calls());

        LlmProperties.Pricing p = props.getPricing();
        assertEquals(p.getFlagshipInput(), ledger.pricing().flagshipInput(), 1e-9);
        assertEquals(p.getBaselineInput(), ledger.pricing().baselineInput(),
                1e-9, "基准单价也要一起给，否则别人没法重算");
        assertTrue(ledger.assumption().contains("选型"),
                "假设里要说清这个数回答的是'选型'而不是'路由'，避免被误引用");
    }

    @Test
    @DisplayName("本轮也要有对照，不只有累计")
    void runMeterTracksBaseline() {
        TokenMeter.RunMeter run = meter.newRunMeter();
        run.record(ModelTier.LIGHT, resp(1000, 1000, "openai"));
        assertTrue(run.baselineCost() > run.cost());
        assertEquals(run.cost(), run.snapshot().cost(), 1e-9);
        assertEquals(run.baselineCost(), run.snapshot().baselineCost(), 1e-9);
    }
}
