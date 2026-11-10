package com.codecanvas.agent;

import com.codecanvas.llm.LlmPurpose;

import java.util.List;

/**
 * 提示词组装与结果摘要（T1/C1 从 AgentOrchestrator 拆出的静态支撑）。
 */
public final class PromptSupport {

    private static final List<String> REBUILD_PREFIXES = List.of(
            "做一个", "做个", "写一个", "写个", "设计一个", "设计个", "生成一个", "生成个",
            "创建一个", "创建个", "帮我做一个", "帮我写一个", "重新做", "重新生成", "换成做一个");

    private PromptSupport() {
    }

    /**
     * 判断用户是不是在重新描述一个完整的应用。
     * 信号：已有项目里用户又说了一句「做一个XX / 写一个XX」——语义是「我要的东西是 XX」，
     * 而不是「把这个页面改成 XX」。区分两者是保证多轮迭代不跑偏的关键。
     */
    public static boolean isRebuildIntent(String requirement) {
        if (requirement == null || requirement.isBlank()) {
            return false;
        }
        String text = requirement.trim();
        for (String prefix : REBUILD_PREFIXES) {
            if (text.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 运行时报错在提示词里的呈现形式。 */
    public static String describeRuntimeErrors(AgentRunRequest request) {
        if (request.errors() == null || request.errors().isEmpty()) {
            return "页面在浏览器中运行时报错，具体信息未知，请检查脚本中可能中断执行的地方。";
        }
        StringBuilder sb = new StringBuilder();
        for (AgentRunRequest.RuntimeError error : request.errors()) {
            sb.append("- ").append(error.message());
            if (error.line() > 0) {
                sb.append("（").append(error.source()).append(" 第 ").append(error.line()).append(" 行）");
            }
            sb.append('\n');
            if (error.stack() != null && !error.stack().isBlank()) {
                String stack = error.stack();
                sb.append("  调用栈：")
                        .append(stack.length() > 400 ? stack.substring(0, 400) + "…" : stack)
                        .append('\n');
            }
        }
        return sb.toString().trim();
    }

    public static String summarize(LlmPurpose purpose,
                                   String requirement,
                                   String prd,
                                   boolean recovered,
                                   int healAttempts,
                                   AgentRunRequest request) {
        String core = "";
        for (String line : prd.split("\n")) {
            if (line.startsWith("核心能力")) {
                core = line.replace("核心能力：", "").trim();
                break;
            }
        }
        String base = switch (purpose) {
            case CODE -> core.isEmpty() ? "已完成首版生成" : "已生成初版：包含 " + core;
            case MODIFY -> "已按需求调整：" + brief(requirement, 40);
            case FIX -> "已自动修复运行时报错：" + brief(firstRuntimeError(request), 60);
            default -> "已完成";
        };
        if (healAttempts > 0) {
            base = base + "；静态校验触发 " + healAttempts + " 次修复" + (recovered ? "后通过" : "后仍有残留问题");
        }
        return base;
    }

    public static String firstRuntimeError(AgentRunRequest request) {
        if (request.errors() == null || request.errors().isEmpty()) {
            return "页面运行时报错";
        }
        return request.errors().get(0).message();
    }

    public static String brief(String text, int max) {
        if (text == null) {
            return "";
        }
        String single = text.replace('\n', ' ').trim();
        return single.length() > max ? single.substring(0, max) + "…" : single;
    }
}
