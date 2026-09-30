package com.codecanvas.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 数据层落盘行为。
 *
 * <p>重点钉住「原子写」：写盘必须经临时文件 + rename，任何时刻磁盘上的项目文件
 * 都应是完整 JSON（进程中途被杀不会留下半截文件），且不能有 .tmp 残留。
 */
class FileSystemProjectRepositoryTest {

    @TempDir
    Path tempDir;

    private FileSystemProjectRepository newRepo() {
        // 与生产对齐: Spring Boot 的 ObjectMapper 自带 JavaTimeModule(Project 里有 Instant 字段),
        // 裸 new 的不带, 会在序列化第一步就抛异常被 save 静默吞掉, 测不到写盘行为
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return new FileSystemProjectRepository(mapper, tempDir.toString());
    }

    private Project sampleProject(String id) {
        return Project.create(id, "演示项目", "做一个演示页面");
    }

    @Test
    @DisplayName("save 后磁盘上是完整 JSON，且没有 .tmp 残留")
    void saveLeavesNoTempFilesAndValidJson() throws IOException {
        FileSystemProjectRepository repo = newRepo();
        repo.save(sampleProject("p_1"));

        Path file = tempDir.resolve("projects/p_1.json");
        assertTrue(Files.exists(file), "项目文件应存在");
        assertTrue(Files.readString(file).contains("\"p_1\""), "内容应是可解析的项目 JSON");

        try (var files = Files.list(tempDir.resolve("projects"))) {
            long tmp = files.filter(p -> p.getFileName().toString().contains(".tmp")).count();
            assertEquals(0, tmp, "不允许残留临时文件: " + tmp);
        }
    }

    @Test
    @DisplayName("重复 save 覆盖旧内容且仍无残留（rename 路径可重复走）")
    void repeatedSaveIsIdempotentOnDisk() throws IOException {
        FileSystemProjectRepository repo = newRepo();
        repo.save(sampleProject("p_2"));
        repo.save(sampleProject("p_2"));
        repo.save(sampleProject("p_2"));

        Path file = tempDir.resolve("projects/p_2.json");
        assertNotNull(Files.readString(file));
        try (var files = Files.list(tempDir.resolve("projects"))) {
            assertEquals(1, files.count(), "同项目只应有一个文件、无临时残留");
        }
    }

    @Test
    @DisplayName("重新实例化（模拟重启）能从磁盘完整恢复项目")
    void reloadAfterRestartRecoversProject() throws IOException {
        newRepo().save(sampleProject("p_3"));
        Path file = tempDir.resolve("projects/p_3.json");
        assertTrue(Files.exists(file), "save 后文件应存在");

        // 新实例重新走 loadAll（生产由 @PostConstruct 触发），等价于进程重启后的恢复路径。
        // 这里顺带用严格 mapper 复现反序列化：若有人再往 JSON 里塞进非字段属性
        // （比如之前的 isEmpty() → "empty"），这个测试会当场红掉，而不是上线后才静默丢项目。
        ObjectMapper strict = new ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        assertEquals("p_3", strict.readValue(Files.readString(file), Project.class).id());
        FileSystemProjectRepository repo = newRepo();
        repo.loadAll();
        Project restored = repo.findById("p_3").orElse(null);
        assertNotNull(restored, "重启后应能恢复项目");
        assertEquals("演示项目", restored.name());
    }

    @Test
    @DisplayName("工作区路径校验：拒绝空/绝对/目录穿越/反斜杠，放行正常相对路径")
    void workspacePathValidation() {
        assertNull(AgentWorkspaceBridge.check("index.html"));
        assertNull(AgentWorkspaceBridge.check("assets/js/app.js"));
        assertNotNull(AgentWorkspaceBridge.check(""));
        assertNotNull(AgentWorkspaceBridge.check(null));
        assertNotNull(AgentWorkspaceBridge.check("/etc/passwd"));
        assertNotNull(AgentWorkspaceBridge.check("C:/windows/system32"));
        assertNotNull(AgentWorkspaceBridge.check("../other/project.html"));
        assertNotNull(AgentWorkspaceBridge.check("a/../../b.html"));
        assertNotNull(AgentWorkspaceBridge.check("assets\\app.js"));
        assertNotNull(AgentWorkspaceBridge.check("a//b.html"));
    }

    /** 只为让静态方法可测的薄桥接（AgentWorkspace 在 tool 包内）。 */
    private static final class AgentWorkspaceBridge {
        static String check(String path) {
            return com.codecanvas.agent.tool.AgentWorkspace.checkPath(path);
        }
    }

    @Test
    @DisplayName("write_file 工具：拒绝路径穿越与超长内容，null 参数不产生 \"null\" 字符串")
    void writeFileDialogueValidation() {
        com.codecanvas.agent.tool.AgentWorkspace ws = new com.codecanvas.agent.tool.AgentWorkspace();
        com.codecanvas.agent.tool.ToolRegistry registry = new com.codecanvas.agent.tool.ToolRegistry(ws);

        // 目录穿越被拒绝，且不会混进工作区
        String refused = registry.call("write_file", Map.of("path", "../evil.html", "content", "<p>x</p>"));
        assertTrue(refused.contains("写入被拒绝"), refused);
        assertFalse(ws.paths().contains("../evil.html"), "被拒路径不得进入工作区");

        // 绝对路径被拒绝
        assertTrue(registry.call("write_file", Map.of("path", "/tmp/x.html", "content", "x")).contains("写入被拒绝"));

        // path 显式传 null：应回落主文件而不是变成字符串 "null"（Map.of 不收 null，用 HashMap）
        Map<String, Object> nullPath = new java.util.HashMap<>();
        nullPath.put("path", null);
        nullPath.put("content", "<p>hi</p>");
        String ok = registry.call("write_file", nullPath);
        assertTrue(ok.contains("已写入 index.html"), ok);

        // 超长内容被拒绝
        String big = "x".repeat(400_001);
        assertTrue(registry.call("write_file", Map.of("content", big)).contains("写入被拒绝"));
    }
}
