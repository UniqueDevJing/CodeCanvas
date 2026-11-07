package com.codecanvas.project;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 上线前三条风险里的两条的回归测试。
 *
 * <p>1) 同一项目并发生成必须被拒（项目语义，不是存储语义）；
 * <p>2) 保存失败必须**显式报错**——之前只打日志，调用方以为成功，用户看到"生成成功"但
 * 磁盘上没有版本，属于静默丢数据。
 */
class ProjectRunSafetyTest {

    // ───────── 并发互斥 ─────────

    @Test
    @DisplayName("同一项目第二次进入被拒；释放后可再次进入")
    void lockRejectsSecondConcurrentRun() {
        ProjectRunLock lock = new ProjectRunLock();

        assertThat(lock.tryAcquire("p_1")).isTrue();
        assertThat(lock.tryAcquire("p_1")).as("同项目并发生成必须被拒").isFalse();
        assertThat(lock.isRunning("p_1")).isTrue();

        lock.release("p_1");
        assertThat(lock.tryAcquire("p_1")).isTrue();
    }

    @Test
    @DisplayName("不同项目互不影响；新项目（null id）不占锁")
    void lockIsPerProject() {
        ProjectRunLock lock = new ProjectRunLock();

        assertThat(lock.tryAcquire("p_a")).isTrue();
        assertThat(lock.tryAcquire("p_b")).isTrue();
        assertThat(lock.tryAcquire(null)).as("新建项目没有竞争对象").isTrue();
    }

    // ───────── 保存失败必须显式报错 ─────────

    @Test
    @DisplayName("存储目录不可写时保存抛异常，而不是静默成功")
    void saveFailureIsLoud(@TempDir Path dir) throws Exception {
        // 用一个"文件"占住目录名，让 createDirectories 必然失败
        Path blocked = dir.resolve("blocked");
        Files.writeString(blocked, "not-a-dir");

        FileSystemProjectRepository repo = new FileSystemProjectRepository(
                new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()),
                blocked.toString());

        Project project = Project.create("p_fail", "保存失败", "需求");

        assertThatThrownBy(() -> repo.save(project))
                .as("磁盘问题必须让调用方知道，不能只写日志")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("保存项目失败");
    }
}