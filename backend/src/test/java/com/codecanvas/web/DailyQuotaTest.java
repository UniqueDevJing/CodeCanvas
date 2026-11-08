package com.codecanvas.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 每日额度回归（成本护栏）。
 *
 * <p>语义：额度用尽必须拒绝（429 的判定依据）；取消/失败要退还；0 表示不限制。
 * 这条护栏的意义是"别人用你的公开演示时，不会把你的模型额度刷空"。
 */
class DailyQuotaTest {

    @Test
    @DisplayName("按上限扣减：扣满后拒绝")
    void rejectsAfterLimit() {
        DailyQuota quota = new DailyQuota(3, "Asia/Shanghai");

        assertThat(quota.tryAcquire()).isTrue();
        assertThat(quota.tryAcquire()).isTrue();
        assertThat(quota.tryAcquire()).isTrue();
        assertThat(quota.used()).isEqualTo(3);
        assertThat(quota.remaining()).isZero();
        assertThat(quota.tryAcquire()).as("超限必须拒绝").isFalse();
    }

    @Test
    @DisplayName("退还：取消/失败不占用当日次数")
    void refundFreesSlot() {
        DailyQuota quota = new DailyQuota(2, "Asia/Shanghai");
        assertThat(quota.tryAcquire()).isTrue();
        assertThat(quota.tryAcquire()).isTrue();
        assertThat(quota.tryAcquire()).isFalse();

        quota.refund();
        assertThat(quota.used()).isEqualTo(1);
        assertThat(quota.tryAcquire()).as("退还后应能再次使用").isTrue();
    }

    @Test
    @DisplayName("退还不会把已用次数减成负数")
    void refundNeverNegative() {
        DailyQuota quota = new DailyQuota(5, "Asia/Shanghai");
        quota.refund();
        quota.refund();
        assertThat(quota.used()).isZero();
    }

    @Test
    @DisplayName("0 表示不限制（本地开发用）")
    void zeroMeansUnlimited() {
        DailyQuota quota = new DailyQuota(0, "Asia/Shanghai");
        for (int i = 0; i < 100; i++) {
            assertThat(quota.tryAcquire()).isTrue();
        }
        assertThat(quota.remaining()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("并发下不超发：10 线程抢 3 个额度，只有 3 个成功")
    void concurrentAcquireIsSafe() throws Exception {
        DailyQuota quota = new DailyQuota(3, "Asia/Shanghai");
        int threads = 10;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger granted = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (quota.tryAcquire()) {
                        granted.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(granted.get()).as("并发下不能超发").isEqualTo(3);
        assertThat(quota.used()).isEqualTo(3);
    }

    @Test
    @DisplayName("重置时间在次日零点（给前端提示用）")
    void resetsAtNextMidnight() {
        DailyQuota quota = new DailyQuota(10, "Asia/Shanghai");
        assertThat(quota.resetsAt()).isNotNull();
        assertThat(quota.resetsAt()).isAfter(java.time.Instant.now());
    }
}