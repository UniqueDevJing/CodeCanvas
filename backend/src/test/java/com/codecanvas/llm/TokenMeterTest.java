package com.codecanvas.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.codecanvas.config.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 成本反事实的算术。
 *
 * <p>这些断言同时也是"节省比例是怎么算出来的"的说明书：基准 = 同一批 token 按旗舰单价重算，
 * 节省 = (基准 - 实际) / 基准。算不出来（基准为 0）就老实返回 0，不做兜底修饰。
 */
class TokenMeterTest {

    private final LlmProperties props = new LlmProperties();
    private final TokenMeter meter = new TokenMeter(props);

    private static LlmResponse resp(int in, int out, String provider) {
        return LlmResponse.of("code", in, out, "test-model", provider, false);
    }

    @Test
    @DisplayName("走旗舰档时反事实与实际相等，节省为 0")
    void flagshipHasNoSaving() {
        meter.record(ModelTier.FLAGSHIP, resp(1000, 1000, "openai"));
        TokenMeter.Ledger ledger = meter.ledger();
        assertEquals(ledger.cost(), ledger.baselineCost(), 1e-9);
        assertEquals(0.0, ledger.savedPercent(), 1e-9);
    }

    @Test
    @DisplayName("走轻量档时，反事实按旗舰单价重算，比例可被外部复算")
    void lightTierSavingIsRecomputable() {
        meter.record(ModelTier.LIGHT, resp(1000, 1000, "openai"));
        TokenMeter.Ledger ledger = meter.ledger();
        LlmProperties.Pricing p = props.getPricing();

        double expectedActual = p.getLightInput() + p.getLightOutput();
        double expectedBaseline = p.getFlagshipInput() + p.getFlagshipOutput();
        assertEquals(expectedActual, ledger.cost(), 1e-9);
        assertEquals(expectedBaseline, ledger.baselineCost(), 1e-9);
        assertEquals((expectedBaseline - expectedActual) / expectedBaseline * 100.0,
                ledger.savedPercent(), 1e-9);
        assertTrue(ledger.savedPercent() > 0, "轻量档应当确实比旗舰便宜");
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
        assertEquals(props.getPricing().getFlagshipInput(), ledger.pricing().flagshipInput(), 1e-9);
        assertTrue(ledger.assumption().contains("旗舰"), "必须把假设写出来，不能只丢一个比例给用户");
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
