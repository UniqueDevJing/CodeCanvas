package com.codecanvas.agent.tool;

import com.codecanvas.common.HtmlValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表。
 *
 * <p>当前编排是有向流程（每个阶段调用固定工具），所以这里不做"模型自主选工具"的决策，
 * 只负责把工具统一注册、统一执行、统一留痕。把决策权交给模型的成本很高（要处理幻觉调用、
 * 参数校验、循环终止），在编排稳定之前先用流程约束住，这是刻意的取舍。
 */
public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final List<String> trace = new ArrayList<>();

    public ToolRegistry(AgentWorkspace workspace) {
        register(new ListFilesTool(workspace));
        register(new ReadFileTool(workspace));
        register(new WriteFileTool(workspace));
        register(new ValidateHtmlTool(workspace));
    }

    public void register(Tool tool) {
        tools.put(tool.name(), tool);
    }

    /** 执行工具并记录调用轨迹。 */
    public String call(String name, Map<String, Object> args) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未注册的工具: " + name);
        }
        // 参数可能来自模型输出, 缺失/为 null 都属正常输入而非编程错误, 统一在这里兜底,
        // 避免 String.valueOf(null) 产生 "null" 字符串混进 path/content
        String result = tool.execute(args == null ? Map.of() : args);
        trace.add(name + "(" + summarize(args) + ") -> " + brief(result));
        return result;
    }

    /** 取字符串参数：显式 null 视为缺失并回落默认值（区别于 String.valueOf 的 "null" 陷阱）。 */
    static String stringArg(Map<String, Object> args, String key, String defaultValue) {
        Object v = args.get(key);
        return v == null ? defaultValue : String.valueOf(v);
    }

    public List<String> trace() {
        return new ArrayList<>(trace);
    }

    public String describeTools() {
        StringBuilder sb = new StringBuilder();
        tools.values().forEach(tool -> sb.append(tool.name()).append("：").append(tool.description()).append('\n'));
        return sb.toString().trim();
    }

    private String summarize(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        args.forEach((key, value) -> {
            String text = String.valueOf(value);
            sb.append(key).append("=").append(text.length() > 24 ? text.substring(0, 24) + "…" : text).append(' ');
        });
        return sb.toString().trim();
    }

    private String brief(String result) {
        if (result == null) {
            return "null";
        }
        String single = result.replace('\n', ' ');
        return single.length() > 80 ? single.substring(0, 80) + "…" : single;
    }

    // ==================== 具体工具 ====================

    /** 列出工作区里的文件。 */
    public static class ListFilesTool implements Tool {

        private final AgentWorkspace workspace;

        public ListFilesTool(AgentWorkspace workspace) {
            this.workspace = workspace;
        }

        @Override
        public String name() {
            return "list_files";
        }

        @Override
        public String description() {
            return "列出当前项目里的所有文件";
        }

        @Override
        public String execute(Map<String, Object> args) {
            List<String> paths = workspace.paths();
            return paths.isEmpty() ? "（项目还没有文件）" : String.join("\n", paths);
        }
    }

    /** 读取文件内容。 */
    public static class ReadFileTool implements Tool {

        private final AgentWorkspace workspace;

        public ReadFileTool(AgentWorkspace workspace) {
            this.workspace = workspace;
        }

        @Override
        public String name() {
            return "read_file";
        }

        @Override
        public String description() {
            return "读取指定文件的内容";
        }

        @Override
        public String execute(Map<String, Object> args) {
            String path = stringArg(args, "path", workspace.mainPath());
            String content = workspace.read(path);
            if (content == null) {
                return "文件不存在: " + path;
            }
            return "已读取 " + path + "（" + content.length() + " 字符）";
        }
    }

    /** 写入文件。 */
    public static class WriteFileTool implements Tool {

        /** 单文件内容上限： runaway 生成会把项目 JSON 撑到几十 MB, 也说明生成已失控。 */
        static final int MAX_CONTENT_CHARS = 400_000;

        private final AgentWorkspace workspace;

        public WriteFileTool(AgentWorkspace workspace) {
            this.workspace = workspace;
        }

        @Override
        public String name() {
            return "write_file";
        }

        @Override
        public String description() {
            return "把生成或修改后的内容写入文件";
        }

        @Override
        public String execute(Map<String, Object> args) {
            String path = stringArg(args, "path", workspace.mainPath());
            String content = stringArg(args, "content", "");
            if (content.isBlank()) {
                return "写入被拒绝：内容为空";
            }
            // 路径校验：workspace 是内存工作区，path 会被原样固化进项目 JSON，
            // 必须在入口拦住目录穿越/绝对路径这类脏键
            String invalid = AgentWorkspace.checkPath(path);
            if (invalid != null) {
                return "写入被拒绝：" + invalid + "（" + path + "）";
            }
            if (content.length() > MAX_CONTENT_CHARS) {
                return "写入被拒绝：内容 " + content.length() + " 字符，超过单文件上限 "
                        + MAX_CONTENT_CHARS + "（疑似生成失控，请缩小改动范围）";
            }
            workspace.write(path, content);
            return "已写入 " + path + "（" + content.length() + " 字符）";
        }
    }

    /** 静态校验。 */
    public static class ValidateHtmlTool implements Tool {

        private final AgentWorkspace workspace;

        public ValidateHtmlTool(AgentWorkspace workspace) {
            this.workspace = workspace;
        }

        @Override
        public String name() {
            return "validate_html";
        }

        @Override
        public String description() {
            return "对生成的页面做结构与脚本静态检查";
        }

        @Override
        public String execute(Map<String, Object> args) {
            String path = stringArg(args, "path", workspace.mainPath());
            String content = workspace.read(path);
            if (content == null) {
                return "校验失败：文件不存在 " + path;
            }
            List<HtmlValidator.Issue> issues = HtmlValidator.validate(content);
            if (issues.isEmpty()) {
                return "校验通过：未发现结构或脚本问题";
            }
            return "发现 " + issues.size() + " 个问题：\n" + HtmlValidator.describe(issues);
        }
    }
}
