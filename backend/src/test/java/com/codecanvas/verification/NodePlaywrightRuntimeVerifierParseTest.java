package com.codecanvas.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 验证器对 Node 脚本输出 JSON 的解析（不依赖真实浏览器）。
 *
 * <p>这部分逻辑是"执行级验证闭环"的关键解析环节，必须独立于 Playwright 也能断言——
 * 否则只能靠手动跑端到端才能发现回归。
 */
class NodePlaywrightRuntimeVerifierParseTest {

    /** 一个指向不存在脚本的实例：available()=false，但 parseReport 仍可独立测试。 */
    private final NodePlaywrightRuntimeVerifier verifier =
            new NodePlaywrightRuntimeVerifier("node", "does-not-exist.mjs", 15000);

    @Test
    @DisplayName("解析含 PAGE_CRASH 与 SKIPPED 的混合报告")
    void parsesMixedReport() {
        String json = "{"
                + "\"skipped\":false,\"verified\":true,\"screenshotPath\":\"/tmp/shot.png\","
                + "\"issues\":["
                + "{\"type\":\"PAGE_CRASH\",\"message\":\"boom\",\"source\":\"index.html\",\"line\":7,\"stack\":\"s\"},"
                + "{\"type\":\"SKIPPED\",\"message\":\"无浏览器\"}"
                + "]}";

        RuntimeReport report = verifier.parseReport(json);
        assertFalse(report.skipped());
        assertTrue(report.verified());
        assertEquals("/tmp/shot.png", report.screenshotPath());
        assertEquals(2, report.issues().size());
        assertEquals(RuntimeIssueType.PAGE_CRASH, report.issues().get(0).type());
        assertEquals(7, report.issues().get(0).line());
        assertEquals(RuntimeIssueType.SKIPPED, report.issues().get(1).type());
        // SKIPPED 被阻断项筛选排除
        assertEquals(1, report.blockingIssues().size());
    }

    @Test
    @DisplayName("空输入降级为 skipped，不抛异常")
    void emptyInputBecomesSkipped() {
        RuntimeReport report = verifier.parseReport("");
        assertTrue(report.skipped());
        assertTrue(report.blockingIssues().isEmpty());
    }

    @Test
    @DisplayName("未知问题类型被当作 SKIPPED 兜底")
    void unknownTypeFallsBackToSkipped() {
        String json = "{\"skipped\":false,\"verified\":true,\"issues\":"
                + "[{\"type\":\"TOTALLY_UNKNOWN\",\"message\":\"x\"}]}";
        RuntimeReport report = verifier.parseReport(json);
        assertEquals(1, report.issues().size());
        assertEquals(RuntimeIssueType.SKIPPED, report.issues().get(0).type());
    }

    @Test
    @DisplayName("缺失字段使用安全默认值（source/stack 可为 null）")
    void missingFieldsUseDefaults() {
        String json = "{\"issues\":[{\"type\":\"CONSOLE_ERROR\",\"message\":\"err\"}]}";
        RuntimeReport report = verifier.parseReport(json);
        assertEquals(RuntimeIssueType.CONSOLE_ERROR, report.issues().get(0).type());
        assertNull(report.issues().get(0).source());
        assertNull(report.issues().get(0).stack());
    }
}
