import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  fetchTrace,
  fetchTraceDiff,
  fetchTraces,
} from '../api/trace';
import type { RunDiff, RunEvent, RunTrace, RunTraceSummary } from '../api/trace';
import './TraceReplayPanel.css';

/**
 * 执行轨迹面板（C3 时间旅行）：回放任意一次运行、diff 两次运行。
 *
 * 回放在前端完成：每个事件都带 elapsedMs（相对运行起点的毫秒数），
 * 播放时按时间轴推进游标，"重新看一遍当时发生了什么"。
 */
export function TraceReplayPanel() {
  const [runs, setRuns] = useState<RunTraceSummary[] | null>(null);
  const [loadError, setLoadError] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);
  const [trace, setTrace] = useState<RunTrace | null>(null);
  const [diff, setDiff] = useState<RunDiff | null>(null);
  const [playing, setPlaying] = useState(false);
  const [cursorMs, setCursorMs] = useState(0);
  const timerRef = useRef<number | null>(null);

  useEffect(() => {
    fetchTraces(50)
      .then(setRuns)
      .catch(() => setLoadError(true));
  }, []);

  const stop = useCallback(() => {
    if (timerRef.current !== null) {
      window.clearInterval(timerRef.current);
      timerRef.current = null;
    }
    setPlaying(false);
  }, []);

  // 播放循环：按 4x 速度推进游标
  useEffect(() => {
    if (!playing || !trace) return;
    timerRef.current = window.setInterval(() => {
      setCursorMs((ms) => {
        const next = ms + 200; // 4x：每 50ms 真实间隔推进 200ms
        if (next >= trace.durationMs + 500) {
          stop();
          return trace.durationMs + 500;
        }
        return next;
      });
    }, 50);
    return () => {
      if (timerRef.current !== null) {
        window.clearInterval(timerRef.current);
        timerRef.current = null;
      }
    };
  }, [playing, trace, stop]);

  const toggleRun = useCallback(async (runId: string) => {
    stop();
    setDiff(null);
    setTrace(null);
    setCursorMs(0);
    setSelected((prev) => {
      const base = prev.includes(runId) ? prev.filter((id) => id !== runId) : [...prev, runId].slice(-2);
      return base;
    });
  }, [stop]);

  // 选中变化：1 个 → 加载轨迹回放；2 个 → 请求 diff
  useEffect(() => {
    if (selected.length === 1) {
      fetchTrace(selected[0]).then((t) => {
        setTrace(t);
        setCursorMs(0);
      }).catch(() => setLoadError(true));
    } else {
      setTrace(null);
    }
    if (selected.length === 2) {
      fetchTraceDiff(selected[0], selected[1]).then(setDiff).catch(() => setLoadError(true));
    } else {
      setDiff(null);
    }
  }, [selected]);

  const visibleEvents = useMemo<RunEvent[]>(() => {
    if (!trace) return [];
    return trace.events.filter((e) => e.elapsedMs <= cursorMs);
  }, [trace, cursorMs]);

  if (loadError) {
    return <div className="trace-hint">没能读取执行轨迹，请确认后端已启动。</div>;
  }
  if (!runs) {
    return <div className="trace-hint">正在加载运行轨迹…</div>;
  }
  if (runs.length === 0) {
    return <div className="trace-hint">还没有运行轨迹。生成一次应用后，这里就能回放整个过程。</div>;
  }

  return (
    <div className="trace-panel">
      <div className="trace-head">
        <span className="trace-title">执行轨迹（可回放 / 可 diff）</span>
        <span className="trace-mode">
          {selected.length === 2 ? 'diff 模式' : selected.length === 1 ? '回放模式' : '点选 1 条回放、2 条 diff'}
        </span>
      </div>

      <ul className="trace-run-list">
        {runs.map((run) => (
          <li key={run.runId}>
            <button
              type="button"
              className={`trace-run${selected.includes(run.runId) ? ' is-active' : ''}${run.ok ? '' : ' is-failed'}`}
              onClick={() => toggleRun(run.runId)}
            >
              <span className="trace-run-time">{new Date(run.startedAt).toLocaleTimeString()}</span>
              <span className="trace-run-req">{run.requirement || run.trigger || '(未记录需求)'}</span>
              <span className="trace-run-meta">
                {(run.durationMs / 1000).toFixed(1)}s · {run.toolCalls} 工具 · {run.healAttempts} 修复
                {run.ok ? '' : ' · 失败'}
              </span>
            </button>
          </li>
        ))}
      </ul>

      {trace && (
        <div className="trace-replay">
          <div className="trace-controls">
            <button type="button" onClick={() => (playing ? stop() : setPlaying(true))}>
              {playing ? '暂停' : cursorMs > 0 ? '继续' : '回放'}
            </button>
            <button type="button" onClick={() => { stop(); setCursorMs(0); }}>重置</button>
            <span className="trace-progress-label">
              {Math.min(cursorMs, trace.durationMs) / 1000}s / {(trace.durationMs / 1000).toFixed(1)}s
            </span>
          </div>
          <input
            className="trace-scrub"
            type="range"
            min={0}
            max={Math.max(trace.durationMs, 1)}
            value={Math.min(cursorMs, trace.durationMs)}
            onChange={(e) => { stop(); setCursorMs(Number(e.target.value)); }}
          />
          <ol className="trace-events">
            {visibleEvents.map((e, i) => (
              <li key={i} className={`trace-event is-${e.type}`}>
                <span className="trace-event-t">+{(e.elapsedMs / 1000).toFixed(1)}s</span>
                <span className="trace-event-type">{e.type}</span>
                <span className="trace-event-detail">{e.detail}</span>
              </li>
            ))}
          </ol>
        </div>
      )}

      {diff && (
        <div className="trace-diff">
          <div className="trace-diff-head">两次运行 diff（{diff.from.slice(0, 10)}… → {diff.to.slice(0, 10)}…）</div>
          {diff.notes.length === 0
            ? <div className="trace-hint">两次运行的汇总指标完全一致。</div>
            : (
              <ul className="trace-diff-notes">
                {diff.notes.map((n, i) => <li key={i}>{n}</li>)}
              </ul>
            )}
          <table className="trace-diff-table">
            <thead>
              <tr><th>阶段</th><th>{diff.from.slice(0, 8)}</th><th>{diff.to.slice(0, 8)}</th></tr>
            </thead>
            <tbody>
              {diff.fromStages.map((s, i) => {
                const other = diff.toStages[i];
                return (
                  <tr key={s.stage}>
                    <td>{s.stage}</td>
                    <td>{(s.atMs / 1000).toFixed(1)}s</td>
                    <td>{other ? `${(other.atMs / 1000).toFixed(1)}s` : '—'}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          <div className="trace-diff-summary">
            <div>耗时 {(diff.fromSummary.durationMs / 1000).toFixed(1)}s → {(diff.toSummary.durationMs / 1000).toFixed(1)}s</div>
            <div>成本 {diff.fromSummary.cost.toFixed(4)} → {diff.toSummary.cost.toFixed(4)}</div>
            <div>工具 {diff.fromSummary.toolCalls} → {diff.toSummary.toolCalls}｜修复 {diff.fromSummary.healAttempts} → {diff.toSummary.healAttempts}</div>
          </div>
        </div>
      )}
    </div>
  );
}

export default TraceReplayPanel;
