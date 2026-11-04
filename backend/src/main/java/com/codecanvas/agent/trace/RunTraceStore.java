package com.codecanvas.agent.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行轨迹存储（C3）：一次运行一个 JSON 文件（{storage.dir}/traces/{runId}.json），
 * 与 {@code FileSystemProjectRepository} 同一套文件持久化模式；内存索引供列表/回放/diff 查询。
 */
@Service
public class RunTraceStore {

    private static final Logger log = LoggerFactory.getLogger(RunTraceStore.class);

    private final Path dir;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final Map<String, RunTrace> index = new ConcurrentHashMap<>();

    public RunTraceStore(@Value("${codecanvas.storage.dir:./codecanvas-data}") String storageDir) {
        this.dir = Paths.get(storageDir, "traces");
    }

    @PostConstruct
    void loadAll() {
        try {
            Files.createDirectories(dir);
            try (var stream = Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .forEach(this::loadOne);
            }
            log.info("[trace] 已加载 {} 条历史运行轨迹", index.size());
        } catch (Exception e) {
            log.warn("[trace] 轨迹目录初始化失败（不影响主流程）: {}", e.getMessage());
        }
    }

    private void loadOne(Path file) {
        try {
            RunTrace trace = mapper.readValue(Files.readString(file), RunTrace.class);
            index.put(trace.runId(), trace);
        } catch (Exception e) {
            log.warn("[trace] 轨迹文件损坏，跳过 {}: {}", file.getFileName(), e.getMessage());
        }
    }

    /** 保存轨迹：内存索引 + 落盘（失败仅告警，不影响主流程） */
    public void save(RunTrace trace) {
        if (trace == null) {
            return;
        }
        index.put(trace.runId(), trace);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(trace.runId() + ".json"), mapper.writeValueAsString(trace));
        } catch (Exception e) {
            log.warn("[trace] 轨迹落盘失败（不影响主流程）: {}", e.getMessage());
        }
    }

    /** 最近 limit 次运行的摘要（不含事件明细，按开始时间倒序） */
    public List<RunTraceSummary> list(int limit) {
        List<RunTrace> all = new ArrayList<>(index.values());
        all.sort(Comparator.comparingLong(RunTrace::startedAt).reversed());
        return all.stream().limit(Math.max(1, limit)).map(RunTraceSummary::of).toList();
    }

    /** 单次运行的完整轨迹（回放数据源） */
    public RunTrace get(String runId) {
        return runId == null ? null : index.get(runId);
    }

    /** 可回放运行数（验收指标：轨迹完整、有事件明细的运行数） */
    public int replayableCount() {
        return (int) index.values().stream().filter(t -> !t.events().isEmpty()).count();
    }

    /** diff 两次运行：汇总指标对比 + 分阶段耗时对比 + 差异说明 */
    public RunDiff diff(String fromId, String toId) {
        RunTrace from = get(fromId);
        RunTrace to = get(toId);
        if (from == null || to == null) {
            return null;
        }
        List<String> notes = new ArrayList<>();
        if (from.durationMs() != to.durationMs()) {
            long delta = to.durationMs() - from.durationMs();
            notes.add(String.format("总耗时 %s%+d ms（%.0f → %.0f 秒）", delta >= 0 ? "+" : "", delta,
                    from.durationMs() / 1000.0, to.durationMs() / 1000.0));
        }
        if (from.cost() != to.cost()) {
            notes.add(String.format("成本 %+.4f（%.4f → %.4f）", to.cost() - from.cost(), from.cost(), to.cost()));
        }
        if (from.toolCalls() != to.toolCalls()) {
            notes.add(String.format("工具调用 %+d（%d → %d）", to.toolCalls() - from.toolCalls(),
                    from.toolCalls(), to.toolCalls()));
        }
        if (from.healAttempts() != to.healAttempts()) {
            notes.add(String.format("修复次数 %+d（%d → %d）", to.healAttempts() - from.healAttempts(),
                    from.healAttempts(), to.healAttempts()));
        }
        int fromCtx = from.contextInjected();
        int toCtx = to.contextInjected();
        if (fromCtx != toCtx) {
            notes.add(String.format("注入上下文 %+d tokens（%d → %d）", toCtx - fromCtx, fromCtx, toCtx));
        }
        if (from.ok() != to.ok()) {
            notes.add("结果 " + (from.ok() ? "成功" : "失败") + " → " + (to.ok() ? "成功" : "失败"));
        }
        return new RunDiff(fromId, toId,
                RunTraceSummary.of(from), RunTraceSummary.of(to),
                stageTimings(from), stageTimings(to), List.copyOf(notes));
    }

    /** 各阶段首次进入时间与阶段间隔（供时间旅行对齐比较） */
    private static List<RunDiff.StageTiming> stageTimings(RunTrace trace) {
        List<RunDiff.StageTiming> result = new ArrayList<>();
        RunEvent prev = null;
        for (RunEvent e : trace.events()) {
            if ("stage".equals(e.type())) {
                long gap = prev == null ? e.elapsedMs() : e.elapsedMs() - prev.elapsedMs();
                result.add(new RunDiff.StageTiming(e.stage(), e.elapsedMs(), gap));
                prev = e;
            }
        }
        return result;
    }
}
