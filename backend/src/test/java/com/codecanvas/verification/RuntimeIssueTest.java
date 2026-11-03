package com.codecanvas.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.codecanvas.common.HtmlValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 运行时问题的分类与"回注"形态。 */
class RuntimeIssueTest {

    @Test
    @DisplayName("SKIPPED 不阻断，其余都阻断")
    void blockingDiscriminatesSkipped() {
        assertFalse(RuntimeIssueType.SKIPPED.blocking(), "跳过不应阻断交付");
        for (RuntimeIssueType type : RuntimeIssueType.values()) {
            if (type != RuntimeIssueType.SKIPPED) {
                assertTrue(type.blocking(), type + " 应阻断交付");
            }
        }
    }

    @Test
    @DisplayName("toValidatorIssue 把运行时问题映射成修复闭环能消费的 Issue")
    void mapsToValidatorIssue() {
        RuntimeIssue issue = new RuntimeIssue(RuntimeIssueType.PAGE_CRASH,
                "Cannot read properties of null", "index.html", 42, "stack");
        HtmlValidator.Issue mapped = issue.toValidatorIssue();
        assertEquals("RUNTIME_PAGE_CRASH", mapped.type());
        assertEquals("Cannot read properties of null", mapped.message());
    }

    @Test
    @DisplayName("skipped 工厂产出 SKIPPED 类型且 null 字段安全")
    void skippedFactory() {
        RuntimeIssue issue = RuntimeIssue.skipped("无浏览器");
        assertEquals(RuntimeIssueType.SKIPPED, issue.type());
        assertFalse(issue.blocking());
        assertEquals("无浏览器", issue.message());
    }
}
