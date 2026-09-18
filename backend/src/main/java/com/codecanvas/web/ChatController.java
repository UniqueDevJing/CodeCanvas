package com.codecanvas.web;

import com.codecanvas.agent.AgentOrchestrator;
import com.codecanvas.web.dto.ChatRequestDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 生成入口。
 *
 * <p>只有一个流式端点，同时承担两种触发：用户提需求、以及前端沙箱捕获到运行时报错后的自动修复。
 * 合成一个端点的好处是编排逻辑只有一份，且"自动修复"在界面上和一次普通迭代长得一样，用户能看懂。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    /** 单次生成最长等待时间（含多轮修复），给足冗余。 */
    private static final long TIMEOUT_MILLIS = 15 * 60 * 1000L;

    private final AgentOrchestrator orchestrator;
    private final ExecutorService agentExecutor;
    private final ObjectMapper objectMapper;

    public ChatController(AgentOrchestrator orchestrator,
                          @Qualifier("agentExecutor") ExecutorService agentExecutor,
                          ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.agentExecutor = agentExecutor;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@RequestBody ChatRequestDto request) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        SseAgentListener listener = new SseAgentListener(emitter, objectMapper);

        emitter.onTimeout(() -> listener.abort("生成超时"));
        emitter.onError(error -> listener.abort("连接异常：" + error.getMessage()));

        // 放到专用线程池执行：一次生成要多次调用模型并长时间阻塞等待，不能占用 Web 请求线程
        agentExecutor.submit(() -> {
            try {
                orchestrator.run(request.toAgentRequest(), listener);
            } catch (Exception e) {
                listener.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } finally {
                listener.done();
            }
        });
        return emitter;
    }
}
