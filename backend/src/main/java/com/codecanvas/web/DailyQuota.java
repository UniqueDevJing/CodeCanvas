package com.codecanvas.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 每日生成次数上限（保护模型额度）。
 *
 * <p>为什么必须有：项目一旦对公网开放，"任何人打开就能用"意味着**任何人都能消耗你的 API 额度**。
 * 前端打包进去的访问密钥不是秘密，真正的护栏只能放在服务端——按天计数、超限拒绝。
 *
 * <p>为什么按天而不是按分钟限流：这是成本保护（防止额度被刷空），不是抗 DDoS；
 * 按天计数语义简单、用户可预期（每天有固定额度），也不会误伤正常迭代。
 *
 * <p>单实例内存实现，重启即清零；多副本需换 Redis 计数器（接口保持不变）。
 */
@Component
public class DailyQuota {

    private static final Logger log = LoggerFactory.getLogger(DailyQuota.class);

    private final int dailyMax;
    private final ZoneId zone;
    private final AtomicInteger used = new AtomicInteger(0);
    private volatile LocalDate day;

    public DailyQuota(@Value("${codecanvas.quota.daily-max:30}") int dailyMax,
                      @Value("${codecanvas.quota.zone:Asia/Shanghai}") String zone) {
        this.dailyMax = Math.max(0, dailyMax);
        this.zone = ZoneId.of(zone);
        this.day = LocalDate.now(this.zone);
    }

    /** 占用一次额度；额度已满返回 false（调用方应回 429）。 */
    public synchronized boolean tryAcquire() {
        rollIfNewDay();
        if (dailyMax <= 0) {
            return true;   // 0 = 不限制
        }
        if (used.get() >= dailyMax) {
            log.warn("[quota] 今日生成额度已用尽 {}/{}", used.get(), dailyMax);
            return false;
        }
        used.incrementAndGet();
        return true;
    }

    public synchronized void rollIfNewDay() {
        LocalDate today = LocalDate.now(zone);
        if (!today.equals(day)) {
            log.info("[quota] 新的一天 {}，额度重置（昨日使用 {}）", today, used.get());
            day = today;
            used.set(0);
        }
    }

    public int used() {
        rollIfNewDay();
        return used.get();
    }

    public int limit() {
        return dailyMax;
    }

    public int remaining() {
        return dailyMax <= 0 ? Integer.MAX_VALUE : Math.max(0, dailyMax - used());
    }

    /** 额度重置时间（次日零点），给前端提示用。 */
    public Instant resetsAt() {
        return day.plusDays(1).atStartOfDay(zone).toInstant();
    }

    /** 仅测试用：直接设定已用次数。 */
    void seed(int value) {
        used.set(Math.max(0, value));
    }
    /** 退还一次额度（生成被取消/失败时调用，避免"没产出也扣次数"）。 */
    public synchronized void refund() {
        used.updateAndGet(v -> Math.max(0, v - 1));
    }

}