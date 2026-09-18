package com.codecanvas.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.codecanvas.llm.LlmPurpose;
import com.codecanvas.llm.LlmRequest;
import com.codecanvas.llm.LlmResponse;
import com.codecanvas.llm.StreamListener;
import com.codecanvas.llm.mock.MockLlmProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;

/**
 * 编排链路的端到端行为。
 *
 * <p>用"第一次生成故意返回坏代码"的提供方来验证自检闭环：校验 → 回注 → 修复 → 重新校验，
 * 这条路径不靠人工观察，而是用断言把它钉住。
 */
@SpringBootTest
@TestPropertySource(properties = {
        "codecanvas.storage.dir=target/test-data",
        "codecanvas.llm.mode=mock"
})
class AgentOrchestrationTest {

    /** 故意留一个未闭合的 div，用来触发静态校验失败。 */
    private static final String BROKEN = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head><meta charset="UTF-8"><title>结构测试</title></head>
            <body>
              <div class="wrap"><h1>结构测试页面</h1>
            </body>
            </html>
            """;

    /** 只有需求里带上这个标记时，提供方才返回坏代码，避免影响其他用例。 */
    private static final String BREAK_MARKER = "结构测试";

    @TestConfiguration
    static class BrokenOutputProviderConfig {

        @Bean
        @Primary
        MockLlmProvider brokenOutputProvider() {
            return new MockLlmProvider() {
                private final AtomicInteger generated = new AtomicInteger();

                @Override
                public void chatStream(LlmRequest request, StreamListener listener) {
                    boolean target = request.userPrompt() != null && request.userPrompt().contains(BREAK_MARKER);
                    if (target && request.purpose() == LlmPurpose.CODE && generated.incrementAndGet() == 1) {
                        listener.onDelta(BROKEN);
                        listener.onComplete(LlmResponse.of(BROKEN, 20, 40, request.model(), "mock", true));
                        return;
                    }
                    super.chatStream(request, listener);
                }
            };
        }
    }

    @Autowired
    private AgentOrchestrator orchestrator;

    @Test
    @DisplayName("生成结果不合格时应触发自检修复并恢复")
    void healsBrokenGeneration() {
        RecordingListener listener = new RecordingListener();
        orchestrator.run(AgentRunRequest.user(null, "做一个" + BREAK_MARKER + "的页面"), listener);

        assertNotNull(listener.result, "应产出结果");
        assertTrue(listener.heals.size() >= 1, "应触发自检修复，实际未触发");
        assertTrue(listener.result.recovered(), "修复后应通过校验");
        assertFalse(listener.result.toolTrace().isEmpty(), "应留下工具调用轨迹");
        assertTrue(listener.result.files().get(0).content().contains("</div>"), "缺失的闭合标签应被补上");
        assertEquals("v1", listener.result.versionId(), "应生成第 1 版");
    }

    @Test
    @DisplayName("沙箱拒绝 localStorage 时应自动降级存储实现")
    void recoversFromSandboxStorageDenial() {
        RecordingListener created = new RecordingListener();
        orchestrator.run(AgentRunRequest.user(null, "做一个记账小工具"), created);
        String projectId = created.result.projectId();

        RecordingListener healed = new RecordingListener();
        orchestrator.run(new AgentRunRequest(projectId, "", AgentRunRequest.Trigger.RUNTIME_ERROR,
                List.of(new AgentRunRequest.RuntimeError(
                        "SecurityError: Failed to read the 'localStorage' property from 'Window'",
                        "index.html", 103, ""))), healed);

        assertNotNull(healed.result, "应产出结果");
        String content = healed.result.files().get(0).content();
        assertTrue(content.contains("__ccStorage"), "应注入可降级的存储实现");
        assertTrue(content.contains("var __ccStorage = (function ()"), "存储垫片本身必须保持完整，不能被替换逻辑污染");
    }

    @Test
    @DisplayName("同一个项目连续迭代应累积版本并保留 PRD")
    void keepsVersionsAndPrdAcrossTurns() {
        RecordingListener created = new RecordingListener();
        orchestrator.run(AgentRunRequest.user(null, "做一个待办清单"), created);
        String projectId = created.result.projectId();

        RecordingListener second = new RecordingListener();
        orchestrator.run(AgentRunRequest.user(projectId, "把主色调改成绿色"), second);

        assertEquals("v2", second.result.versionId(), "第二轮应生成第 2 版");
        assertFalse(second.result.summary().isBlank(), "应产出可读的变更摘要");
        assertNotNull(second.lastUsage, "应推送用量与上下文信息");
        assertTrue(second.lastUsage.contextRaw() > 0, "应能估算出未压缩上下文规模");
    }

    @Test
    @DisplayName("在已有项目里重新描述应用应按重建处理，而不是打补丁")
    void treatsFreshRequirementAsRebuild() {
        RecordingListener created = new RecordingListener();
        orchestrator.run(AgentRunRequest.user(null, "做一个记账小工具"), created);
        String projectId = created.result.projectId();

        RecordingListener rebuild = new RecordingListener();
        orchestrator.run(AgentRunRequest.user(projectId, "做一个番茄钟专注计时器"), rebuild);

        // 断言"有明确告知用户这是重建"，而不是绑定具体措辞——
        // 界面文案会随产品化调整，测试不该因此变红
        assertTrue(rebuild.statuses.stream().anyMatch(s -> s.contains("全新的应用")),
                "应告知用户这是重建而不是打补丁，实际状态：" + rebuild.statuses);
        String content = rebuild.result.files().get(0).content();
        assertTrue(content.contains("番茄钟"), "应生成番茄钟页面而不是在记账页上追加内容");
        assertEquals("v2", rebuild.result.versionId(), "重建同样应产生新版本，可回溯");
    }

    /** 收集全部事件，便于断言编排过程中的行为。 */
    static class RecordingListener implements AgentListener {

        final List<String> stages = new ArrayList<>();
        final List<String> statuses = new ArrayList<>();
        final List<String> toolCalls = new ArrayList<>();
        final List<Integer> heals = new ArrayList<>();
        AgentResult result;
        UsageView lastUsage;
        String error;

        @Override
        public void onSession(String projectId, boolean created) {
        }

        @Override
        public void onStage(AgentStage stage, String detail) {
            stages.add(stage.name());
        }

        @Override
        public void onStatus(String text) {
            statuses.add(text);
        }

        @Override
        public void onToolCall(String tool, Map<String, Object> args, String result) {
            toolCalls.add(tool);
        }

        @Override
        public void onFile(String path, String action, int bytes) {
        }

        @Override
        public void onDelta(String path, String text) {
        }

        @Override
        public void onTokens(UsageView usage) {
            this.lastUsage = usage;
        }

        @Override
        public void onHeal(int attempt, String reason, String detail) {
            heals.add(attempt);
        }

        @Override
        public void onResult(AgentResult result) {
            this.result = result;
        }

        @Override
        public void onError(String message) {
            this.error = message;
        }
    }
}
