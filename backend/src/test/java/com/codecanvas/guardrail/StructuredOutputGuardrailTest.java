package com.codecanvas.guardrail;

import com.codecanvas.acceptance.AcceptanceSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** P0-3：结构化输出自校正的五段式与「诚实降级」。 */
class StructuredOutputGuardrailTest {

    private static final String VALID =
            "{\"checks\":[{\"id\":\"a1\",\"kind\":\"dom\",\"target\":\"<canvas>\","
                    + "\"description\":\"存在画布\"}]}";

    /** 缺 id：toCheck 的 String.valueOf(null) 会落成字面量 "null"。 */
    private static final String DIRTY =
            "好的，这是断言：{\"checks\":[{\"kind\":\"dom\",\"target\":\"<canvas>\","
                    + "\"description\":\"存在画布\"}]}";

    private final StructuredOutputGuardrail guard = new StructuredOutputGuardrail();

    @Test
    @DisplayName("首轮就合规时零修复调用，不浪费模型费用")
    void firstRoundPassDoesNotInvokeRepair() {
        AtomicInteger calls = new AtomicInteger();
        StructuredOutputGuardrail.Enforcement e = guard.enforce(VALID, prompt -> {
            calls.incrementAndGet();
            return VALID;
        }, null);
        assertThat(calls.get()).isZero();
        assertThat(e.repairAttempts()).isZero();
        assertThat(e.downgraded()).isFalse();
        assertThat(e.spec().checks()).hasSize(1);
    }

    @Test
    @DisplayName("字段级判据：缺 id 会被抓出来（而不是静默降级）")
    void violationsCatchMissingFields() {
        AcceptanceSpec spec = AcceptanceSpec.parse(DIRTY);
        assertThat(spec.checks()).hasSize(1); // parse 确实抠出了结构
        List<String> violations = AcceptanceSpec.violations(spec, DIRTY);
        assertThat(violations).anyMatch(s -> s.contains("id 缺失"));
    }

    @Test
    @DisplayName("空断言集与非 JSON 输出都有明确违规")
    void violationsCatchEmptyAndNonJson() {
        assertThat(AcceptanceSpec.violations(AcceptanceSpec.empty(), "随便说点什么"))
                .anyMatch(s -> s.contains("不是 JSON 对象"))
                .anyMatch(s -> s.contains("checks 为空"));
        assertThat(AcceptanceSpec.violations(AcceptanceSpec.empty(), VALID))
                .anyMatch(s -> s.contains("checks 为空"));
    }

    @Test
    @DisplayName("定向修复提示词携带上一轮确切违规清单，复校通过后返回修复结果")
    void repairPromptCarriesExactViolations() {
        List<String> prompts = new ArrayList<>();
        StructuredOutputGuardrail.Enforcement e = guard.enforce(DIRTY, prompt -> {
            prompts.add(prompt);
            return VALID;
        }, null);

        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0)).contains("id 缺失").contains("字段契约").contains("原始输出");
        assertThat(e.repairAttempts()).isEqualTo(1);
        assertThat(e.downgraded()).isFalse();
        assertThat(e.spec().checks().get(0).id()).isEqualTo("a1");
    }

    @Test
    @DisplayName("修复耗尽后诚实降级：标记 downgraded，spec 为空，不抛异常")
    void exhaustedRepairsDowngradeHonestly() {
        AtomicInteger calls = new AtomicInteger();
        StructuredOutputGuardrail.Enforcement e = guard.enforce(DIRTY, prompt -> {
            calls.incrementAndGet();
            return DIRTY; // 每轮都还是脏的
        }, null);

        assertThat(calls.get()).isEqualTo(StructuredOutputGuardrail.MAX_REPAIR_ATTEMPTS);
        assertThat(e.downgraded()).isTrue();
        assertThat(e.spec().isEmpty()).isTrue();
        assertThat(e.remainingViolations()).isNotEmpty();
        assertThat(e.trail()).anyMatch(s -> s.contains("自校正耗尽"));
    }

    @Test
    @DisplayName("修复调用抛异常也不阻断交付")
    void repairExceptionIsTolerated() {
        StructuredOutputGuardrail.Enforcement e = guard.enforce(DIRTY, prompt -> {
            throw new IllegalStateException("模型不可用");
        }, null);
        assertThat(e.downgraded()).isTrue();
        assertThat(e.trail()).anyMatch(s -> s.contains("调用失败"));
    }

    @Test
    @DisplayName("留痕复用 onToolCall，不新增 SSE 事件名")
    void auditUsesExistingToolCallback() {
        List<String> tools = new ArrayList<>();
        List<Map<String, Object>> argsSeen = new ArrayList<>();
        com.codecanvas.agent.AgentListener listener = new com.codecanvas.agent.AgentListener() {
            public void onSession(String p, boolean c) { }
            public void onStage(com.codecanvas.agent.AgentStage s, String d) { }
            public void onStatus(String t) { }
            public void onToolCall(String tool, Map<String, Object> args, String result) {
                tools.add(tool);
                argsSeen.add(args);
            }
            public void onFile(String p, String a, int b) { }
            public void onDelta(String p, String t) { }
            public void onTokens(com.codecanvas.agent.UsageView u) { }
            public void onHeal(int a, String r, String d) { }
            public void onResult(com.codecanvas.agent.AgentResult r) { }
            public void onError(String m) { }
        };

        guard.enforce(DIRTY, prompt -> VALID, listener);
        assertThat(tools).containsExactly("guardrail_structured_output");
        assertThat(argsSeen.get(0)).containsKeys("phase", "rule", "attempts", "violations");
    }
}
