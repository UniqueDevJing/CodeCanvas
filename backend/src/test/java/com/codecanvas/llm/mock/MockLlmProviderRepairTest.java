package com.codecanvas.llm.mock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.codecanvas.common.CodeExtractor;
import com.codecanvas.common.HtmlValidator;
import com.codecanvas.context.PromptLibrary;
import com.codecanvas.llm.LlmPurpose;
import com.codecanvas.llm.LlmRequest;
import com.codecanvas.llm.LlmResponse;
import com.codecanvas.project.ProjectFile;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 离线引擎的修复能力。
 *
 * <p>它决定了"没有密钥时自检闭环还能不能真的闭环"。这里直接断言修复后的代码能通过校验，
 * 而不是只看它有没有被调用过。
 */
class MockLlmProviderRepairTest {

    private final MockLlmProvider provider = new MockLlmProvider();

    private static final String UNCLOSED = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head><meta charset="UTF-8"><title>测试</title></head>
            <body>
              <div class="wrap"><h1>标题</h1>
            </body>
            </html>
            """;

    private String fix(String code, String errorText) {
        String prompt = PromptLibrary.fixUserPrompt("目标：测试", List.of(new ProjectFile("index.html", code)), errorText);
        LlmResponse response = provider.chat(new LlmRequest(
                LlmPurpose.FIX, "mock-model", PromptLibrary.FIX_SYSTEM, prompt, 0.2, 8192));
        return CodeExtractor.extractHtml(response.text());
    }

    @Test
    @DisplayName("未闭合标签应被补全并通过校验")
    void repairsUnclosedTags() {
        String error = "1. [TAG] 以下标签没有闭合：div";
        String fixed = fix(UNCLOSED, error);

        assertTrue(fixed.contains("</div>"), "应补上缺失的闭合标签");
        assertTrue(HtmlValidator.validate(fixed).isEmpty(),
                () -> "修复后应通过校验，实际：" + HtmlValidator.describe(HtmlValidator.validate(fixed)));
    }

    @Test
    @DisplayName("沙箱禁止 localStorage 时应注入可降级的存储实现")
    void injectsFallbackStorage() {
        String error = "SecurityError: Failed to read the 'localStorage' property from 'Window'";
        String code = """
                <!DOCTYPE html>
                <html><head><meta charset="UTF-8"></head>
                <body><script>
                var saved = localStorage.getItem('k');
                localStorage.setItem('k', 'v');
                </script></body></html>
                """;
        String fixed = fix(code, error);

        assertTrue(fixed.contains("var __ccStorage = (function ()"), "应注入存储垫片");
        assertTrue(fixed.contains("__ccStorage.getItem('k')"), "业务代码里的引用应指向垫片");
        assertTrue(HtmlValidator.validate(fixed).isEmpty(), "修复后应通过校验");
    }

    @Test
    @DisplayName("修改指令应真实改在代码上且不破坏已有功能")
    void appliesModificationWithoutBreakingFeatures() {
        String original = MockAppTemplates.document(
                MockAppTemplates.build("expense", "记账工具"), null);
        String prompt = PromptLibrary.modifyUserPrompt("目标：记账", "把主色调改成蓝色，标题改成我的账本",
                List.of(new ProjectFile("index.html", original)), "（无历史）", "");

        LlmResponse response = provider.chat(new LlmRequest(
                LlmPurpose.MODIFY, "mock-model", PromptLibrary.MODIFY_SYSTEM, prompt, 0.3, 8192));
        String updated = CodeExtractor.extractHtml(response.text());

        assertTrue(updated.contains("--accent:#2563eb"), "主色应被改成蓝色");
        assertTrue(updated.contains("<h1>我的账本</h1>"), "标题应被替换");
        assertTrue(updated.contains("cc_expense_items"), "已有功能不应被破坏");
        assertFalse(updated.isBlank());
        assertTrue(HtmlValidator.validate(updated).isEmpty(), "修改后仍应通过校验");
    }
}
