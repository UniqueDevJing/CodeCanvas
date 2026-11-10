package com.codecanvas.project.model;

import com.codecanvas.project.ProjectFile;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** C3 项目清单测试。 */
class ProjectManifestTest {

    @Test
    void fromAndToJsonRoundTrip() {
        List<ProjectFile> files = List.of(
                new ProjectFile("index.html", "<html>x</html>"),
                new ProjectFile("app.js", "console.log(1);"));
        ProjectManifest manifest = ProjectManifest.from("index.html", "html", files);
        assertEquals("index.html", manifest.entry());
        assertEquals("html", manifest.tech());
        assertEquals(2, manifest.fileCount());
        assertEquals(List.of("index.html", "app.js"), manifest.paths());
        // "<html>x</html>" 14B + "console.log(1);" 15B
        assertEquals(29, manifest.totalBytes());

        String json = manifest.toJson();
        ProjectManifest restored = ProjectManifest.fromJson(json);
        assertNotNull(restored);
        assertEquals(manifest.entry(), restored.entry());
        assertEquals(manifest.fileCount(), restored.fileCount());
        assertEquals(manifest.paths(), restored.paths());
        assertEquals(manifest.totalBytes(), restored.totalBytes());
    }

    @Test
    void invalidJsonReturnsNull() {
        assertNull(ProjectManifest.fromJson("not json"));
        assertNull(ProjectManifest.fromJson(null));
    }

    @Test
    void manifestPathIsFixed() {
        assertEquals("codecanvas.manifest.json", ProjectManifest.FILENAME);
    }

    @Test
    void bytesAreUtf8() {
        List<ProjectFile> files = List.of(new ProjectFile("a.txt", "中")); // 3 字节
        assertEquals(3, ProjectManifest.from("a.txt", "html", files).totalBytes());
        assertTrue(true);
    }
}
