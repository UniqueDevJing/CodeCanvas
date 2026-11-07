package com.codecanvas.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发写入的版本丢失回归测试（真实 bug）。
 *
 * <p>背景：实测在同一项目上并发发起两次生成，两次都返回 success，但磁盘上只多了 1 个版本。
 * 根因是"读项目 → 追加版本 → 保存"的读改写：两个线程都基于旧快照写回，后者覆盖前者。
 *
 * <p>断言只关心一件事：**谁生成过的版本必须还在**。
 */
class FileSystemProjectRepositoryConcurrencyTest {

    private static VersionSnapshot version(String id) {
        return new VersionSnapshot(
                id,
                id,
                "summary of " + id,
                "需求 " + id,
                Instant.now(),
                List.of(new ProjectFile("index.html", "<html><body>" + id + "</body></html>")),
                0,
                0.0,
                0L,
                0L,
                "qwen3-coder-flash",
                "标准");
    }

    private static Project withVersions(Project base, List<VersionSnapshot> versions) {
        return new Project(base.id(), base.name(), base.prompt(), base.prd(),
                base.createdAt(), Instant.now(), base.files(), versions,
                versions.isEmpty() ? null : versions.get(versions.size() - 1).id(),
                base.deployKey(), base.deployedAt());
    }

    private static ObjectMapper mapper() {
        // 生产环境注入的是 Spring 的 ObjectMapper（已注册 JavaTimeModule）；
        // 裸 new ObjectMapper() 无法序列化 Instant，会让 save() 里的异常被吞掉、
        // 结果"看起来保存成功但磁盘没文件"——这个坑我在测试里踩到过一次。
        return new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    }

    @Test
    @DisplayName("并发写版本不丢失：8 个线程各追加一版，最终 8 版全部在")
    void concurrentSavesDoNotLoseVersions(@TempDir Path dir) throws Exception {
        FileSystemProjectRepository repo = new FileSystemProjectRepository(mapper(), dir.toString());
        Project base = Project.create("p_race", "竞态项目", "需求");
        repo.save(base);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> errors = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 模拟真实调用方：读快照 → 追加一版 → 保存（各自持不同快照）
                    Project snapshot = repo.findById("p_race").orElseThrow();
                    List<VersionSnapshot> versions = new ArrayList<>(snapshot.versions());
                    versions.add(version("v" + index));
                    repo.save(withVersions(snapshot, versions));
                } catch (Throwable t) {
                    synchronized (errors) {
                        errors.add(t);
                    }
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).as("线程应在超时内完成").isTrue();
        pool.shutdownNow();

        assertThat(errors).as("不应有异常").isEmpty();

        Project saved = repo.findById("p_race").orElseThrow();
        List<String> ids = saved.versions().stream().map(VersionSnapshot::id).toList();
        assertThat(ids).as("每个并发生成的版本都必须保留").hasSize(threads);
        assertThat(ids).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("顺序保存仍幂等：同一版本重复保存不产生重复条目")
    void repeatedSaveIsIdempotent(@TempDir Path dir) {
        FileSystemProjectRepository repo = new FileSystemProjectRepository(mapper(), dir.toString());
        Project base = Project.create("p_idem", "幂等项目", "需求");
        repo.save(base);

        List<VersionSnapshot> versions = new ArrayList<>(List.of(version("v1")));
        repo.save(withVersions(base, versions));
        repo.save(withVersions(base, versions));

        assertThat(repo.findById("p_idem").orElseThrow().versions()).hasSize(1);
    }

    @Test
    @DisplayName("磁盘文件始终是完整 JSON（原子写 + 合并后仍可解析）")
    void persistedFileIsAlwaysParsable(@TempDir Path dir) throws Exception {
        FileSystemProjectRepository repo = new FileSystemProjectRepository(mapper(), dir.toString());
        Project base = Project.create("p_atomic", "原子写", "需求");
        repo.save(base);
        repo.save(withVersions(base, new ArrayList<>(List.of(version("v1"), version("v2")))));

        // 落盘位置是 {dir}/projects/{id}.json（构造函数内部拼的），这里直接按该布局校验。
        java.nio.file.Path file = dir.resolve("projects").resolve("p_atomic.json");
        assertThat(java.nio.file.Files.exists(file))
                .as("落盘文件应存在：%s（目录内容：%s）", file, listFiles(dir))
                .isTrue();

        Project reparsed = mapper().readValue(
                java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8), Project.class);
        assertThat(reparsed.versions()).hasSize(2);
    }

    private static java.util.List<String> listFiles(java.nio.file.Path dir) {
        try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(dir)) {
            return walk.filter(java.nio.file.Files::isRegularFile)
                    .map(p -> dir.relativize(p).toString())
                    .toList();
        } catch (java.io.IOException e) {
            return List.of("walk failed: " + e.getMessage());
        }
    }
}