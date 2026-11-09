package com.codecanvas.mcp;

import com.codecanvas.common.HtmlValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MCP 工具暴露层（P0-2）：把 CodeCanvas 的<b>确定性、无状态</b>能力按
 * Model Context Protocol 标准发布给外部 Agent。
 *
 * <p>设计取舍（面试要能讲清）：MCP 工具是进程级、无会话状态的，因此只暴露不依赖
 * {@code AgentWorkspace} 的纯函数工具（质检、工具契约发现）。read_file / write_file /
 * list_files 绑定单次会话的工作区（路径穿越防护、内容长度上限、版本快照），
 * 仍走进程内 {@code ToolRegistry.call(...)} 直调，避免把会话状态泄漏到共享的 MCP Server 上
 * —— 这是"工具层协议化"，不是"把一切硬塞进 MCP"。
 *
 * <p><b>为什么返回 {@link ToolCallbackProvider} 而不是 {@code ToolCallback[]}</b>：
 * Spring AI 1.1.8 的 {@code ToolCallbackConverterAutoConfiguration} 聚合的是容器里的
 * {@code List<ToolCallback>} 与 {@code ToolCallbackProvider} Bean。数组类型的 Bean
 * 不会被展开注册，工具会"定义成功但 tools/list 里一个都没有"——这个坑只有对着
 * 自动配置类的方法签名核过才能避开。
 *
 * <p>Server 侧通过 {@code /mcp}（Streamable HTTP）发布 tools/list 与 tools/call，
 * 无需手写 JSON-RPC；开关 {@code spring.ai.mcp.server.enabled=false} 可整层关闭。
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class McpToolExposure {

    @Bean
    public McpTools codecanvasMcpTools(ObjectMapper mapper) {
        return new McpTools(mapper);
    }

    /** 把带 @Tool 的方法转成 ToolCallbackProvider，供 MCP Server 自动注册 */
    @Bean
    public ToolCallbackProvider codecanvasMcpToolProvider(McpTools tools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();
    }

    public static class McpTools {

        private final ObjectMapper mapper;

        public McpTools(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        /**
         * 确定性 HTML 质检：与自检修复闭环用的是同一套规则（不依赖模型），
         * 因此外部 Agent 通过 MCP 调到的结论与 CodeCanvas 内部自检完全一致。
         */
        @Tool(name = "validate_html_content", description =
                "对一段 HTML 做确定性质检：空输出/Markdown 围栏/占位内容、HTML 骨架、"
                        + "标签闭合、script 括号配平、img 的 alt 与 src 可用性；"
                        + "返回逐项检查结论与问题描述。不依赖大模型，可重复调用。")
        public String validateHtmlContent(
                @ToolParam(description = "待检查的完整 HTML 文本") String html) {
            Map<String, Object> out = new LinkedHashMap<>();
            if (html == null || html.isBlank()) {
                out.put("ok", false);
                out.put("error", "HTML 内容为空");
                return write(out);
            }
            List<HtmlValidator.Issue> issues = HtmlValidator.validate(html);
            List<HtmlValidator.CheckResult> report = HtmlValidator.report(html);
            out.put("ok", issues.isEmpty());
            out.put("issueCount", issues.size());
            out.put("issues", HtmlValidator.describe(issues));
            // 不用 Map.of：CheckResult.detail 的 null 语义由这里收口，避免 NPE 打断一次 tools/call
            out.put("checks", report.stream().map(r -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", r.id());
                row.put("label", r.label());
                row.put("passed", r.passed());
                row.put("detail", r.detail() == null ? "" : r.detail());
                return row;
            }).toList());
            return write(out);
        }

        /**
         * 工具契约发现：让外部 Agent 知道这个 MCP Server 提供哪些能力，
         * 也说明为什么文件类工具没有被暴露（会话工作区状态约束）。
         */
        @Tool(name = "describe_tool_contract", description =
                "返回 CodeCanvas 工具层的契约说明：已通过 MCP 暴露的工具、保留在进程内的工具及其原因。")
        public String describeToolContract() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("protocol", "MCP (Model Context Protocol) / tools-list + tools-call");
            out.put("exposed", List.of("validate_html_content", "describe_tool_contract"));
            out.put("inProcessOnly", List.of("list_files", "read_file", "write_file", "validate_html", "generateImage"));
            out.put("reason", "文件类工具绑定单次会话的 AgentWorkspace（路径穿越防护、内容长度上限、版本快照），"
                    + "属于有状态会话能力，不放入无状态的进程级 MCP Server；"
                    + "两者共用同一套确定性校验规则与 SSE tool 事件契约。");
            return write(out);
        }

        private String write(Object value) {
            try {
                return mapper.writeValueAsString(value);
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":\"结果序列化失败\"}";
            }
        }
    }
}
