package com.codecanvas.web;

import com.codecanvas.agent.AgentOrchestrator;
import com.codecanvas.project.ProjectRunLock;
import com.codecanvas.web.dto.ChatRequestDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
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
    private final ProjectRunLock runLock;

    public ChatController(AgentOrchestrator orchestrator,
                          ProjectRunLock runLock,
                          @Qualifier("agentExecutor") ExecutorService agentExecutor,
                          ObjectMapper objectMapper) {
        this.orchestrator = orchestrator;
        this.runLock = runLock;
        this.agentExecutor = agentExecutor;
        this.objectMapper = objectMapper;
    }

    @PostMapping(value = "/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@RequestBody ChatRequestDto request) {
        // 同一项目不允许并发生成：两个人格同时改写一个页面，结果不可解释也不可复现。
        // 存储层已能保证不丢版本，但产品语义上应当直接拒绝（409），让用户等前一次结束。
        if (!runLock.tryAcquire(request.projectId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "该项目正在生成中，请等待当前生成结束（同一项目不支持并发生成）");
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
        SseAgentListener listener = new SseAgentListener(emitter, objectMapper);

        emitter.onTimeout(() -> listener.abort("生成超时"));
        emitter.onError(error -> listener.abort("连接异常：" + error.getMessage()));

        // 放到专用线程池执行：一次生成要多次调用模型并长时间阻塞等待，不能占用 Web 请求线程
        agentExecutor.submit(() -> {
            try {
                orchestrator.run(request.toAgentRequest(), listener);
            } catch (AgentOrchestrator.CancelledException cancelled) {
                // 用户已关闭页面：安静收尾，不推错误事件、不打错误日志
            } catch (Exception e) {
                listener.onError(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            } finally {
                listener.done();
                runLock.release(request.projectId());
            }
        });
        return emitter;
    }
}
