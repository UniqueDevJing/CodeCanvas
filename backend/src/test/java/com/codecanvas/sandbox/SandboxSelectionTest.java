package com.codecanvas.sandbox;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** P1-4：沙箱后端可插拔选择器的语义与诚实性。 */
class SandboxSelectionTest {

    private static SandboxExecutor backend(String id, int priority, boolean available) {
        return new SandboxExecutor() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public int priority() {
                return priority;
            }

            @Override
            public boolean available() {
                return available;
            }

            @Override
            public String unavailableReason() {
                return available ? null : id + " 未启用";
            }

            @Override
            public SandboxedPreview prepare(String html, String projectId, String versionId) {
                return new SandboxedPreview(html, id, "note");
            }
        };
    }

    @Test
    @DisplayName("高优先级后端不可用时应顺位选择可用的低优先级后端")
    void fallsBackToNextAvailable() {
        SandboxExecutorResolver resolver = new SandboxExecutorResolver(List.of(
                backend("iframe", 100, true), backend("docker", 10, false)));
        assertThat(resolver.id()).isEqualTo("iframe");
        assertThat(resolver.prepare("<html></html>", "p1", "v1").mode()).isEqualTo("iframe");
    }

    @Test
    @DisplayName("都可用时按隔离能力优先级选（容器优先于 iframe）")
    void respectsPriority() {
        SandboxExecutorResolver resolver = new SandboxExecutorResolver(List.of(
                backend("iframe", 100, true), backend("docker", 10, true)));
        assertThat(resolver.id()).isEqualTo("docker");
    }

    @Test
    @DisplayName("一个后端都不可用时拒绝以无隔离方式预览")
    void refusesWhenNothingAvailable() {
        assertThatThrownBy(() -> new SandboxExecutorResolver(List.of(
                backend("iframe", 100, false), backend("docker", 10, false))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("没有可用的沙箱后端");
    }

    @Test
    @DisplayName("自述清单保留不可用后端及原因，不假装它不存在")
    void backendsExposeReasons() {
        // 用真实后端 id，dockerReason() 才能按 id 查到它
        SandboxExecutorResolver resolver = new SandboxExecutorResolver(List.of(
                backend("iframe-sandbox", 100, true), backend("docker-sandbox", 10, false)));
        List<Map<String, Object>> rows = resolver.backends();
        assertThat(rows).hasSize(2);
        assertThat(rows).anyMatch(r -> "docker-sandbox".equals(r.get("id"))
                && Boolean.FALSE.equals(r.get("available"))
                && "docker-sandbox 未启用".equals(r.get("reason")));
        assertThat(rows).anyMatch(r -> Boolean.TRUE.equals(r.get("active")));
        assertThat(resolver.dockerReason()).isEqualTo("docker-sandbox 未启用");
    }

    @Test
    @DisplayName("Docker 后端如实标注：探测结论可见，但不可被选中，prepare 明确拒绝")
    void dockerBackendIsHonest() {
        DockerSandboxExecutor docker = new DockerSandboxExecutor(false);
        assertThat(docker.available()).isFalse();
        assertThat(docker.priority()).isLessThan(100);
        assertThat(docker.probeSummary()).contains("策略未启用");
        assertThat(docker.unavailableReason()).contains("容器沙箱未启用").contains("iframe");
        assertThatThrownBy(() -> docker.prepare("<html></html>", "p1", "v1"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
