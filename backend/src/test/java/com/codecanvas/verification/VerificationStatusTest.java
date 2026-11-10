package com.codecanvas.verification;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C5 强制门禁：验证状态三态推导测试。 */
class VerificationStatusTest {

    @Test
    void policyDisabledMeansSkipped() {
        VerificationStatus s = VerificationStatus.from(
                RuntimeReport.skipped("whatever"), false);
        assertEquals(VerificationStatus.State.SKIPPED, s.state());
        assertTrue(s.display().contains("跳过"));
        assertFalse(s.attempted());
    }

    @Test
    void skippedReportMeansNotVerified() {
        VerificationStatus s = VerificationStatus.from(
                RuntimeReport.skipped("Chromium 未安装"), true);
        assertEquals(VerificationStatus.State.NOT_VERIFIED, s.state());
        assertEquals("Chromium 未安装", s.reason());
        assertTrue(s.attempted());
        assertFalse(s.isVerified());
        assertTrue(s.display().contains("未完成"), "必须显式说'未完成'，不能说成通过");
    }

    @Test
    void verifiedReportMeansVerified() {
        VerificationStatus s = VerificationStatus.from(
                new RuntimeReport(false, true, java.util.List.of(), null), true);
        assertEquals(VerificationStatus.State.VERIFIED, s.state());
        assertTrue(s.isVerified());
    }

    @Test
    void ranButIncompleteMeansNotVerified() {
        VerificationStatus s = VerificationStatus.from(
                new RuntimeReport(false, false, java.util.List.of(), null), true);
        assertEquals(VerificationStatus.State.NOT_VERIFIED, s.state());
    }

    @Test
    void missingReportMeansNotVerified() {
        VerificationStatus s = VerificationStatus.from(null, true);
        assertEquals(VerificationStatus.State.NOT_VERIFIED, s.state());
        assertTrue(s.reason() != null && !s.reason().isBlank(), "未验证必须有原因");
    }

    @Test
    void blankReasonGetsDefaultText() {
        VerificationStatus s = VerificationStatus.notVerified("  ");
        assertTrue(s.reason().contains("未完成"));
    }
}
