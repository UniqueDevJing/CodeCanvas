package com.codecanvas.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 报告的阻断项筛选与摘要。 */
class RuntimeReportTest {

    @Test
    @DisplayName("blockingIssues 排除 SKIPPED，只留真正的问题")
    void filtersSkipped() {
        RuntimeReport report = new RuntimeReport(false, true,
                List.of(
                        new RuntimeIssue(RuntimeIssueType.PAGE_CRASH, "boom", "index.html", 1, null),
                        RuntimeIssue.skipped("无浏览器")),
                null);
        assertEquals(1, report.blockingIssues().size(), "SKIPPED 不应计入阻断项");
        assertEquals(RuntimeIssueType.PAGE_CRASH, report.blockingIssues().get(0).type());
    }

    @Test
    @DisplayName("summary 在跳过 / 通过 / 有问题时给出不同措辞")
    void summaryVariants() {
        assertEquals("运行时验证已跳过（无浏览器）",
                RuntimeReport.skipped("无浏览器").summary());

        RuntimeReport passed = new RuntimeReport(false, true, List.of(), null);
        assertTrue(passed.summary().contains("通过"), "通过时摘要应含「通过」");

        RuntimeReport withIssue = new RuntimeReport(false, true,
                List.of(new RuntimeIssue(RuntimeIssueType.BUTTON_NOOP, "点了没反应", "index.html", 0, null)),
                null);
        assertTrue(withIssue.summary().contains("点了没反应"), "有问题时摘要应带首条信息");
        assertFalse(withIssue.skipped());
    }
}
