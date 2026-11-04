package com.codecanvas.agent.react;

/**
 * ReAct 单轮输出解析（C5）：把模型的一轮文本解析成结构化步骤。
 *
 * <p>协议（PromptLibrary.REACT_SYSTEM 约定）：
 * <pre>
 * Thought: 简短思考
 * Action: 工具名
 * Action Input: {"path":"index.html"}
 * </pre>
 * 解析刻意严格：缺 Action 或 Action Input 都算 invalid（回喂纠正观察，而不是猜）。
 */
public final class ReActParser {

    private ReActParser() {
    }

    /**
     * @param thought     思考内容（可为空）
     * @param action      工具名（finish 表示完成）
     * @param actionInput 参数原文（JSON 或空）
     * @param valid       是否为格式合法的一轮
     * @param problem     invalid 时的原因说明
     */
    public record Step(String thought, String action, String actionInput, boolean valid, String problem) {
        public boolean isFinish() {
            return valid && "finish".equalsIgnoreCase(action);
        }
    }

    public static Step parse(String text) {
        if (text == null || text.isBlank()) {
            return new Step(null, null, null, false, "输出为空");
        }
        String thought = extractLabeled(text, "Thought:");
        String action = extractLabeled(text, "Action:");
        if (action == null || action.isBlank()) {
            return new Step(thought, null, null, false, "缺少 Action 行");
        }
        action = action.trim();
        String input = extractBlock(text, "Action Input:");
        if (input == null) {
            return new Step(thought, action, null, false, "缺少 Action Input 行");
        }
        return new Step(thought, action, input.trim(), true, null);
    }

    /** 取 "Label:" 到行尾的内容（首次出现） */
    private static String extractLabeled(String text, String label) {
        int i = text.indexOf(label);
        if (i < 0) {
            return null;
        }
        int start = i + label.length();
        int end = text.indexOf('\n', start);
        return (end < 0 ? text.substring(start) : text.substring(start, end)).trim();
    }

    /** 取 "Label:" 之后到下一个协议标签或文本末尾的整块内容（Action Input 可能是多行 JSON） */
    private static String extractBlock(String text, String label) {
        int i = text.indexOf(label);
        if (i < 0) {
            return null;
        }
        int start = i + label.length();
        int end = text.length();
        for (String next : new String[]{"Thought:", "Action:", "Observation:"}) {
            int j = text.indexOf(next, start);
            if (j >= 0 && j < end) {
                end = j;
            }
        }
        return text.substring(start, end).trim();
    }
}
