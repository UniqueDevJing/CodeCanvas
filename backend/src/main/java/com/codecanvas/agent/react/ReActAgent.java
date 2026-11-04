package com.codecanvas.agent.react;

import com.codecanvas.agent.tool.ToolRegistry;
import com.codecanvas.context.PromptLibrary;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 真 ReAct 循环（C5）：模型自主决定"下一步调用什么工具"，与固定流水线（AgentOrchestrator
 * 五阶段）形成 A/B 对照。
 *
 * <p>三重终止条件：maxSteps 步数上限 / 预算耗尽（budgetCheck 返回 false）/ 校验通过
 * （validation.passed()，即 finish 时页面质检全绿）。循环本身是纯 Java——对模型的调用
 * 经 {@link Invoker} 函数注入，编排层负责接 LlmGateway，单测用脚本化假模型即可驱动。
 */
public class ReActAgent {

    /** 对模型的单次调用：完整提示词进，模型文本出（编排层接 LlmGateway，测试接脚本） */
    public interface Invoker {
        String invoke(String prompt);
    }

    /** 完成判据：finish 时页面是否通过校验；未通过时给出可回喂的问题描述 */
    public interface Validation {
        boolean passed();

        String detail();
    }

    /**
     * @param completed 是否在校验通过后正常收尾
     * @param steps     实际执行的轮数
     * @param stopReason validated（校验通过）/ budget（预算耗尽）/ max-steps（步数用尽）/ error
     * @param toolCalls 实际执行的工具调用次数
     */
    public record Outcome(boolean completed, int steps, String stopReason, int toolCalls) {
    }

    private final Invoker invoker;
    private final ToolRegistry tools;
    private final int maxSteps;

    public ReActAgent(Invoker invoker, ToolRegistry tools, int maxSteps) {
        this.invoker = invoker;
        this.tools = tools;
        this.maxSteps = Math.max(1, maxSteps);
    }

    /**
     * 跑一次自主循环。
     *
     * @param task         任务描述（需求 + PRD）
     * @param budgetCheck  返回 false 表示预算耗尽，立即熔断
     * @param validation   finish 时的完成判据
     * @param onToolCall   每次真实工具调用的观察回调（工具名, 结果摘要），供监听器透出
     */
    public Outcome run(String task, BooleanSupplier budgetCheck, Validation validation,
                       java.util.function.BiConsumer<String, String> onToolCall) {
        StringBuilder transcript = new StringBuilder();
        int toolCalls = 0;
        for (int step = 1; step <= maxSteps; step++) {
            if (!budgetCheck.getAsBoolean()) {
                return new Outcome(false, step, "budget", toolCalls);
            }
            String prompt = PromptLibrary.reactUserPrompt(task, tools.describeTools(), transcript.toString());
            String text;
            try {
                text = invoker.invoke(prompt);
            } catch (Exception e) {
                return new Outcome(false, step, "error", toolCalls);
            }
            ReActParser.Step parsed = ReActParser.parse(text);
            if (!parsed.valid()) {
                transcript.append("Thought: (格式错误)\nObservation: 输出格式不正确（")
                        .append(parsed.problem())
                        .append("）。请严格使用 Thought / Action / Action Input 三行格式。\n\n");
                continue;
            }
            transcript.append("Thought: ").append(parsed.thought() == null ? "" : parsed.thought())
                    .append("\nAction: ").append(parsed.action())
                    .append("\nAction Input: ").append(parsed.actionInput()).append('\n');

            if (parsed.isFinish()) {
                if (validation.passed()) {
                    return new Outcome(true, step, "validated", toolCalls);
                }
                transcript.append("Observation: 校验未通过：").append(validation.detail())
                        .append("。请继续修复，完成后再次调用 finish。\n\n");
                continue;
            }

            String result;
            try {
                result = tools.call(parsed.action(), parseArgs(parsed.actionInput()));
            } catch (Exception e) {
                result = "工具调用失败：" + e.getMessage();
            }
            toolCalls++;
            if (onToolCall != null) {
                onToolCall.accept(parsed.action(), brief(result));
            }
            transcript.append("Observation: ").append(truncate(result, 600)).append("\n\n");
        }
        return new Outcome(false, maxSteps, "max-steps", toolCalls);
    }

    /** Action Input → 工具参数：优先按 JSON 解析，失败则包成 {"input": 原文}（单参工具友好） */
    public static Map<String, Object> parseArgs(String input) {
        if (input == null || input.isBlank() || "{}".equals(input.trim())) {
            return Map.of();
        }
        String t = input.trim();
        if (t.startsWith("{")) {
            try {
                return JsonMini.parse(t);
            } catch (Exception ignored) {
                // 落到下面的单参包装
            }
        }
        return Map.of("input", t);
    }

    private static String truncate(String s, int n) {
        if (s == null) {
            return "";
        }
        return s.length() <= n ? s : s.substring(0, n) + "…(" + (s.length() - n) + " 字省略)";
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 120 ? s : s.substring(0, 120) + "…";
    }
}
