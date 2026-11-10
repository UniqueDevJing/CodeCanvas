package com.codecanvas.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 每用户配额与成本上限（C7）。
 *
 * <p>共享口令模式（匿名）下配额由全局 {@link DailyQuota} 管控；
 * 登录用户按身份独立计数：每日生成次数 + 每日累计成本双重上限。
 * 某一个用户把成本刷到上限，不再影响其他用户的使用。
 */
@Component
public class UserQuota {

    private final int dailyMax;
    private final double costCeiling;
    private final ZoneId zone;
    private final Map<String, DayUsage> usage = new ConcurrentHashMap<>();

    private static final class DayUsage {
        final LocalDate day;
        final AtomicInteger chats = new AtomicInteger();
        final AtomicLong costMilli = new AtomicLong(); // 成本按 0.001 元精度累计，避免浮点漂移

        DayUsage(LocalDate day) {
            this.day = day;
        }

        boolean stale(LocalDate today) {
            return !today.equals(day);
        }
    }

    public UserQuota(@Value("${codecanvas.auth.user-daily-max:10}") int dailyMax,
                     @Value("${codecanvas.auth.user-cost-ceiling:1.0}") double costCeiling,
                     @Value("${codecanvas.quota.zone:Asia/Shanghai}") String zone) {
        this.dailyMax = Math.max(0, dailyMax);
        this.costCeiling = Math.max(0, costCeiling);
        this.zone = ZoneId.of(zone);
    }

    /**
     * 占用一次生成额度。返回原因 null 表示允许；否则返回拒绝说明（成本上限 / 次数上限）。
     */
    public String tryAcquire(String username) {
        DayUsage usage = usageOf(username);
        if (dailyMax > 0 && usage.chats.get() >= dailyMax) {
            return "今日生成次数已用尽（" + username + "：上限 " + dailyMax + " 次/天）";
        }
        if (costCeiling > 0 && usage.costMilli.get() >= Math.round(costCeiling * 1000)) {
            return "今日成本已达上限（" + username + "：¥" + String.format("%.2f", costCeiling) + "/天）";
        }
        usage.chats.incrementAndGet();
        return null;
    }

    /** 记账一次运行的实际成本（run 结束时调用，含失败消耗）。 */
    public void recordCost(String username, double cost) {
        if (username == null || cost <= 0) {
            return;
        }
        usageOf(username).costMilli.addAndGet(Math.round(cost * 1000));
    }

    /** 查询某用户当日已用次数。 */
    public int chatsUsed(String username) {
        return usageOf(username).chats.get();
    }

    /** 查询某用户当日累计成本（元）。 */
    public double costUsed(String username) {
        return usageOf(username).costMilli.get() / 1000.0;
    }

    public double costCeiling() {
        return costCeiling;
    }

    public int dailyMax() {
        return dailyMax;
    }

    /** 次数退还（取消场景，与 DailyQuota.refund 对齐）。 */
    public void refund(String username) {
        if (username != null) {
            DayUsage u = usage.get(username);
            if (u != null && u.chats.get() > 0) {
                u.chats.decrementAndGet();
            }
        }
    }

    private DayUsage usageOf(String username) {
        LocalDate today = LocalDate.now(zone);
        return usage.compute(username == null ? "_anon" : username, (k, existing) -> {
            if (existing == null || existing.stale(today)) {
                return new DayUsage(today);
            }
            return existing;
        });
    }

    /** 额度重置时间（次日零点，前端提示用）。 */
    public Instant resetsAt() {
        return LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant();
    }
}
