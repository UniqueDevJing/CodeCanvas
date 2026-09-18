package com.codecanvas.web.dto;

import com.codecanvas.agent.AgentRunRequest;
import java.util.List;

/**
 * 对话生成请求。
 *
 * @param trigger USER=用户提需求；RUNTIME_ERROR=前端捕获到运行时报错后自动触发修复
 */
public record ChatRequestDto(
        String projectId,
        String message,
        String trigger,
        List<RuntimeErrorDto> errors) {

    /** 前端从预览 iframe 里捕获到的运行时错误。 */
    public record RuntimeErrorDto(String message, String source, Integer line, String stack) {
    }

    public AgentRunRequest toAgentRequest() {
        AgentRunRequest.Trigger type = "RUNTIME_ERROR".equalsIgnoreCase(trigger)
                ? AgentRunRequest.Trigger.RUNTIME_ERROR
                : AgentRunRequest.Trigger.USER;
        List<AgentRunRequest.RuntimeError> list = errors == null ? List.of() : errors.stream()
                .map(e -> new AgentRunRequest.RuntimeError(
                        e.message(),
                        e.source() == null ? "index.html" : e.source(),
                        e.line() == null ? 0 : e.line(),
                        e.stack() == null ? "" : e.stack()))
                .toList();
        String text = message == null || message.isBlank()
                ? (type == AgentRunRequest.Trigger.RUNTIME_ERROR ? "页面运行时报错，请修复" : "")
                : message;
        return new AgentRunRequest(projectId, text, type, list);
    }
}
