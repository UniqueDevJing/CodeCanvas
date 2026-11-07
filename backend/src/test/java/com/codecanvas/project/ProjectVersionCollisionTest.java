package com.codecanvas.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发同号版本的回归测试（真实 bug 第二步）。
 *
 * <p>实测发现：两个并发生成会算出**同一个版本号**（按"当前版本数+1"推导），
 * 只按 id 合并会把其中一次的产物整条吞掉——线上表现就是"我改了两处，只生效一处"。
 *
 * <p>这里锁死：同号但内容不同 → 两个版本都必须保留。
 */
class ProjectVersionCollisionTest {

    private static ObjectMapper mapper() {
        return new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    private static ProjectFile file(String html) {
        return new ProjectFile("index.html", html);
    }

    private static VersionSnapshot version(String id, String requirement, String html) {
        return new VersionSnapshot(id, id, "s", requirement, Instant.now(), List.of(file(html)),
                0, 0.0, 0L, 0L, "qwen3-coder-flash", "标准");
    }

    private static Project project(Project base, List<VersionSnapshot> versions) {
        return new Project(base.id(), base.name(), base.prompt(), base.prd(), base.createdAt(),
                Instant.now(), base.files(), versions,
                versions.isEmpty() ? null : versions.get(versions.size() - 1).id(),
                base.deployKey(), base.deployedAt());
    }

    @Test
    @DisplayName("同号不同内容：两次并发生成的产物都必须保留，不允许后者覆盖前者")
    void sameIdDifferentContentKeepsBoth(@TempDir Path dir) {
        FileSystemProjectRepository repo = new FileSystemProjectRepository(mapper(), dir.toString());
        Project base = Project.create("p_col", "同号", "需求");
        repo.save(base);

        // 两个线程都算出 v2，但内容不同（各自满足不同需求）
        Project a = repo.findById("p_col").orElseThrow();
        repo.save(project(a, List.of(version("v2", "改标题", "<html>title-changed</html>"))));

        Project b = repo.findById("p_col").orElseThrow();
        repo.save(project(b, List.of(version("v2", "改背景", "<html>bg-changed</html>"))));

        Project saved = repo.findById("p_col").orElseThrow();
        assertThat(saved.versions()).as("同号不同内容必须都保留").hasSize(2);

        List<String> htmls = saved.versions().stream()
                .map(v -> v.files().get(0).content()).toList();
        assertThat(htmls).contains("<html>title-changed</html>", "<html>bg-changed</html>");
    }

    @Test
    @DisplayName("同号同内容：视为重复保存，不膨胀版本数")
    void sameIdSameContentIsIdempotent(@TempDir Path dir) {
        FileSystemProjectRepository repo = new FileSystemProjectRepository(mapper(), dir.toString());
        Project base = Project.create("p_idem2", "幂等", "需求");
        repo.save(base);

        Project a = repo.findById("p_idem2").orElseThrow();
        VersionSnapshot v = version("v2", "同一个需求", "<html>same</html>");
        repo.save(project(a, List.of(v)));
        Project b = repo.findById("p_idem2").orElseThrow();
        repo.save(project(b, List.of(v)));

        assertThat(repo.findById("p_idem2").orElseThrow().versions()).hasSize(1);
    }
}