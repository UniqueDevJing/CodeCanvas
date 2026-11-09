package com.codecanvas.guardrail;

import com.codecanvas.acceptance.AcceptanceSpec;
import com.codecanvas.agent.AgentListener;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * 结构化输出自校正护栏（P0-3）：把「解析失败就静默降级」升级成
 * 「解析 → 字段级校验 → 带违规原因的定向修复 → 复校 → 有界重试 → 诚实降级」。
 *
 * <p>模式与 triagent P0-1 的 {@code selfCorrectingTriage} 对齐，但这里是通用五段式：
 * 判据（{@link AcceptanceSpec#violations}）、修复提示词、修复调用三者职责分离。
 * 与「朴素重试」的区别是：修复提示词携带<b>上一轮确切的违规清单</b>，
 * 模型修的是被点名的字段，而不是重新猜一遍格式。
 *
 * <p>成本封顶：首轮解析 + 最多 {@value #MAX_REPAIR_ATTEMPTS} 次修复 = 3 次模型调用。
 * 全失败时降级为空断言集并显式标记 {@code downgraded}，绝不把降级伪装成通过。
 *
 * <p>判定与修复解耦：本类不依赖 LlmGateway，修复动作由调用方以
 * {@code Function<String,String>} 注入，因此可以纯单测，也可以换任何模型后端。
 */
@Component
public class StructuredOutputGuardrail {

    public static final int MAX_REPAIR_ATTEMPTS = 2;
    private static final int MAX_RAW_CHARS = 2600;

    /** 修复调用专用系统提示词：只准输出 JSON，避免二次产生 Markdown 围栏。 */
    public static final String REPAIR_SYSTEM =
            "你是结构化输出修复助手。只输出一个合法 JSON 对象，禁止输出解释、前后缀或 Markdown 代码块标记。";

    /**
     * @param rawOutput     模型首轮原始输出
     * @param repairInvoker 修复提示词 → 模型输出；由编排层注入（可抛异常，这里会兜住）
     * @param listener      决策留痕出口；可以为 null（单测场景）
     */
    public Enforcement enforce(String rawOutput, Function<String, String> repairInvoker,
                               AgentListener listener) {
        AcceptanceSpec first = AcceptanceSpec.parse(rawOutput);
        List<String> errors = AcceptanceSpec.violations(first, rawOutput);
        List<String> trail = new ArrayList<>();

        if (errors.isEmpty()) {
            return new Enforcement(first, 0, false, List.of(), List.of("首轮解析直接通过字段契约"));
        }
        trail.add("首轮解析违反 " + errors.size() + " 项约束：" + String.join("；", errors));

        for (int attempt = 1; attempt <= MAX_REPAIR_ATTEMPTS; attempt++) {
            String prompt = repairPrompt(rawOutput, errors);
            String fixed;
            try {
                fixed = repairInvoker == null ? null : repairInvoker.apply(prompt);
            } catch (Exception e) {
                trail.add("第 " + attempt + " 次定向修复调用失败：" + e.getMessage());
                continue;
            }
            if (fixed == null || fixed.isBlank()) {
                trail.add("第 " + attempt + " 次修复返回空输出");
                continue;
            }
            AcceptanceSpec candidate = AcceptanceSpec.parse(fixed);
            List<String> remain = AcceptanceSpec.violations(candidate, fixed);
            if (remain.isEmpty()) {
                trail.add("第 " + attempt + " 次定向修复通过复校");
                audit(listener, attempt, true, errors);
                return new Enforcement(candidate, attempt, false, List.of(), List.copyOf(trail));
            }
            trail.add("第 " + attempt + " 次复校仍有 " + remain.size() + " 项违规");
            errors = new ArrayList<>(remain); // 累积违规反馈给下一轮，而不是重发同一份
        }

        trail.add("自校正耗尽（" + MAX_REPAIR_ATTEMPTS + " 次），诚实降级为无验收断言，不阻断交付");
        audit(listener, MAX_REPAIR_ATTEMPTS, false, errors);
        return new Enforcement(AcceptanceSpec.empty(), MAX_REPAIR_ATTEMPTS, true,
                List.copyOf(errors), List.copyOf(trail));
    }

    private static String repairPrompt(String rawOutput, List<String> violations) {
        String text = rawOutput == null ? "" : rawOutput;
        if (text.length() > MAX_RAW_CHARS) {
            text = text.substring(0, MAX_RAW_CHARS) + "…(省略)";
        }
        return "上一轮的验收断言输出违反了以下约束：\n【" + String.join("；", violations) + "】\n"
                + "请逐条修正，只输出一个合法 JSON 对象。字段契约：\n"
                + "{\"checks\":[{\"id\":\"a1\",\"kind\":\"dom|content|behavior|semantic\","
                + "\"target\":\"判定目标（标签片段/id/class/关键文案）\",\"description\":\"中文断言描述\"}]}\n"
                + "要求：checks 1~5 条；id/target/description 均不能为空；kind 取上面四种之一。\n\n"
                + "原始输出：\n" + text;
    }

    /**
     * 留痕复用 {@code onToolCall}（tool 事件已在 SSE 契约快照里），
     * 因此不新增事件名，也自动被 RunTraceRecorder 录进执行轨迹，可在时间旅行面板回放。
     */
    private static void audit(AgentListener listener, int attempts, boolean repaired,
                              List<String> violations) {
        if (listener == null) {
            return;
        }
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("phase", "output");
        args.put("rule", "structured-output-self-correct");
        args.put("attempts", attempts);
        args.put("violations", violations);
        listener.onToolCall("guardrail_structured_output", args,
                repaired ? "自校正成功，字段契约通过" : "自校正耗尽，降级为无验收断言");
    }

    public record Enforcement(
            AcceptanceSpec spec,
            int repairAttempts,
            boolean downgraded,
            List<String> remainingViolations,
            List<String> trail) {
    }
}
