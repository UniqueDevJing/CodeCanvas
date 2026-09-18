/**
 * 后端接口契约对应的类型定义。
 * 字段名严格与后端一致，不允许在适配层改名。
 */

/* ---------------------------------- 通用 ---------------------------------- */

export type StageKey = 'CLARIFY' | 'PLAN' | 'GENERATE' | 'VALIDATE' | 'HEAL';

export type ModelTier = 'FLAGSHIP' | 'STANDARD' | 'LIGHT';

export type StreamTrigger = 'USER' | 'RUNTIME_ERROR';

export interface StageInfo {
  key: StageKey;
  label: string;
}

/** 五个固定阶段，顺序与后端 index（1..5）一致 */
export const STAGES: StageInfo[] = [
  { key: 'CLARIFY', label: '需求澄清' },
  { key: 'PLAN', label: '任务规划' },
  { key: 'GENERATE', label: '代码生成' },
  { key: 'VALIDATE', label: '质量校验' },
  { key: 'HEAL', label: '自检修复' },
];

export const MODEL_TIER_LABEL: Record<ModelTier, string> = {
  FLAGSHIP: '旗舰',
  STANDARD: '标准',
  LIGHT: '轻量',
};

/* ------------------------------- 流式请求体 -------------------------------- */

/** iframe 运行时错误回传的消息体 */
export interface RuntimeErrorPayload {
  message: string;
  source: string;
  line: number;
  stack: string;
}

export interface ChatStreamRequest {
  projectId: string | null;
  message: string;
  trigger: StreamTrigger;
  errors: RuntimeErrorPayload[] | null;
}

/* ------------------------------- SSE 事件负载 ------------------------------ */

export interface SessionEvent {
  projectId: string;
  created: boolean;
}

export interface StageEvent {
  stage: StageKey;
  label: string;
  index: number;
  total: number;
  detail: string;
}

export interface StatusEvent {
  text: string;
}

export interface FileEvent {
  path: string;
  action: string;
  bytes: number;
}

export interface DeltaEvent {
  path: string;
  text: string;
}

export interface TokensInfo {
  promptTokens: number;
  completionTokens: number;
  cost: number;
  totalCost: number;
  tier: ModelTier;
  model: string;
}

export interface HealEvent {
  attempt: number;
  reason: string;
  detail: string;
}

export interface ProjectFile {
  path: string;
  content: string;
}

/** 一项质检结果。四项检查总是全部返回（通过与否都返回），界面结构才稳定。 */
export interface QualityCheck {
  id: string;
  label: string;
  passed: boolean;
  detail: string;
}

export interface ResultEvent {
  projectId: string;
  versionId: string;
  versionLabel: string;
  summary: string;
  files: ProjectFile[];
  previewHtml: string;
  selfHeal: { attempts: number; recovered: boolean };
  quality?: QualityCheck[];
  tokens: TokensInfo;
}

/** 作品广场里的一个作品 */
export interface GalleryItem {
  id: string;
  name: string;
  prompt: string;
  deployKey: string;
  summary: string;
  deployedAt: string;
  updatedAt: string;
}

export interface StreamErrorEvent {
  message: string;
}

export interface DoneEvent {
  ok: boolean;
}

/** 解析后的单条 SSE 事件，按 event 名做可辨识联合 */
export type ParsedStreamEvent =
  | { event: 'session'; data: SessionEvent }
  | { event: 'stage'; data: StageEvent }
  | { event: 'status'; data: StatusEvent }
  | { event: 'file'; data: FileEvent }
  | { event: 'delta'; data: DeltaEvent }
  | { event: 'tokens'; data: TokensInfo }
  | { event: 'heal'; data: HealEvent }
  | { event: 'result'; data: ResultEvent }
  | { event: 'error'; data: StreamErrorEvent }
  | { event: 'done'; data: DoneEvent };

/* -------------------------------- 项目与配置 ------------------------------- */

export interface VersionInfo {
  id: string;
  label: string;
  createdAt: string;
  summary: string;
  active: boolean;
}

export interface ProjectSummary {
  id: string;
  name: string;
  updatedAt: string;
  versionCount: number;
  /** 是否发布过（发布过的才有公开链接） */
  deployed: boolean;
}

export interface ProjectDetail {
  id: string;
  name: string;
  prompt: string;
  files: ProjectFile[];
  versions: VersionInfo[];
  previewHtml: string;
  deployed: boolean;
  deployKey: string | null;
  deployedAt: string | null;
}

/** POST /api/projects/{id}/deploy 的返回。只给密钥，完整链接由前端按当前访问地址推导。 */
export interface DeployInfo {
  projectId: string;
  deployed: boolean;
  deployKey: string;
  deployedAt: string;
}

export interface AppConfig {
  llmMode: string;
  provider: string;
  models: Record<ModelTier, string>;
  notes: string;
}

/* --------------------------------- 父窗口消息 ------------------------------- */

export interface CodeCanvasMessage {
  source: 'codecanvas';
  type: 'runtime-error';
  payload: RuntimeErrorPayload;
}
