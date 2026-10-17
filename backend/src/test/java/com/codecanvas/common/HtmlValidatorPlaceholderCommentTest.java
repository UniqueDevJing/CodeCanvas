package com.codecanvas.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 占位符检测的注释边界。
 *
 * <p>{@link HtmlValidator} 是自检修复闭环的判定依据。原先对整份 HTML 做
 * {@code contains} 纯文本匹配，把**注释里的 TODO**当成未实现的占位内容：
 *
 * <pre>
 *   &lt;script&gt;// TODO: 后续接入支付&lt;/script&gt;
 *   &lt;!-- TODO: 补充响应式断点 --&gt;
 *   &lt;style&gt;/* TODO: 调整配色 *\/&lt;/style&gt;
 * </pre>
 *
 * <p>这三种都是开发者留下的正常注释，不是「模型偷懒没写完」。
 *
 * <p>误报的后果比漏报更糟：判定 PLACEHOLDER 失败 → 进入修复闭环 →
 * 模型被要求「补全占位内容」→ 为消除告警而编造并不需要的功能代码。
 * 这与项目「首轮通过率」的目标直接冲突，且会浪费一整轮生成时间。
 */
class HtmlValidatorPlaceholderCommentTest {

    private static final String VALID = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head><meta charset="UTF-8"><title>t</title></head>
            <body>
              <div class="wrap"><h1>标题</h1></div>
              <script>
                var a = { x: [1, 2] };
                function f() { return 'ok'; }
              </script>
            </body>
            </html>
            """;

    private static List<HtmlValidator.Issue> validate(String html) {
        return HtmlValidator.validate(html);
    }

    // ────────────────── 修复点：注释里的 TODO 不应误报 ──────────────────

    @Test
    @DisplayName("JS 行注释里的 TODO 不应判为占位内容")
    void todoInJsLineCommentIsIgnored() {
        String html = VALID.replace("var a = { x: [1, 2] };",
                "// TODO: 后续接入支付\n    var a = { x: [1, 2] };");
        assertTrue(validate(html).isEmpty(),
                () -> "注释里的 TODO 不应触发 PLACEHOLDER，实际："
                        + HtmlValidator.describe(validate(html)));
    }

    @Test
    @DisplayName("CSS 块注释里的 TODO 不应判为占位内容")
    void todoInCssBlockCommentIsIgnored() {
        String html = VALID.replace("<div class=\"wrap\">",
                "<style>/* TODO: 补充响应式断点 */ .a { color: red; }</style><div class=\"wrap\">");
        assertTrue(validate(html).isEmpty(),
                () -> "CSS 注释里的 TODO 不应触发 PLACEHOLDER，实际："
                        + HtmlValidator.describe(validate(html)));
    }

    @Test
    @DisplayName("HTML 注释里的 TODO 不应判为占位内容")
    void todoInHtmlCommentIsIgnored() {
        String html = VALID.replace("<h1>标题</h1>", "<h1>标题</h1><!-- TODO: 补充移动端适配 -->");
        assertTrue(validate(html).isEmpty(),
                () -> "HTML 注释里的 TODO 不应触发 PLACEHOLDER，实际："
                        + HtmlValidator.describe(validate(html)));
    }

    @Test
    @DisplayName("注释里的中文占位词也不应误报")
    void chinesePlaceholderInCommentIsIgnored() {
        String html = VALID.replace("<h1>标题</h1>",
                "<h1>标题</h1><!-- 此处省略 -->");
        // 注意：中文占位词放在**注释**里同样只是说明文字
        assertFalse(hasPlaceholderIssue(validate(html)),
                () -> "注释里的「此处省略」不应触发 PLACEHOLDER，实际："
                        + HtmlValidator.describe(validate(html)));
    }

    // ────────────────── 防回归：正文里的真占位仍须检出 ──────────────────

    @Test
    @DisplayName("正文（非注释）里的占位内容仍被检出")
    void realPlaceholderStillDetected() {
        String html = VALID.replace("<h1>标题</h1>", "<h1>此处省略</h1>");
        assertTrue(hasPlaceholderIssue(validate(html)),
                () -> "正文里的占位内容必须被检出，实际：" + HtmlValidator.describe(validate(html)));
    }

    @Test
    @DisplayName("正文里的 TODO 标记仍被检出")
    void todoInBodyStillDetected() {
        String html = VALID.replace("<h1>标题</h1>", "<h1>TODO: 实现标题</h1>");
        assertTrue(hasPlaceholderIssue(validate(html)),
                () -> "正文里的 TODO 必须被检出，实际：" + HtmlValidator.describe(validate(html)));
    }

    @Test
    @DisplayName("注释与正文混合时，正文里的占位仍须检出")
    void placeholderInBodyDetectedEvenWithCommentsAround() {
        String html = VALID
                .replace("<h1>标题</h1>", "<!-- TODO: 这是注释里的 --><h1>其他功能类似</h1>");
        assertTrue(hasPlaceholderIssue(validate(html)),
                () -> "正文里的占位必须被检出，实际：" + HtmlValidator.describe(validate(html)));
    }

    // ────────────────── 剥离注释不得破坏其他校验 ──────────────────

    @Test
    @DisplayName("剥离注释后标签闭合检查仍生效")
    void tagBalanceStillWorksAfterCommentStripping() {
        String html = VALID.replace("<h1>标题</h1>", "<h1>标题</h1><!-- <div> --></div>");
        // 注释里的 <div> 不参与闭合计数；正文里未闭合的 </div> 应被检出
        assertFalse(validate(html).isEmpty() && hasTagIssue(validate(html)),
                "正文里未闭合的 </div> 应被检出");
    }

    @Test
    @DisplayName("三种注释形式都能被剥离且不产生 PLACEHOLDER")
    void stripsAllCommentForms() {
        for (String comment : new String[]{"<!-- x -->", "// x", "/* x */"}) {
            String html = VALID.replace("<h1>标题</h1>", "<h1>标题</h1>" + comment);
            assertFalse(hasPlaceholderIssue(validate(html)),
                    () -> "「" + comment + "」形式的注释不应产生 PLACEHOLDER，实际："
                            + HtmlValidator.describe(validate(html)));
        }
    }

    // ────────────────── 回归：URL 里的 // 不能被当注释 ──────────────────

    @Test
    @DisplayName("URL 中的双斜杠不应被当作行注释吃掉")
    void urlIsNotTreatedAsComment() {
        // stripComments 若在标签外剥离 //，会把 https://cdn... 之后的内容全部吃掉，
        // 导致后续标签闭合检查失准（这是实现该修复时自查发现的第二个风险点）。
        String html = VALID.replace("<h1>标题</h1>",
                "<h1>标题</h1><img src=\"https://cdn.example.com/a.png\"><div>内容</div>");
        assertTrue(validate(html).isEmpty(),
                () -> "URL 不应影响校验，实际：" + HtmlValidator.describe(validate(html)));
    }

    @Test
    @DisplayName("URL 之后的内容仍参与标签闭合检查")
    void tagsAfterUrlStillChecked() {
        // URL 之后的未闭合标签必须被检出 —— 若 // 误吞了后续内容，这条就会漏报
        String html = VALID.replace("<h1>标题</h1>",
                "<h1>标题</h1><a href=\"https://x.com\">链接</a><section>");
        assertFalse(validate(html).isEmpty(),
                "URL 之后未闭合的 <section> 应被检出（实际：" + HtmlValidator.describe(validate(html)) + "）");
    }

    private static boolean hasPlaceholderIssue(List<HtmlValidator.Issue> issues) {
        return issues.stream().anyMatch(i -> "PLACEHOLDER".equals(i.type()));
    }

    private static boolean hasTagIssue(List<HtmlValidator.Issue> issues) {
        return issues.stream().anyMatch(i -> i.type().toLowerCase().contains("tag")
                || i.message().contains("闭合"));
    }
}
