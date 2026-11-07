package com.codecanvas.agent;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.codecanvas.agent.tool.VisualGatewayClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配图预算测试（W2.6）。
 *
 * <p>预算的意义是"**超了必须降级，而不是继续烧**"，所以这里既测算术，
 * 也测"预算耗尽后槽位是否真的退化为占位图"。
 */
class ImageBudgetTest {

    @Test
    @DisplayName("预留-结算：成功按实际成本记账，预留退回")
    void reserveThenSettle() {
        ImageBudget budget = new ImageBudget(0.02, 0.003);
        assertThat(budget.tryReserve()).isTrue();
        budget.settle(0.0019);

        assertThat(budget.spentUsd()).isEqualTo(0.0019);
        assertThat(budget.generated()).isEqualTo(1);
        assertThat(budget.exhausted()).isFalse();
    }

    @Test
    @DisplayName("失败退回预留：settle(0) 不增加花费也不计生成数")
    void failedGenerationRefunds() {
        ImageBudget budget = new ImageBudget(0.02, 0.003);
        assertThat(budget.tryReserve()).isTrue();
        budget.settle(0.0);

        assertThat(budget.spentUsd()).isZero();
        assertThat(budget.generated()).isZero();
        assertThat(budget.tryReserve()).as("退回后额度应可复用").isTrue();
    }

    @Test
    @DisplayName("超额拒绝：剩余额度不足一次估值时不再预留")
    void rejectsWhenExhausted() {
        ImageBudget budget = new ImageBudget(0.005, 0.003);
        assertThat(budget.tryReserve()).isTrue();
        budget.settle(0.003);
        assertThat(budget.tryReserve()).as("0.003+0.003 > 0.005").isFalse();
        assertThat(budget.degradedByBudget()).isEqualTo(1);
        assertThat(budget.exhausted()).isTrue();
    }

    @Test
    @DisplayName("边界：恰好用满允许通过，超出一点即拒绝")
    void boundaryIsInclusive() {
        ImageBudget exact = new ImageBudget(0.006, 0.003);
        assertThat(exact.tryReserve()).isTrue();
        exact.settle(0.003);
        assertThat(exact.tryReserve()).as("恰好用满应允许").isTrue();

        ImageBudget tight = new ImageBudget(0.0059, 0.003);
        assertThat(tight.tryReserve()).isTrue();
        tight.settle(0.003);
        assertThat(tight.tryReserve()).isFalse();
    }

    @Test
    @DisplayName("零预算：一张都不给")
    void zeroBudgetGeneratesNothing() {
        ImageBudget budget = new ImageBudget(0.0, 0.003);
        assertThat(budget.tryReserve()).isFalse();
        assertThat(budget.exhausted()).isTrue();
    }

    @Test
    @DisplayName("环境变量配错时退回默认值，不抛异常")
    void badEnvFallsBackToDefault() {
        ImageBudget budget = ImageBudget.fromEnv();
        assertThat(budget.maxUsd()).isGreaterThan(0.0);
        assertThat(budget.summary()).contains("配图预算");
    }

    @Test
    @DisplayName("预算耗尽 → 槽位真的降级为占位图（端到端语义）")
    void exhaustedBudgetDegradesSlots() {
        ImageBudget budget = new ImageBudget(0.004, 0.003); // 只够一张

        ImageSlotBinder.Result result = ImageSlotBinder.bind(
                "<html><body><img data-slot=\"hero\" alt=\"主图\">"
                        + "<img data-slot=\"card\" alt=\"卡片\"></body></html>",
                (slot, prompt, aspect) -> {
                    if (!budget.tryReserve()) {
                        return VisualGatewayClient.Result.degraded("image-budget-exhausted");
                    }
                    budget.settle(0.003);
                    return new VisualGatewayClient.Result("a" + slot, "/v1/assets/a.png",
                            "http://gw/v1/assets/a.png", 1024, 1024, 0.003, false, "ok");
                });

        assertThat(result.bound()).hasSize(2);
        assertThat(result.bound().get(0).degraded()).isFalse();
        assertThat(result.bound().get(1).degraded()).as("第二张应被预算拦住").isTrue();
        assertThat(result.bound().stream().filter(ImageSlotBinder.Bound::degraded).count()).isEqualTo(1);
        assertThat(budget.generated()).isEqualTo(1);
    }
}