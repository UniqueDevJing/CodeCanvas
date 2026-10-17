package com.codecanvas.agent.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.codecanvas.project.ProjectFile;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 工作区路径校验与工具层的边界行为。
 *
 * <p>{@link AgentWorkspace#checkPath} 是「路径进入项目 JSON」的唯一守门人 ——
 * 校验通过的 path 会原样固化进项目数据并回显到前端，所以它必须既拦得住
 * 目录穿越，也不能放过会让下游解析产生歧义的字符。
 *
 * <p>本类此前无任何测试覆盖（CodeCanvas 6 个测试类均未涉及工具层），
 * 而路径校验是 Agent 唯一的输入边界，属于必须钉住的行为。
 */
class AgentWorkspacePathTest {

    // ────────────────── 合法路径不能被误拒 ──────────────────

    @Test
    @DisplayName("常见项目路径应通过校验")
    void acceptsNormalProjectPaths() {
        String[] valid = {
                "index.html",
                "ok/index.html",
                "css/style.css",
                "js/app.js",
                "a/b/c/deep-file.json",
                "带中文的文件.md",
                "file-with-dash.js",
                "file_with_underscore.py",
        };
        for (String p : valid) {
            assertNull(AgentWorkspace.checkPath(p), "合法路径不应被拒: " + p);
        }
    }

    // ────────────────── 目录穿越与绝对路径 ──────────────────

    @Test
    @DisplayName("目录穿越必须被拒绝")
    void rejectsTraversal() {
        String[] attacks = {
                "../etc/passwd",
                "../../etc/passwd",
                "a/../../b",
                "a/b/../../../c",
                "./x",
                "x/./y",
                "x//y",
                "a/b/..",
        };
        for (String p : attacks) {
            assertNotNull(AgentWorkspace.checkPath(p), "穿越路径必须被拒: " + p);
        }
    }

    @Test
    @DisplayName("绝对路径必须被拒绝")
    void rejectsAbsolutePaths() {
        String[] attacks = {"/etc/passwd", "/", "C:/Windows/system32", "C:\\Windows\\x"};
        for (String p : attacks) {
            assertNotNull(AgentWorkspace.checkPath(p), "绝对路径必须被拒: " + p);
        }
    }

    @Test
    @DisplayName("反斜杠必须被拒绝（统一用 / 作分隔）")
    void rejectsBackslash() {
        assertNotNull(AgentWorkspace.checkPath("a\\b"));
        assertNotNull(AgentWorkspace.checkPath("dir\\file.html"));
    }

    // ────────────────── 本次修复：控制字符 ──────────────────

    @Test
    @DisplayName("NUL 字节必须被拒绝")
    void rejectsNulByte() {
        // NUL 在 Paths.get(String) 里会截断字符串，导致「校验用的 key」
        // 与「下游真正使用的 key」不是同一个；且会随项目 JSON 传到前端。
        // 显式拼接而非源码内嵌裸控制字符。
        String withNul = "evil" + ((char) 0x00) + ".html";
        assertNotNull(AgentWorkspace.checkPath(withNul), "NUL 字节应被拒绝");
        assertNull(AgentWorkspace.checkPath("evil.html"), "不含 NUL 的同名路径应正常通过");
    }

    @Test
    @DisplayName("换行/回车/制表等控制字符同样被拒绝")
    void rejectsOtherControlChars() {
        int[] codes = {0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x1B};
        for (int code : codes) {
            String p = "a" + ((char) code) + "b";
            assertNotNull(AgentWorkspace.checkPath(p),
                    "控制字符应被拒绝: U+" + Integer.toHexString(code).toUpperCase());
        }
    }

    @Test
    @DisplayName("DEL(0x7F) 也算控制字符")
    void rejectsDel() {
        assertNotNull(AgentWorkspace.checkPath("a" + ((char) 0x7F) + "b"));
    }

    @Test
    @DisplayName("0x20(空格) 不是控制字符，不应误拒")
    void spaceIsAllowed() {
        assertNull(AgentWorkspace.checkPath("my file.html"));
    }

    // ────────────────── 长度与空值 ──────────────────

    @Test
    @DisplayName("空值与超长路径被拒绝")
    void rejectsEmptyAndTooLong() {
        assertNotNull(AgentWorkspace.checkPath(null));
        assertNotNull(AgentWorkspace.checkPath(""));
        assertNotNull(AgentWorkspace.checkPath("   "));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            sb.append('a');
        }
        assertNotNull(AgentWorkspace.checkPath(sb.toString()));
    }

    // ────────────────── write 入口的二次防线 ──────────────

    @Test
    @DisplayName("write 必须拒绝非法路径而不是静默写入")
    void writeRejectsIllegalPath() {
        AgentWorkspace ws = new AgentWorkspace();
        boolean threw = false;
        try {
            ws.write("../escape.txt", "x");
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue(threw, "write 对非法路径应抛 IllegalArgumentException");
        assertTrue(ws.isEmpty(), "非法路径不应落进工作区");
    }

    @Test
    @DisplayName("write 记录 changed 供后续快照使用")
    void writeTracksChangedPaths() {
        AgentWorkspace ws = new AgentWorkspace();
        ws.write("index.html", "<html></html>");

        List<String> changed = ws.changedPaths();
        assertEquals(List.of("index.html"), changed);
        assertEquals("<html></html>", ws.read("index.html"));
    }

    @Test
    @DisplayName("seed 进来的既有文件不计入 changed")
    void seedDoesNotMarkChanged() {
        AgentWorkspace ws = new AgentWorkspace();
        ws.seed(List.of(new ProjectFile("existing.html", "old")));

        assertEquals(List.of("existing.html"), ws.paths());
        assertTrue(ws.changedPaths().isEmpty(), "seed 不应算作本次改动");
        assertEquals("old", ws.read("existing.html"));
    }

    // ────────────────── 工具层：写入护栏 ──────────────────

    @Test
    @DisplayName("write_file 工具拒绝穿越路径并给出可读原因")
    void writeFileToolRejectsTraversal() {
        AgentWorkspace ws = new AgentWorkspace();
        ToolRegistry registry = new ToolRegistry(ws);

        String result = registry.call("write_file",
                Map.of("path", "../evil.html", "content", "<html>"));

        assertTrue(result.contains("拒绝"), "应明确告知拒绝: " + result);
        assertTrue(ws.isEmpty(), "不应写入任何文件");
    }

    @Test
    @DisplayName("write_file 工具拒绝空内容")
    void writeFileToolRejectsBlankContent() {
        AgentWorkspace ws = new AgentWorkspace();
        ToolRegistry registry = new ToolRegistry(ws);

        String result = registry.call("write_file", Map.of("path", "a.html", "content", "  "));

        assertTrue(result.contains("拒绝"), result);
        assertTrue(ws.isEmpty());
    }

    @Test
    @DisplayName("write_file 工具拦截超长内容（生成失控护栏）")
    void writeFileToolRejectsOversizedContent() {
        AgentWorkspace ws = new AgentWorkspace();
        ToolRegistry registry = new ToolRegistry(ws);

        String huge = "x".repeat(ToolRegistry.WriteFileTool.MAX_CONTENT_CHARS + 1);
        String result = registry.call("write_file", Map.of("path", "a.html", "content", huge));

        assertTrue(result.contains("超过单文件上限"), result);
        assertTrue(ws.isEmpty());
    }

    @Test
    @DisplayName("write_file 对缺失参数回落默认路径而非写入 \"null\"")
    void writeFileToolHandlesMissingArgs() {
        AgentWorkspace ws = new AgentWorkspace();
        ws.write("index.html", "<html></html>");
        ToolRegistry registry = new ToolRegistry(ws);

        // 不传 path：应回落到工作区主路径，且不能出现字符串 "null"
        String result = registry.call("write_file", Map.of("content", "<html>updated</html>"));

        assertTrue(ws.read("index.html").contains("updated"),
                "缺省 path 应写入主文件，实际: " + ws.read("index.html"));
        assertTrue(!result.contains("null"), "结果里不应出现字面量 null: " + result);
    }

    @Test
    @DisplayName("调用未注册工具抛明确异常")
    void unknownToolThrows() {
        AgentWorkspace ws = new AgentWorkspace();
        ToolRegistry registry = new ToolRegistry(ws);

        boolean threw = false;
        try {
            registry.call("rm_rf", Map.of());
        } catch (IllegalArgumentException e) {
            threw = true;
            assertTrue(e.getMessage().contains("rm_rf"));
        }
        assertTrue(threw, "未注册工具应抛 IllegalArgumentException");
    }

    @Test
    @DisplayName("工具调用轨迹可用于事后复盘")
    void traceRecordsCalls() {
        AgentWorkspace ws = new AgentWorkspace();
        ToolRegistry registry = new ToolRegistry(ws);

        registry.call("write_file", Map.of("path", "index.html", "content", "<html></html>"));
        List<String> trace = registry.trace();

        assertEquals(1, trace.size());
        assertTrue(trace.get(0).startsWith("write_file("), trace.get(0));
        assertTrue(trace.get(0).contains("index.html"), trace.get(0));
    }

    @Test
    @DisplayName("describeTools 列出全部已注册工具")
    void describeToolsListsAll() {
        AgentWorkspace ws = new AgentWorkspace();
        ToolRegistry registry = new ToolRegistry(ws);

        String desc = registry.describeTools();
        for (String name : List.of("list_files", "read_file", "write_file", "validate_html")) {
            assertTrue(desc.contains(name), "应包含工具 " + name + "，实际: " + desc);
        }
    }
}
