package com.codecanvas.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.codecanvas.common.HtmlValidator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 总开关与"静态 + 运行时"合并策略。 */
class RuntimeVerificationPolicyTest {

    @Test
    @DisplayName("enabled 由构造参数决定")
    void respectsEnabledFlag() {
        assertTrue(new RuntimeVerificationPolicy(true, true, 300).enabled());
        assertFalse(new RuntimeVerificationPolicy(false, true, 300).enabled());
    }

    @Test
    @DisplayName("defaultSpec 默认点主按钮、不瞎断言文案/点击变化（避免误报）")
    void defaultSpecIsConservative() {
        RuntimeSpec spec = new RuntimeVerificationPolicy(true, true, 300).defaultSpec();
        assertTrue(spec.clickMainButton());
        assertFalse(spec.assertClickChanges(), "默认不断言点击后 DOM 必须变化，避免对合法落地页误报");
        assertTrue(spec.expectTexts().isEmpty(), "默认不盲目断言文案，避免误报");
    }

    @Test
    @DisplayName("merge 把运行时阻断项并入静态清单，但排除 SKIPPED")
    void mergesBlockingRuntimeIssues() {
        RuntimeVerificationPolicy policy = new RuntimeVerificationPolicy(true, true, 300);
        List<HtmlValidator.Issue> statics = List.of(new HtmlValidator.Issue("TAG", "未闭合标签"));

        RuntimeReport report = new RuntimeReport(false, true,
                List.of(
                        new RuntimeIssue(RuntimeIssueType.PAGE_CRASH, "null 引用", "index.html", 3, null),
                        RuntimeIssue.skipped("无浏览器")),
                null);

        List<HtmlValidator.Issue> merged = policy.merge(statics, report);
        assertEquals(2, merged.size(), "静态 1 + 运行时阻断 1（SKIPPED 排除）");
        assertTrue(merged.stream().anyMatch(i -> i.type().equals("RUNTIME_PAGE_CRASH")));
    }

    @Test
    @DisplayName("merge 对 skipped 报告只保留静态项（降级时不污染修复闭环）")
    void mergeSkippedKeepsStaticOnly() {
        RuntimeVerificationPolicy policy = new RuntimeVerificationPolicy(true, true, 300);
        List<HtmlValidator.Issue> statics = List.of(new HtmlValidator.Issue("TAG", "未闭合标签"));

        List<HtmlValidator.Issue> merged = policy.merge(statics, RuntimeReport.skipped("无浏览器"));
        assertEquals(1, merged.size());
        assertEquals("TAG", merged.get(0).type());
    }
}
