package com.codecanvas.common;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 静态校验器的行为约束。它是自检闭环的判定依据，误报会引发无意义的重试，漏报会让坏代码交付出去。 */
class HtmlValidatorTest {

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

    @Test
    @DisplayName("完整页面应通过校验")
    void acceptsValidDocument() {
        assertTrue(HtmlValidator.validate(VALID).isEmpty(),
                () -> "不应报错，实际：" + HtmlValidator.describe(HtmlValidator.validate(VALID)));
    }

    @Test
    @DisplayName("未闭合标签应被检出")
    void detectsUnclosedTag() {
        String broken = VALID.replace("<div class=\"wrap\"><h1>标题</h1></div>", "<div class=\"wrap\"><h1>标题</h1>");
        List<HtmlValidator.Issue> issues = HtmlValidator.validate(broken);
        assertTrue(issues.stream().anyMatch(i -> "TAG".equals(i.type())), "应报出标签问题");
        assertTrue(HtmlValidator.describe(issues).contains("div"), "错误信息应指出具体标签名");
    }

    @Test
    @DisplayName("脚本括号不配平应被检出")
    void detectsUnbalancedScript() {
        String broken = VALID.replace("function f() { return 'ok'; }", "function f() { return 'ok';");
        List<HtmlValidator.Issue> issues = HtmlValidator.validate(broken);
        assertTrue(issues.stream().anyMatch(i -> "SCRIPT".equals(i.type())), "应报出脚本问题");
    }

    @Test
    @DisplayName("字符串与注释里的括号不应造成误报")
    void ignoresBracketsInsideStringsAndComments() {
        String tricky = VALID.replace("var a = { x: [1, 2] };",
                "var a = { x: [1, 2] };\nvar s = '未闭合的 { 不是语法错误';\n// 注释里的 ) 也不算");
        assertTrue(HtmlValidator.validate(tricky).isEmpty(),
                () -> "不应误报，实际：" + HtmlValidator.describe(HtmlValidator.validate(tricky)));
    }

    @Test
    @DisplayName("残留代码围栏与占位内容应被检出")
    void detectsFenceAndPlaceholder() {
        assertFalse(HtmlValidator.validate("```html\n" + VALID + "\n```").isEmpty(), "围栏应被检出");
        assertFalse(HtmlValidator.validate(VALID.replace("<h1>标题</h1>", "<h1>标题</h1><!-- 此处省略 -->"))
                .isEmpty(), "占位内容应被检出");
    }

    @Test
    @DisplayName("空内容应被检出")
    void detectsEmpty() {
        assertFalse(HtmlValidator.validate("").isEmpty());
        assertFalse(HtmlValidator.validate(null).isEmpty());
    }
}
