package com.codecanvas.web;

import com.codecanvas.agent.trace.RunDiff;
import com.codecanvas.agent.trace.RunTrace;
import com.codecanvas.agent.trace.RunTraceStore;
import com.codecanvas.agent.trace.RunTraceSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 执行轨迹查询端点（C3 时间旅行）。
 *
 * <p>回放在前端完成：轨迹 JSON 里的每个事件都带 elapsedMs（相对运行起点的毫秒数），
 * 前端按时间轴动画重演即可，无需再开一条 SSE。diff 由后端做——
 * 汇总指标与分阶段耗时的对齐逻辑只写一份。
 */
@RestController
@RequestMapping("/api/traces")
public class TraceController {

    private final RunTraceStore store;

    public TraceController(RunTraceStore store) {
        this.store = store;
    }

    /** 最近 N 次运行摘要（默认 50，不含事件明细） */
    @GetMapping
    public List<RunTraceSummary> list(@RequestParam(defaultValue = "50") int limit) {
        return store.list(limit);
    }

    /** 可回放运行数（验收指标） */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return Map.of("replayableRuns", store.replayableCount());
    }

    /** diff 两次运行（注意：字面路径优先于 /{runId} 匹配） */
    @GetMapping("/diff")
    public RunDiff diff(@RequestParam String from, @RequestParam String to) {
        return store.diff(from, to);
    }

    /** 单次运行的完整轨迹（回放数据源，含事件明细） */
    @GetMapping("/{runId}")
    public RunTrace get(@PathVariable String runId) {
        return store.get(runId);
    }
}
