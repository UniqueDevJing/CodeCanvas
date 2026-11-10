package com.codecanvas.agent.trace;

import com.codecanvas.agent.AgentStage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C6 长任务持久化：RUNNING 快照落盘、重启恢复明确终止、结束状态映射。
 */
class RunTracePersistenceTest {

    /** 转发目标的最小空实现（不关心转发事件本身）。 */
    private static final com.codecanvas.agent.AgentListener NOOP = new com.codecanvas.agent.AgentListener() {
        @Override public void onSession(String projectId, boolean created) { }
        @Override public void onStage(AgentStage stage, String detail) { }
        @Override public void onStatus(String text) { }
        @Override public void onToolCall(String tool, java.util.Map<String, Object> args, String result) { }
        @Override public void onFile(String path, String action, int bytes) { }
        @Override public void onDelta(String path, String text) { }
        @Override public void onTokens(com.codecanvas.agent.UsageView usage) { }
        @Override public void onHeal(int attempt, String reason, String detail) { }
        @Override public void onResult(com.codecanvas.agent.AgentResult result) { }
        @Override public void onError(String message) { }
    };

    private RunTrace aCompletedTrace(String runId) {
        RunTraceRecorder recorder = new RunTraceRecorder(runId, NOOP);
        recorder.begin("INITIAL", "PIPELINE", "做一个待办清单");
        recorder.onSession("proj-1", true);
        recorder.onStage(AgentStage.GENERATE, "正在写页面代码");
        recorder.onResult(new com.codecanvas.agent.AgentResult(
                "proj-1", "v1", "第 1 版", "生成完成", List.of(), "", "iframe-sandbox", "",
                0, true, "mock-model", "mock", List.of(), null, List.of(), null));
        return recorder.finish(null);
    }

    @Test
    void runningSnapshotHasRunningStatus(@TempDir Path tmp) {
        RunTraceRecorder recorder = new RunTraceRecorder(RunTrace.newId(), NOOP);
        recorder.begin("INITIAL", "PIPELINE", "需求");
        recorder.onSession("proj-9", true);
        RunTrace snapshot = recorder.runningSnapshot();
        assertEquals(RunTrace.STATUS_RUNNING, snapshot.status());
        assertTrue(snapshot.isRunning());
        assertEquals("proj-9", snapshot.projectId());
        assertEquals(0, snapshot.durationMs());
    }

    @Test
    void finishMapsStatusCorrectly(@TempDir Path tmp) {
        RunTrace ok = aCompletedTrace("r-ok");
        assertEquals(RunTrace.STATUS_COMPLETED, ok.status());
        assertTrue(ok.ok());

        RunTraceRecorder failRec = new RunTraceRecorder("r-fail", NOOP);
        failRec.begin("INITIAL", "PIPELINE", "x");
        RunTrace failed = failRec.finish("模型调用失败");
        assertEquals(RunTrace.STATUS_FAILED, failed.status());
        assertTrue(failed.statusNote().contains("模型调用失败"));
        org.junit.jupiter.api.Assertions.assertFalse(failed.ok());

        RunTraceRecorder cancelRec = new RunTraceRecorder("r-cancel", NOOP);
        cancelRec.begin("INITIAL", "PIPELINE", "x");
        RunTrace cancelled = cancelRec.finish("client-cancelled", RunTrace.STATUS_CANCELLED);
        assertEquals(RunTrace.STATUS_CANCELLED, cancelled.status());
        assertTrue(cancelled.statusNote().contains("取消"));
    }

    @Test
    void restartTerminatesRunningTasks(@TempDir Path tmp) throws Exception {
        // 1. 模拟上一次进程崩溃：只留下 RUNNING 快照文件
        Path dir = tmp.resolve("traces");
        Files.createDirectories(dir);
        RunTraceRecorder recorder = new RunTraceRecorder("r-orphan", NOOP);
        recorder.begin("INITIAL", "PIPELINE", "一个计算器");
        recorder.onSession("proj-orphan", true);
        RunTrace orphan = recorder.runningSnapshot();
        ObjectMapperHolder.MAPPER.writeValue(dir.resolve("r-orphan.json").toFile(), orphan);

        // 2. 新进程启动：loadAll 应把 RUNNING 明确改判为 TERMINATED
        RunTraceStore store = new RunTraceStore(tmp.toString());
        store.loadAll();
        RunTrace restored = store.get("r-orphan");
        assertNotNull(restored);
        assertEquals(RunTrace.STATUS_TERMINATED, restored.status());
        assertTrue(restored.statusNote().contains("重启"), "终止原因必须说明是服务重启");
        assertTrue(restored.statusNote().contains("终止"));
        assertFalse(restored.ok());
        assertTrue(restored.finishedAt() > 0, "终止时间应补记");
        assertTrue(restored.durationMs() > 0);

        // 3. 改判结果必须写回文件（下次重启看到的是 TERMINATED，不再反复改判）
        String onDisk = Files.readString(dir.resolve("r-orphan.json"));
        assertTrue(onDisk.contains("TERMINATED"));
    }

    @Test
    void completedTracesSurviveRestartUntouched(@TempDir Path tmp) throws Exception {
        Path dir = tmp.resolve("traces");
        Files.createDirectories(dir);
        RunTrace done = aCompletedTrace("r-done");
        ObjectMapperHolder.MAPPER.writeValue(dir.resolve("r-done.json").toFile(), done);

        RunTraceStore store = new RunTraceStore(tmp.toString());
        store.loadAll();
        RunTrace restored = store.get("r-done");
        assertEquals(RunTrace.STATUS_COMPLETED, restored.status(), "已完成任务不得被恢复逻辑改写");
        assertTrue(restored.ok());
    }

    @Test
    void summaryCarriesStatus(@TempDir Path tmp) {
        RunTrace ok = aCompletedTrace("r-sum");
        RunTraceSummary summary = RunTraceSummary.of(ok);
        assertEquals(RunTrace.STATUS_COMPLETED, summary.status());
        assertNotNull(summary.statusNote());
    }

    /** 测试内部共用 ObjectMapper（不加模块依赖的小私有工具）。 */
    private static final class ObjectMapperHolder {
        static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                                false);
    }
}
