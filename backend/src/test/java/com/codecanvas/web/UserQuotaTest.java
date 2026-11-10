package com.codecanvas.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C7 用户配额与成本上限测试。 */
class UserQuotaTest {

    private UserQuota quota(int dailyMax, double costCeiling) {
        return new UserQuota(dailyMax, costCeiling, "Asia/Shanghai");
    }

    @Test
    void withinLimitsAllowed() {
        UserQuota q = quota(3, 10.0);
        assertNull(q.tryAcquire("alice"));
        assertNull(q.tryAcquire("alice"));
        assertEquals(2, q.chatsUsed("alice"));
    }

    @Test
    void dailyMaxEnforcedPerUser() {
        UserQuota q = quota(2, 100.0);
        assertNull(q.tryAcquire("alice"));
        assertNull(q.tryAcquire("alice"));
        assertNotNull(q.tryAcquire("alice"), "超出每日次数应拒绝");
        assertNull(q.tryAcquire("bob"), "用户之间互不影响");
    }

    @Test
    void costCeilingEnforced() {
        UserQuota q = quota(100, 0.5);
        assertNull(q.tryAcquire("alice"));
        q.recordCost("alice", 0.3);
        assertNull(q.tryAcquire("alice"), "0.3 < 0.5 仍可用");
        q.recordCost("alice", 0.3);
        assertNotNull(q.tryAcquire("alice"), "累计 0.6 超过 0.5 上限应拒绝");
        assertTrue(q.costUsed("alice") > 0.55, "成本应被累计");
    }

    @Test
    void cancelledRunRefundsChatCount() {
        UserQuota q = quota(1, 100.0);
        assertNull(q.tryAcquire("alice"));
        assertNotNull(q.tryAcquire("alice"));
        q.refund("alice");
        assertNull(q.tryAcquire("alice"), "取消退还后可再次使用");
    }

    @Test
    void zeroMeansUnlimited() {
        UserQuota q = quota(0, 0);
        for (int i = 0; i < 50; i++) {
            assertNull(q.tryAcquire("alice"), "0=不限制");
        }
    }

    @Test
    void anonymousUsageIsolated() {
        UserQuota q = quota(1, 100.0);
        assertNull(q.tryAcquire("alice"));
        assertNull(q.tryAcquire(null), "匿名键与具名用户互不影响");
    }
}
