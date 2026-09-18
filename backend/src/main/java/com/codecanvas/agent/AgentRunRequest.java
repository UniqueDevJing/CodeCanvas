package com.codecanvas.agent;

import java.util.List;

/**
 * 一次 Agent 运行的入参。
 *
 * @param trigger USER=用户在对话框提需求；RUNTIME_ERROR=前端沙箱捕获到运行时报错自动触发修复
 */
public record AgentRunRequest(
        String projectId,
        String message,
        Trigger trigger,
        List<RuntimeError> errors) {

    public enum Trigger {
        USER,
        RUNTIME_ERROR
    }

    /** 前端从 iframe 里捕获到的运行时错误。 */
    public record RuntimeError(String message, String source, int line, String stack) {
    }

    public static AgentRunRequest user(String projectId, String message) {
        return new AgentRunRequest(projectId, message, Trigger.USER, List.of());
    }
}
