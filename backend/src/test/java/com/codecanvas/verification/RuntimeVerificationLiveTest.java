package com.codecanvas.verification;

import com.codecanvas.common.HtmlValidator;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P1-4：执行级验证的<b>真跑</b>用例。
 *
 * <p>不做 mock：真的起 Node + Playwright + Chromium，把页面加载一遍，
 * 验证"页面抛异常 → 被抓成 PAGE_CRASH → 并入自检修复清单"这条链路。
 * 环境不具备浏览器时用 {@link Assumptions#assumeTrue(boolean, String)} 跳过，
 * 而不是让 CI 变红——这与主流程"无浏览器则诚实降级"是同一原则。
 */
class RuntimeVerificationLiveTest {

    private NodePlaywrightRuntimeVerifier verifier() {
        return new NodePlaywrightRuntimeVerifier("node", "tools/runtime-verify.mjs", 15000);
    }

    private static RuntimeSpec minimal() {
        return new RuntimeSpec(false, false, List.of(), List.of(), null, 50);
    }

    @Test
    @DisplayName("能力探测是真的：走完整条 Node → Playwright → Chromium 链路才算可用")
    void capabilityProbeIsReal() {
        NodePlaywrightRuntimeVerifier v = verifier();
        Assumptions.assumeTrue(v.available(),
                "环境未安装 Playwright / Chromium，跳过实跑用例");
        assertThat(v.unavailableReason()).isNull();
        assertThat(v.parseReport("{\"skipped\":false,\"verified\":true,\"issues\":[]}").verified()).isTrue();
    }

    @Test
    @DisplayName("真跑：页面未捕获异常被抓成 PAGE_CRASH，并并入自检修复清单")
    void crashingPageIsDetectedAndMerged() {
        NodePlaywrightRuntimeVerifier v = verifier();
        Assumptions.assumeTrue(v.available(), "环境未安装 Playwright / Chromium，跳过实跑用例");

        String badHtml = """
                <!DOCTYPE html><html><body><h1>boom</h1>
                <script>throw new Error('codecanvas-live-boom')</script>
                </body></html>
                """;
        RuntimeReport report = v.verify(badHtml, minimal());

        assertThat(report.skipped()).isFalse();
        assertThat(report.verified()).isTrue();
        assertThat(report.issues()).anyMatch(i -> i.message().contains("codecanvas-live-boom"));

        List<HtmlValidator.Issue> merged = new RuntimeVerificationPolicy(true, false, 300)
                .merge(List.of(), report);
        assertThat(merged).anyMatch(i -> i.type().equals("RUNTIME_PAGE_CRASH"));
    }

    @Test
    @DisplayName("真跑：正常页面零运行时问题，不会制造无意义的修复轮次")
    void healthyPagePasses() {
        NodePlaywrightRuntimeVerifier v = verifier();
        Assumptions.assumeTrue(v.available(), "环境未安装 Playwright / Chromium，跳过实跑用例");

        String goodHtml = "<!DOCTYPE html><html><body><h1>hello</h1>"
                + "<button onclick=\"this.textContent='ok'\">go</button></body></html>";
        RuntimeReport report = v.verify(goodHtml,
                new RuntimeSpec(true, false, List.of("hello"), List.of("button"), null, 50));

        assertThat(report.skipped()).isFalse();
        assertThat(report.blockingIssues()).isEmpty();
    }

    @Test
    @DisplayName("回归：settleMs 不再被进程超时污染（以前误传 15000ms）")
    void settleMsIsNotProcessTimeout() {
        RuntimeSpec spec = new RuntimeVerificationPolicy(true, true, 15000).defaultSpec();
        assertThat(spec.settleMs()).isLessThanOrEqualTo(2000);
    }
}
