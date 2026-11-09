package com.codecanvas.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P0-2 MCP 工具层契约测试。
 *
 * <p>为什么必须测 {@code ToolCallback.call(...)} 而不只测 Java 方法：MCP Server 走的是
 * "tools/list 暴露 ToolCallback → tools/call 传 JSON 字符串"这条链路，
 * 参数名绑定、JSON Schema 生成、返回序列化任何一环出错，工具都会在服务里
 * 显示存在、一调就崩。这里用真实 Spring AI ToolCallback 做一次端到端往返。
 */
class McpToolContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolCallbackProvider provider() {
        McpToolExposure.McpTools tools = new McpToolExposure.McpTools(MAPPER);
        return new McpToolExposure().codecanvasMcpToolProvider(tools);
    }

    private ToolCallback callback(String name) {
        return Arrays.stream(provider().getToolCallbacks())
                .filter(c -> c.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("MCP 工具未发布: " + name));
    }

    @Test
    @DisplayName("契约：只发布两个确定性工具，且返回类型必须是可展开的 ToolCallbackProvider")
    void exposesOnlyStatelessTools() {
        List<String> names = Arrays.stream(provider().getToolCallbacks())
                .map(c -> c.getToolDefinition().name())
                .toList();
        assertThat(names).containsExactlyInAnyOrder("validate_html_content", "describe_tool_contract");
        // 文件类工具一旦被暴露，就意味着会话工作区状态泄漏到进程级 Server 上
        assertThat(names).doesNotContain("read_file", "write_file", "list_files", "validate_html");
    }

    @Test
    @DisplayName("端到端：tools/call 传入 JSON 能走通参数绑定并返回自检修复同源的质检结论")
    void validateHtmlViaToolCallback() throws Exception {
        String good = "<html><body><h1>hello</h1></body></html>";
        String payload = MAPPER.writeValueAsString(java.util.Map.of("html", good));
        JsonNode out = MAPPER.readTree(callback("validate_html_content").call(payload));

        assertThat(out.get("ok").asBoolean()).isTrue();
        assertThat(out.get("issueCount").asInt()).isZero();
        // report() 的五项必须全部返回（通过也返回），外部 Agent 才能拿到稳定结构
        assertThat(out.get("checks").size()).isEqualTo(5);
    }

    @Test
    @DisplayName("端到端：坏 HTML 的结论与 CodeCanvas 内部自检一致")
    void validateHtmlDetectsFence() throws Exception {
        String bad = "```html\n<div>hi</div>\n```";
        String payload = MAPPER.writeValueAsString(java.util.Map.of("html", bad));
        JsonNode out = MAPPER.readTree(callback("validate_html_content").call(payload));

        assertThat(out.get("ok").asBoolean()).isFalse();
        assertThat(out.get("issues").asText()).contains("Markdown 代码围栏");
    }

    @Test
    @DisplayName("边界：空 HTML 不抛异常，返回结构化错误")
    void blankHtmlIsHandled() throws Exception {
        String payload = MAPPER.writeValueAsString(java.util.Map.of("html", "  "));
        JsonNode out = MAPPER.readTree(callback("validate_html_content").call(payload));
        assertThat(out.get("ok").asBoolean()).isFalse();
        assertThat(out.get("error").asText()).contains("为空");
    }

    @Test
    @DisplayName("契约发现：明确说明哪些工具留进程内、为什么")
    void describeContractExplainsBoundary() throws Exception {
        JsonNode out = MAPPER.readTree(callback("describe_tool_contract").call("{}"));
        assertThat(out.get("protocol").asText()).contains("MCP");
        assertThat(out.get("exposed").size()).isEqualTo(2);
        assertThat(out.get("inProcessOnly").toString()).contains("write_file");
        assertThat(out.get("reason").asText()).contains("AgentWorkspace");
    }

    @Test
    @DisplayName("命名隔离：外部工具名不能与内置工具撞键，也不能带脏字符")
    void remoteToolNamesAreIsolated() {
        assertThat(McpClientBridge.localName("read_file")).isEqualTo("mcp_read_file");
        assertThat(McpClientBridge.localName("search/web.v2")).isEqualTo("mcp_search_web_v2");
        assertThat(McpClientBridge.localName(null)).isEqualTo("mcp_unnamed");
    }
}
