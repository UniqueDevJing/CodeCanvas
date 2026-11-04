package com.codecanvas.agent;

import com.codecanvas.llm.TaskBudget;
import java.util.List;

/**
 * 一次 Agent 运行的入参。
 *
 * @param trigger  USER=用户在对话框提需求；RUNTIME_ERROR=前端沙箱捕获到运行时报错自动触发修复
 * @param strategy PIPELINE=固定五阶段流水线（默认）；REACT=模型自主 ReAct 循环（C5，用于两条路径 A/B 对照）
 */
public record AgentRunRequest(
        String projectId,
        String message,
        Trigger trigger,
        List<RuntimeError> errors,
        TaskBudget budget,
        Strategy strategy) {

    public enum Trigger {
        USER,
        RUNTIME_ERROR
    }

    public enum Strategy {
        PIPELINE,
        REACT
    }

    /** 前端从 iframe 里捕获到的运行时错误。 */
    public record RuntimeError(String message, String source, int line, String stack) {
    }

    public static AgentRunRequest user(String projectId, String message) {
        return new AgentRunRequest(projectId, message, Trigger.USER, List.of(), null, Strategy.PIPELINE);
    }

    /** 带预算下发的运行请求（C2 预算感知调度）。 */
    public static AgentRunRequest user(String projectId, String message, TaskBudget budget) {
        return new AgentRunRequest(projectId, message, Trigger.USER, List.of(), budget, Strategy.PIPELINE);
    }

    /** 指定执行策略的运行请求（C5：pipeline / react A/B 对照）。 */
    public static AgentRunRequest of(String projectId, String message, Trigger trigger,
                                     List<RuntimeError> errors, TaskBudget budget, Strategy strategy) {
        return new AgentRunRequest(projectId, message, trigger, errors, budget,
                strategy == null ? Strategy.PIPELINE : strategy);
    }
}
