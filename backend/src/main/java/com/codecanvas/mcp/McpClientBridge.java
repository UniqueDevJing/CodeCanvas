package com.codecanvas.mcp;

import com.codecanvas.agent.tool.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MCP 客户端桥（P0-2 的另一半）：把<b>外部</b> MCP Server 的工具适配成 CodeCanvas 的
 * {@link Tool} 接口，注册进本次运行的 {@code ToolRegistry}，让 ReAct 自主循环可以按需调用。
 *
 * <p>为什么要做双向：只把工具"发布出去"是单向演示；真正的工具层协议化要能同时
 * <b>生产</b>（McpToolExposure）与<b>消费</b>（本类）标准工具。这样
 * {@code langchain4j-mcp} 依赖不是简历上的摆设，而是有实际调用链的能力。
 *
 * <p>三条工程约束：
 * <ol>
 *   <li><b>默认关闭</b>：没有外部 MCP Server 时不建立连接，应用启动零额外依赖；
 *       由 {@code codecanvas.mcp.client.enabled=true} + URL 显式开启。</li>
 *   <li><b>失败降级</b>：握手/发现问题一律吞掉并返回空工具集，绝不因外部服务不通
 *       而让本地生成流程挂掉——这与模型降级、运行时验证降级是同一套思路。</li>
 *   <li><b>不阻断</b>：单个远程工具调用失败时返回可读的错误文本而非抛异常，
 *       让 ReAct 循环自己决定重试或换工具；SSE 的 tool 事件契约保持不变。</li>
 * </ol>
 *
 * <p>命名隔离：外部工具统一加 {@code mcp_} 前缀，避免与内置工具同名被
 * {@code ToolRegistry.register} 静默覆盖。
 */
@Component
@ConditionalOnProperty(prefix = "codecanvas.mcp.client", name = "enabled", havingValue = "true")
public class McpClientBridge implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpClientBridge.class);
    private static final String SERVER_KEY = "codecanvas-external";

    private final String url;
    private final long timeoutMs;
    private final ObjectMapper mapper;

    private volatile List<Tool> cached;
    private volatile McpClient client;

    public McpClientBridge(
            @Value("${codecanvas.mcp.client.url:http://127.0.0.1:8080/mcp}") String url,
            @Value("${codecanvas.mcp.client.timeout-ms:10000}") long timeoutMs,
            ObjectMapper mapper) {
        this.url = url;
        this.timeoutMs = timeoutMs;
        this.mapper = mapper;
    }

    /** 本次运行可注册进 ToolRegistry 的外部工具；连接失败返回空集。 */
    public List<Tool> externalTools() {
        List<Tool> local = cached;
        if (local != null) {
            return local;
        }
        synchronized (this) {
            if (cached != null) {
                return cached;
            }
            cached = connect();
            return cached;
        }
    }

    private List<Tool> connect() {
        try {
            Duration timeout = Duration.ofMillis(timeoutMs);
            StreamableHttpMcpTransport transport = StreamableHttpMcpTransport.builder()
                    .url(url)
                    .timeout(timeout)
                    .logRequests(false)
                    .logResponses(false)
                    .build();
            McpClient connected = DefaultMcpClient.builder()
                    .transport(transport)
                    .key(SERVER_KEY)
                    .clientName("codecanvas")
                    .toolExecutionTimeout(timeout)
                    .cacheToolList(true)
                    .build();
            List<ToolSpecification> specs = connected.listTools();
            this.client = connected;

            List<Tool> adapted = new ArrayList<>();
            for (ToolSpecification spec : specs) {
                adapted.add(new RemoteMcpTool(localName(spec.name()), spec.name(),
                        spec.description() == null ? "外部 MCP 工具" : spec.description(),
                        connected, mapper));
            }
            log.info("MCP 客户端已接入 {} 的 {} 个工具", url, adapted.size());
            return List.copyOf(adapted);
        } catch (Exception e) {
            log.warn("MCP 客户端连接失败，本次运行降级为无外部工具（不影响生成）: {}", e.getMessage());
            return List.of();
        }
    }

    /** 包名隔离 + 字符清洗：ToolRegistry 以 name 为键，脏名字会污染工具清单。 */
    static String localName(String remoteName) {
        String cleaned = remoteName == null ? "unnamed" : remoteName.replaceAll("[^a-zA-Z0-9_]", "_");
        return "mcp_" + cleaned;
    }

    @Override
    public void close() {
        McpClient c = client;
        if (c != null) {
            try {
                c.close();
            } catch (Exception e) {
                log.warn("关闭 MCP 客户端失败: {}", e.getMessage());
            }
        }
    }

    /** 一个远程 MCP 工具 = 一个 CodeCanvas Tool；execute 走标准 tools/call。 */
    private static final class RemoteMcpTool implements Tool {

        private final String localName;
        private final String remoteName;
        private final String description;
        private final McpClient client;
        private final ObjectMapper mapper;

        private RemoteMcpTool(String localName, String remoteName, String description,
                             McpClient client, ObjectMapper mapper) {
            this.localName = localName;
            this.remoteName = remoteName;
            this.description = description;
            this.client = client;
            this.mapper = mapper;
        }

        @Override
        public String name() {
            return localName;
        }

        @Override
        public String description() {
            return "[MCP 外部工具] " + description;
        }

        @Override
        public String execute(Map<String, Object> args) {
            String json;
            try {
                Map<String, Object> safeArgs = args == null ? Map.of() : args;
                json = mapper.writeValueAsString(safeArgs);
            } catch (Exception e) {
                return "MCP 工具参数序列化失败：" + e.getMessage();
            }
            try {
                ToolExecutionRequest request = ToolExecutionRequest.builder()
                        .id(UUID.randomUUID().toString())
                        .name(remoteName)
                        .arguments(json)
                        .build();
                ToolExecutionResult result = client.executeTool(request);
                String text = result.resultText();
                if (result.isError()) {
                    return "MCP 工具返回错误：" + (text == null ? "(无详情)" : text);
                }
                return text == null ? "(工具无返回内容)" : text;
            } catch (Exception e) {
                // 不抛：让 ReAct 循环拿到可读反馈后自行决定重试/换路，生成流程不中断
                return "MCP 工具调用失败（不阻断生成）：" + e.getMessage();
            }
        }
    }
}
