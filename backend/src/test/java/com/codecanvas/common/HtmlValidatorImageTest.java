package com.codecanvas.common;

import com.codecanvas.common.HtmlValidator.CheckResult;
import com.codecanvas.common.HtmlValidator.Issue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 图片可用性校验（W2）。
 *
 * <p>被测的是"生成式页面最容易翻车的两条"：图裂（src 空/缺失）与 alt 缺失。
 * 它们必须变成 {@code IMAGE} 类 issue，才能被 heal 回路消费。
 */
class HtmlValidatorImageTest {

    private static String page(String imgTag) {
        return "<!DOCTYPE html><html><head><title>t</title></head><body>"
                + imgTag + "<h1>标题</h1></body></html>";
    }

    private static List<String> imageIssues(String html) {
        return HtmlValidator.validate(html).stream()
                .filter(i -> "IMAGE".equals(i.type()))
                .map(Issue::message)
                .toList();
    }

    @Test
    @DisplayName("img 带非空 alt 与 src → 无 IMAGE issue")
    void validImagePasses() {
        assertThat(imageIssues(page("<img src=\"/v1/assets/abc.png\" alt=\"海边插画\">"))).isEmpty();
    }

    @Test
    @DisplayName("img 缺 alt → IMAGE issue")
    void missingAltReported() {
        assertThat(imageIssues(page("<img src=\"/v1/assets/abc.png\">")))
                .anySatisfy(msg -> assertThat(msg).contains("alt"));
    }

    @Test
    @DisplayName("img 空 alt → IMAGE issue")
    void blankAltReported() {
        assertThat(imageIssues(page("<img src=\"/x.png\" alt=\"  \">"))).isNotEmpty();
    }

    @Test
    @DisplayName("img 缺 src 或 src 为空 → IMAGE issue")
    void missingOrBlankSrcReported() {
        assertThat(imageIssues(page("<img alt=\"图\">"))).anySatisfy(msg -> assertThat(msg).contains("src"));
        assertThat(imageIssues(page("<img src=\"\" alt=\"图\">"))).isNotEmpty();
    }

    @Test
    @DisplayName("单引号属性与无引号属性都能识别")
    void handlesQuoteVariants() {
        assertThat(imageIssues(page("<img src='/a.png' alt='图'>"))).isEmpty();
        assertThat(imageIssues(page("<img alt=图 src=/a.png>"))).isEmpty();
    }

    @Test
    @DisplayName("多张图：只报有问题的那张")
    void reportsOnlyBrokenImage() {
        List<String> issues = imageIssues(page(
                "<img src=\"/ok.png\" alt=\"好图\"><img src=\"\" alt=\"\">"));
        assertThat(issues).hasSize(2);
    }

    @Test
    @DisplayName("report() 增加 images 检查项，并通过/失败可判定")
    void reportIncludesImagesCheck() {
        List<CheckResult> ok = HtmlValidator.report(
                page("<img src=\"/a.png\" alt=\"图\">"));
        assertThat(ok).anySatisfy(r -> {
            assertThat(r.id()).isEqualTo("images");
            assertThat(r.passed()).isTrue();
        });

        List<CheckResult> bad = HtmlValidator.report(page("<img src=\"/a.png\">"));
        assertThat(bad).anySatisfy(r -> {
            assertThat(r.id()).isEqualTo("images");
            assertThat(r.passed()).isFalse();
        });
    }
}