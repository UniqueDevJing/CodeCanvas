import { apiGet } from './client';

/** 执行轨迹（C3 时间旅行）：与后端 RunTraceSummary / RunTrace / RunDiff 对应 */

export interface RunEvent {
  ts: number;
  elapsedMs: number;
  type: 'session' | 'stage' | 'status' | 'tool' | 'file' | 'tokens' | 'heal' | 'result' | 'error' | 'delta_summary';
  stage: string | null;
  detail: string;
  data: Record<string, unknown>;
}

export interface RunTraceSummary {
  runId: string;
  projectId: string;
  startedAt: number;
  durationMs: number;
  ok: boolean;
  error: string;
  trigger: string;
  requirement: string;
  toolCalls: number;
  healAttempts: number;
  recovered: boolean;
  tier: string;
  cost: number;
  promptTokens: number;
  completionTokens: number;
  eventCount: number;
}

export interface RunTrace extends RunTraceSummary {
  finishedAt: number;
  model: string;
  baselineCost: number;
  contextInjected: number;
  contextRaw: number;
  versionId: string;
  versionLabel: string;
  events: RunEvent[];
}

export interface StageTiming {
  stage: string;
  atMs: number;
  gapMs: number;
}

export interface RunDiff {
  from: string;
  to: string;
  fromSummary: RunTraceSummary;
  toSummary: RunTraceSummary;
  fromStages: StageTiming[];
  toStages: StageTiming[];
  notes: string[];
}

export function fetchTraces(limit = 50): Promise<RunTraceSummary[]> {
  return apiGet(`/traces?limit=${limit}`);
}

export function fetchTrace(runId: string): Promise<RunTrace> {
  return apiGet(`/traces/${runId}`);
}

export function fetchTraceDiff(from: string, to: string): Promise<RunDiff> {
  return apiGet(`/traces/diff?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
}
