package com.codecanvas.web;

import com.codecanvas.agent.AgentListener;
import com.codecanvas.agent.AgentResult;
import com.codecanvas.agent.AgentStage;
import com.codecanvas.agent.UsageView;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 把 Agent 事件转成 SSE 推给浏览器。
 *
 * <p>用 <b>POST + fetch 流式读取</b>而不是 EventSource：因为提交需求是一个带请求体的 POST，
 * 且要能携带"运行时错误"这类结构化数据。
 *
 * <p>事件一旦发送失败（例如用户关了页面）就标记为已关闭并停止投递，避免后台任务继续空转。
 */
public class SseAgentListener implements AgentListener {

    private static final Logger log = LoggerFactory.getLogger(SseAgentListener.class);

    private final SseEmitter emitter;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** 连接已断开（取消信号）：编排层据此停止后续阶段 */
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    public SseAgentListener(SseEmitter emitter, ObjectMapper objectMapper) {
        this.emitter = emitter;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onSession(String projectId, boolean created) {
        send("session", map("projectId", projectId, "created", created));
    }

    @Override
    public void onStage(AgentStage stage, String detail) {
        send("stage", map(
                "stage", stage.name(),
                "label", stage.label(),
                "index", stage.index(),
                "total", AgentStage.total(),
                "detail", detail));
    }

    @Override
    public void onStatus(String text) {
        send("status", map("text", text));
    }

    @Override
    public void onToolCall(String tool, Map<String, Object> args, String result) {
        send("tool", map("tool", tool, "args", args == null ? Map.of() : args,
                "result", result == null ? "" : result));
    }

    @Override
    public void onFile(String path, String action, int bytes) {
        send("file", map("path", path, "action", action, "bytes", bytes));
    }

    /**
     * 图片产物事件（W2）：前端按 {@code addEventListener('image')} 订阅。
     *
     * <p>为什么单独开一个事件而不是塞进 {@code file}：图片是**视觉资产**，前端要拿
     * width/height 做布局占位（避免 CLS 抖动），也要拿 role 决定插到哪里；
     * 而 file 事件描述的是"工作区里的文件"，语义不同。
     */
    @Override
    public void onImage(String assetId, String url, String role, int width, int height, double costUsd) {
        send("image", map("assetId", assetId, "url", url, "role", role,
                "width", width, "height", height, "costUsd", costUsd));
    }

    @Override
    public void onDelta(String path, String text) {
        send("delta", map("path", path, "text", text));
    }

    @Override
    public void onTokens(UsageView usage) {
        send("tokens", usage);
    }

    @Override
    public void onHeal(int attempt, String reason, String detail) {
        send("heal", map("attempt", attempt, "reason", reason, "detail", detail));
    }

    @Override
    public void onResult(AgentResult result) {
        send("result", result);
    }

    @Override
    public void onError(String message) {
        send("error", map("message", message));
    }

    /** 收尾：发一个 done 事件并关闭连接。 */
    public void done() {
        send("done", map("ok", true));
        if (!closed.get()) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
                // 客户端已断开，忽略
            }
        }
    }

    /** 异常收尾。 */
    @Override
    public boolean isCancelled() {
        return cancelled.get() || closed.get();
    }

    public void abort(String message) {
        cancelled.set(true);
        onError(message);
        done();
    }

    public boolean isClosed() {
        return closed.get();
    }

    private void send(String event, Object payload) {
        if (closed.get()) {
            return;
        }
        try {
            String json = payload instanceof String text ? text : objectMapper.writeValueAsString(payload);
            emitter.send(SseEmitter.event().name(event).data(json, MediaType.TEXT_PLAIN));
        } catch (Exception e) {
            closed.set(true);
            log.debug("SSE 推送失败（客户端可能已断开）: {}", e.getMessage());
        }
    }

    private Map<String, Object> map(Object... kv) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            result.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return result;
    }
}
