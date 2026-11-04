package com.codecanvas.contract;

import com.codecanvas.agent.AgentStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 前端 SSE 契约快照（P1-1）。
 *
 * <p>前端按 {@code addEventListener('<name>')} 订阅事件；事件名或阶段枚举一改，
 * 页面就静默失去响应（不报错、只是不动）。这里把两件事钉死：
 * <ol>
 *   <li>{@link AgentStage} 的枚举名与序号（前端展示阶段进度条用）；</li>
 *   <li>{@code SseAgentListener} 实际 emit 的事件名集合。</li>
 * </ol>
 *
 * <p>契约变更时的正确做法：先改本测试的期望值（显式承认协议变化），再改实现。
 */
class SseContractSnapshotTest {

    /** 前端已订阅的全部事件名。新增事件必须同步前端，所以这里要显式加。 */
    private static final Set<String> EXPECTED_EVENTS = new TreeSet<>(List.of(
            "session", "stage", "status", "tool", "file",
            "delta", "tokens", "heal", "result", "error", "done"));

    @Test
    @DisplayName("契约：AgentStage 枚举名与序号固定（前端阶段进度基于 index）")
    void agentStageIsStable() {
        assertThat(Arrays.stream(AgentStage.values()).map(Enum::name).collect(Collectors.toList()))
                .containsExactly("CLARIFY", "PLAN", "GENERATE", "VALIDATE", "HEAL");

        assertThat(Arrays.stream(AgentStage.values()).map(AgentStage::index).collect(Collectors.toList()))
                .containsExactly(1, 2, 3, 4, 5);

        for (AgentStage stage : AgentStage.values()) {
            assertThat(stage.label()).as("%s 的展示名", stage.name()).isNotBlank();
            assertThat(stage.description()).as("%s 的描述", stage.name()).isNotBlank();
        }
    }

    @Test
    @DisplayName("契约：SSE 事件名集合固定（改名会让前端静默失去响应）")
    void sseEventNamesAreStable() throws IOException {
        Path source = Path.of(System.getProperty("user.dir"),
                "src", "main", "java", "com", "codecanvas", "web", "SseAgentListener.java");
        assertThat(source).as("找不到 SseAgentListener 源码: %s", source).exists();

        String text = Files.readString(source, StandardCharsets.UTF_8);
        Set<String> emitted = new TreeSet<>();
        Matcher matcher = Pattern.compile("send\\(\\s*\"([a-z]+)\"").matcher(text);
        while (matcher.find()) {
            emitted.add(matcher.group(1));
        }

        assertThat(emitted)
                .as("SSE 事件名变了 = 前端订阅失效，必须同步前端并更新本快照")
                .isEqualTo(EXPECTED_EVENTS);
    }
}