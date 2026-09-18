package com.codecanvas.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.codecanvas.config.LlmProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 分级路由的决策边界。这些断言同时也是一份"什么任务该用贵模型"的说明书。 */
class ModelRouterTest {

    private final ModelRouter router = new ModelRouter(new LlmProperties());

    @Test
    @DisplayName("首轮完整生成应走旗舰档")
    void firstTurnGenerationGoesFlagship() {
        RouteDecision decision = router.route(LlmPurpose.CODE,
                "做一个项目管理平台，需要登录、看板和统计图表", true, 0);
        assertEquals(ModelTier.FLAGSHIP, decision.tier());
        assertTrue(decision.reason().contains("旗舰"), "应给出可读的决策理由");
    }

    @Test
    @DisplayName("局部微调应走轻量档")
    void smallTweakGoesLight() {
        RouteDecision decision = router.route(LlmPurpose.MODIFY, "把颜色改成蓝色", false, 1);
        assertEquals(ModelTier.LIGHT, decision.tier());
    }

    @Test
    @DisplayName("需求抽取属于轻量任务")
    void prdExtractionGoesLight() {
        assertEquals(ModelTier.LIGHT, router.route(LlmPurpose.PRD, "做一个记账小工具", true, 0).tier());
    }

    @Test
    @DisplayName("常规功能增改走标准档")
    void regularModificationGoesStandard() {
        RouteDecision decision = router.route(LlmPurpose.MODIFY, "增加一个按月份筛选和导出记录的功能", false, 1);
        assertEquals(ModelTier.STANDARD, decision.tier());
    }

    @Test
    @DisplayName("降级链最终收敛到轻量档，不会无限降级")
    void degradeChainTerminates() {
        assertEquals(ModelTier.STANDARD, ModelTier.FLAGSHIP.degrade());
        assertEquals(ModelTier.LIGHT, ModelTier.STANDARD.degrade());
        assertEquals(ModelTier.LIGHT, ModelTier.LIGHT.degrade());
    }
}
