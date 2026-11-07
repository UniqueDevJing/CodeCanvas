package com.codecanvas.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 心跳断连探测回归（实测两次确认过：没有心跳时取消永远不触发）。
 *
 * <p>语义：心跳写入失败 ⇒ 判定客户端已断开 ⇒ 通知编排层取消。
 * 正常连接下心跳不得误触发取消。
 */
class SseHeartbeatTest {

    @Test
    @DisplayName("连接已断开（写入必失败）→ 触发取消回调，且只触发一次")
    void failedHeartbeatTriggersDisconnect() {
        SseEmitter emitter = new SseEmitter(60_000L);
        SseAgentListener listener = new SseAgentListener(emitter, new ObjectMapper());
        AtomicBoolean fired = new AtomicBoolean(false);

        SseHeartbeat heartbeat = new SseHeartbeat(emitter, () -> {
            fired.set(true);
            listener.abort("客户端已断开");
        });

        emitter.complete();       // 模拟连接结束
        heartbeat.tick();         // 第一次探测：必然失败

        assertThat(fired.get()).isTrue();
        assertThat(listener.isCancelled()).isTrue();

        fired.set(false);
        heartbeat.tick();         // 已停止后不再重复触发
        assertThat(fired.get()).isFalse();
    }

    @Test
    @DisplayName("连接正常时心跳不触发取消")
    void healthyHeartbeatDoesNotCancel() {
        SseEmitter emitter = new SseEmitter(60_000L);
        SseAgentListener listener = new SseAgentListener(emitter, new ObjectMapper());
        AtomicBoolean fired = new AtomicBoolean(false);

        SseHeartbeat heartbeat = new SseHeartbeat(emitter, () -> fired.set(true));
        heartbeat.tick();

        assertThat(fired.get()).isFalse();
        assertThat(listener.isCancelled()).isFalse();
    }

    @Test
    @DisplayName("stop() 之后心跳不再写入（避免泄漏与无意义异常）")
    void stopIsIdempotentAndStopsTicking() {
        SseEmitter emitter = new SseEmitter(60_000L);
        AtomicBoolean fired = new AtomicBoolean(false);

        SseHeartbeat heartbeat = new SseHeartbeat(emitter, () -> fired.set(true));
        heartbeat.stop();
        heartbeat.stop();      // 幂等
        heartbeat.tick();

        assertThat(fired.get()).isFalse();
    }
}