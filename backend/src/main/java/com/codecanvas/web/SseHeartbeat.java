package com.codecanvas.web;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 心跳（断连探测）。
 *
 * <p>为什么必须靠心跳：Spring MVC 在客户端断开时**不会稳定回调 onError / onCompletion**，
 * 错误是在容器层以 AsyncRequestNotUsableException 抛出的，绕过了监听器钩子。
 * 实测两次都证明：没有心跳时，编排层永远收不到"用户已经关掉页面"的信号，
 * 后台会把剩余阶段跑完、继续烧 token。
 *
 * <p>做法：每隔几秒写一帧 SSE 注释（`:` 开头，前端自动忽略）。写失败即判定断连，
 * 触发 {@link Runnable}；这是唯一可靠的进程内信号。
 */
public final class SseHeartbeat {

    private static final Logger log = LoggerFactory.getLogger(SseHeartbeat.class);

    /** 心跳间隔：5s 足够快（用户感知"停止"），又不会显著增加流量。 */
    static final long DEFAULT_PERIOD_MS = 5_000L;

    private final SseEmitter emitter;
    private final Runnable onDisconnect;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> future;
    private volatile boolean stopped;

    public SseHeartbeat(SseEmitter emitter, Runnable onDisconnect) {
        this(emitter, onDisconnect, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        }));
    }

    SseHeartbeat(SseEmitter emitter, Runnable onDisconnect, ScheduledExecutorService scheduler) {
        this.emitter = emitter;
        this.onDisconnect = onDisconnect;
        this.scheduler = scheduler;
    }

    public void start() {
        start(DEFAULT_PERIOD_MS);
    }

    public void start(long periodMs) {
        if (future != null) {
            return;
        }
        future = scheduler.scheduleAtFixedRate(this::tick, periodMs, periodMs, TimeUnit.MILLISECONDS);
    }

    /** 单次探测（包级可见，便于单测直接调用）。 */
    void tick() {
        if (stopped) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("ping"));
        } catch (Exception e) {
            log.info("[sse] 心跳写入失败，判定客户端已断开：{}", e.getClass().getSimpleName());
            stop();
            try {
                onDisconnect.run();
            } catch (RuntimeException ignored) {
                // 取消回调本身不允许影响其它任务
            }
        }
    }

    public void stop() {
        stopped = true;
        if (future != null) {
            future.cancel(false);
            future = null;
        }
        scheduler.shutdownNow();
    }
}