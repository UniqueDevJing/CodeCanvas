package com.codecanvas.project.model;

import com.codecanvas.agent.tool.AgentWorkspace;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C3 文件变更集解析与应用测试。 */
class FileChangeSetTest {

    @Test
    void parseFromFencedJson() {
        String text = """
                说明文字
                ```json
                {"summary":"改标题","changes":[
                  {"path":"index.html","action":"UPDATE","content":"<html>new</html>"},
                  {"path":"styles.css","action":"CREATE","content":"body{}"},
                  {"path":"old.js","action":"DELETE"}
                ]}
                ```
                尾部文字
                """;
        FileChangeSet set = FileChangeSet.parse(text);
        assertEquals(3, set.changes().size());
        assertEquals("改标题", set.summary());
        assertEquals(FileAction.UPDATE, set.changes().get(0).action());
        assertEquals(FileAction.CREATE, set.changes().get(1).action());
        assertEquals(FileAction.DELETE, set.changes().get(2).action());
        assertNull(set.changes().get(2).content());
    }

    @Test
    void parseFromBareJson() {
        String json = "{\"summary\":\"s\",\"changes\":[{\"path\":\"a.js\",\"content\":\"x\"}]}";
        FileChangeSet set = FileChangeSet.parse(json);
        assertEquals(1, set.changes().size());
        assertEquals(FileAction.UPDATE, set.changes().get(0).action());
    }

    @Test
    void parseInvalidReturnsNull() {
        assertNull(FileChangeSet.parse(null));
        assertNull(FileChangeSet.parse("没有任何 JSON"));
        assertNull(FileChangeSet.parse("{\"summary\":\"空集\",\"changes\":[]}"));
        assertNull(FileChangeSet.parse("{非法"));
    }

    @Test
    void applyCreateUpdateDelete() {
        AgentWorkspace workspace = new AgentWorkspace();
        workspace.write("index.html", "<html>old</html>");
        FileChangeSet set = new FileChangeSet("s", List.of(
                new FileChange("styles.css", "body{}", FileAction.CREATE),
                new FileChange("index.html", "<html>new</html>", FileAction.UPDATE),
                new FileChange("legacy.js", null, FileAction.DELETE)));
        workspace.write("legacy.js", "old");

        FileChangeSet.ApplyResult result = set.applyTo(workspace);
        assertTrue(result.ok());
        assertEquals(1, result.created());
        assertEquals(1, result.updated());
        assertEquals(1, result.deleted());
        assertEquals("<html>new</html>", workspace.read("index.html"));
        assertEquals("body{}", workspace.read("styles.css"));
        assertNull(workspace.read("legacy.js"));
    }

    @Test
    void applyRejectsIllegalPath() {
        AgentWorkspace workspace = new AgentWorkspace();
        FileChangeSet set = new FileChangeSet("s", List.of(
                new FileChange("../etc/passwd", "evil", FileAction.CREATE)));
        FileChangeSet.ApplyResult result = set.applyTo(workspace);
        assertTrue(!result.ok());
        assertNull(workspace.read("../etc/passwd"));
    }

    @Test
    void parseFiltersIllegalPaths() {
        String json = "{\"summary\":\"s\",\"changes\":["
                + "{\"path\":\"../../x\",\"content\":\"bad\"},"
                + "{\"path\":\"ok.js\",\"content\":\"good\"}]}";
        FileChangeSet set = FileChangeSet.parse(json);
        assertEquals(1, set.changes().size());
        assertEquals("ok.js", set.changes().get(0).path());
    }

    @Test
    void deleteMissingFileIsIdempotent() {
        AgentWorkspace workspace = new AgentWorkspace();
        FileChangeSet set = new FileChangeSet("s", List.of(
                new FileChange("not-exist.js", null, FileAction.DELETE)));
        assertTrue(set.applyTo(workspace).ok());
    }
}
