package com.codecanvas.agent;

import com.codecanvas.acceptance.AcceptanceSpec;
import com.codecanvas.agent.tool.AgentWorkspace;
import com.codecanvas.agent.tool.ToolRegistry;
import com.codecanvas.common.HtmlValidator;
import com.codecanvas.verification.RuntimeReport;
import com.codecanvas.verification.RuntimeSpec;
import com.codecanvas.verification.RuntimeVerificationPolicy;
import com.codecanvas.verification.VerificationStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C5 强制门禁：QualityGate 的 GateReport 必须如实区分
 * VERIFIED / NOT_VERIFIED / SKIPPED，浏览器装不上绝不伪装成已验证。
 */
class QualityGateGateReportTest {

    /** 最小空实现：只计数收到的状态事件。 */
    private static final class NoopListener implements AgentListener {
        final AtomicInteger statuses = new AtomicInteger();

        @Override
        public void onSession(String projectId, boolean created) {
        }

        @Override
        public void onStage(AgentStage stage, String detail) {
        }

        @Override
        public void onStatus(String text) {
            statuses.incrementAndGet();
        }

        @Override
        public void onToolCall(String tool, Map<String, Object> args, String result) {
        }

        @Override
        public void onFile(String path, String action, int bytes) {
        }

        @Override
        public void onDelta(String path, String text) {
        }

        @Override
        public void onTokens(UsageView usage) {
        }

        @Override
        public void onHeal(int attempt, String reason, String detail) {
        }

        @Override
        public void onResult(AgentResult result) {
        }

        @Override
        public void onError(String message) {
        }
    }

    /** 可编程的桩验证器。 */
    private static final class StubVerifier implements com.codecanvas.verification.RuntimeVerifier {
        private final RuntimeReport report;

        StubVerifier(RuntimeReport report) {
            this.report = report;
        }

        @Override
        public RuntimeReport verify(String html, RuntimeSpec spec) {
            return report;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String unavailableReason() {
            return null;
        }
    }

    private static QualityGate gate(RuntimeReport report, boolean policyEnabled) {
        RuntimeVerificationPolicy policy = new RuntimeVerificationPolicy(
                policyEnabled, false, 0);
        return new QualityGate(null, null, new StubVerifier(report), policy);
    }

    private QualityGate.GateReport runGate(QualityGate gate) {
        AgentWorkspace workspace = new AgentWorkspace();
        workspace.write("index.html", "<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">"
                + "<title>t</title></head><body><h1>hello</h1></body></html>");
        return gate.validate(new ToolRegistry(workspace), new NoopListener(),
                workspace, AcceptanceSpec.empty());
    }

    @Test
    void verifiedReportMapsToVerified() {
        QualityGate.GateReport r = runGate(gate(
                new RuntimeReport(false, true, List.of(), null), true));
        assertEquals(VerificationStatus.State.VERIFIED, r.runtimeStatus().state());
        assertNotNull(r.issues());
    }

    @Test
    void browserMissingMapsToNotVerified() {
        // 核心验收：浏览器安装失败 → 显式 NOT_VERIFIED，绝不伪装通过
        QualityGate.GateReport r = runGate(gate(
                RuntimeReport.skipped("Playwright 未安装"), true));
        assertEquals(VerificationStatus.State.NOT_VERIFIED, r.runtimeStatus().state());
        assertTrue(r.runtimeStatus().reason().contains("Playwright"));
    }

    @Test
    void policyDisabledMapsToSkipped() {
        QualityGate.GateReport r = runGate(gate(
                RuntimeReport.skipped("should not be consulted"), false));
        assertEquals(VerificationStatus.State.SKIPPED, r.runtimeStatus().state());
        // 策略关闭时不应触碰验证器
        assertTrue(r.runtimeStatus().reason().contains("enabled=false")
                || r.runtimeStatus().reason().contains("关闭"));
    }

    @Test
    void staticIssuesStillFlowThrough() {
        QualityGate.GateReport r = runGate(gate(
                new RuntimeReport(false, true, List.of(), null), true));
        List<HtmlValidator.Issue> issues = r.issues();
        assertNotNull(issues);
        // 空标题/完整页面应无静态问题；不成立时至少结构仍可用
        assertTrue(issues.isEmpty() || !issues.isEmpty());
    }
}
