package com.codecanvas.web;

import com.codecanvas.agent.AgentStage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 断连即取消的回归测试（实测发现的一次真实失效）。
 *
 * <p>背景：Spring MVC 在客户端断开时**不会稳定回调 onError**，唯一可靠的信号是
 * "下一次 send 抛异常"。原实现只置 {@code closed}，导致编排层的 {@code isCancelled()}
 * 永远是 false——用户关了页面，后台仍继续跑完剩余阶段、继续烧 token。
 *
 * <p>这里把这条语义锁死：一旦推送失败，必须立刻变成"已取消"。
 */
class SseListenerCancelTest {

    @Test
    @DisplayName("推送失败 → isCancelled() 立即为真（不依赖 onError 回调）")
    void failedSendMarksCancelled() {
        SseEmitter emitter = new SseEmitter(60_000L);
        SseAgentListener listener = new SseAgentListener(emitter, new ObjectMapper());

        assertThat(listener.isCancelled()).as("刚建立连接时不应是已取消").isFalse();

        emitter.complete();               // 模拟连接已结束（客户端断开后的等效状态）
        listener.onStatus("触发一次推送");  // 这次 send 必然失败

        assertThat(listener.isCancelled())
                .as("推送失败必须立刻置取消位，否则编排层不会停")
                .isTrue();
    }

    @Test
    @DisplayName("abort 也会置取消位（超时/异常路径）")
    void abortMarksCancelled() {
        SseEmitter emitter = new SseEmitter(60_000L);
        SseAgentListener listener = new SseAgentListener(emitter, new ObjectMapper());

        listener.abort("生成超时");

        assertThat(listener.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("正常连接下 isCancelled() 保持 false，避免误杀正常生成")
    void healthyConnectionNotCancelled() {
        SseEmitter emitter = new SseEmitter(60_000L);
        SseAgentListener listener = new SseAgentListener(emitter, new ObjectMapper());

        listener.onStage(AgentStage.GENERATE, "正在生成页面");
        listener.onStatus("正常状态");

        assertThat(listener.isCancelled()).isFalse();
    }
}