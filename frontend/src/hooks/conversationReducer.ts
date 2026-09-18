import type {
  DeltaEvent,
  FileEvent,
  HealEvent,
  ModelTier,
  ProjectDetail,
  ProjectFile,
  QualityCheck,
  ResultEvent,
  StageEvent,
  StreamTrigger,
  TokensInfo,
  VersionInfo,
} from '../types';

/* --------------------------------- 状态模型 -------------------------------- */

/** 单个文件的流式代码缓冲区 */
export interface CodeBuffer {
  path: string;
  text: string;
}

export interface AgentMessage {
  id: string;
  role: 'agent';
  trigger: StreamTrigger;
  createdAt: number;
  /** 当前阶段序号，0 表示未开始，1..5 对应 STAGES 下标 +1 */
  stageIndex: number;
  stageDetail: string;
  statusText: string;
  files: FileEvent[];
  buffers: CodeBuffer[];
  activePath: string | null;
  heals: HealEvent[];
  tokens: TokensInfo | null;
  result: ResultEvent | null;
  error: string | null;
  stagesDone: boolean;
  finished: boolean;
  stopped: boolean;
}

export interface UserMessage {
  id: string;
  role: 'user';
  text: string;
  createdAt: number;
}

export type ChatMessage = UserMessage | AgentMessage;

export interface PreviewState {
  html: string;
  files: ProjectFile[];
  versions: VersionInfo[];
  activeVersionId: string | null;
  versionLabel: string | null;
}

export interface ConversationState {
  projectId: string | null;
  projectName: string;
  /** 是否已经发布过（决定顶栏按钮显示"发布"还是"查看发布链接"） */
  deployed: boolean;
  deployKey: string | null;
  messages: ChatMessage[];
  activeAgentId: string | null;
  streaming: boolean;
  preview: PreviewState;
  /** 最近一次生成的质检报告，供右侧「质检」标签页展示 */
  quality: QualityCheck[];
  tokens: TokensInfo | null;
  modelTier: ModelTier;
}

export function createInitialState(): ConversationState {
  return {
    projectId: null,
    projectName: '未命名项目',
    deployed: false,
    deployKey: null,
    messages: [],
    activeAgentId: null,
    streaming: false,
    preview: { html: '', files: [], versions: [], activeVersionId: null, versionLabel: null },
    quality: [],
    tokens: null,
    modelTier: 'STANDARD',
  };
}

/* ---------------------------------- Action -------------------------------- */

export type ConversationAction =
  | { type: 'run/start'; id: string; trigger: StreamTrigger; text: string; echoUser: boolean }
  | { type: 'run/stop' }
  | { type: 'stream/session'; projectId: string }
  | { type: 'stream/stage'; stage: StageEvent }
  | { type: 'stream/status'; text: string }
  | { type: 'stream/file'; file: FileEvent }
  | { type: 'stream/delta'; delta: DeltaEvent }
  | { type: 'stream/tokens'; tokens: TokensInfo }
  | { type: 'stream/heal'; heal: HealEvent }
  | { type: 'stream/result'; result: ResultEvent }
  | { type: 'stream/error'; message: string }
  | { type: 'stream/done' }
  | { type: 'project/loaded'; detail: ProjectDetail }
  | { type: 'project/versions'; detail: ProjectDetail }
  | { type: 'project/rename'; name: string }
  | { type: 'project/deployed'; deployKey: string }
  | { type: 'project/new' };

/* ---------------------------------- Reducer -------------------------------- */

export function conversationReducer(
  state: ConversationState,
  action: ConversationAction,
): ConversationState {
  switch (action.type) {
    case 'run/start': {
      const agent: AgentMessage = {
        id: action.id,
        role: 'agent',
        trigger: action.trigger,
        createdAt: Date.now(),
        stageIndex: 0,
        stageDetail: '',
        statusText: '正在建立连接…',
        files: [],
        buffers: [],
        activePath: null,
        heals: [],
        tokens: null,
        result: null,
        error: null,
        stagesDone: false,
        finished: false,
        stopped: false,
      };
      const echo: UserMessage[] = action.echoUser
        ? [{ id: `${action.id}_u`, role: 'user', text: action.text, createdAt: Date.now() }]
        : [];
      return {
        ...state,
        messages: [...state.messages, ...echo, agent],
        activeAgentId: agent.id,
        streaming: true,
      };
    }

    case 'run/stop': {
      const next = patchActive(state, (m) => ({
        ...m,
        finished: true,
        stopped: true,
        statusText: '已停止生成',
      }));
      return { ...next, streaming: false, activeAgentId: null };
    }

    case 'stream/session':
      return { ...state, projectId: action.projectId };

    case 'stream/stage':
      return patchActive(state, (m) => ({
        ...m,
        stageIndex: Math.max(m.stageIndex, action.stage.index),
        stageDetail: action.stage.detail,
        statusText: action.stage.detail || action.stage.label,
      }));

    case 'stream/status':
      return patchActive(state, (m) => ({ ...m, statusText: action.text }));

    case 'stream/file':
      return patchActive(state, (m) => {
        const exists = m.files.some((f) => f.path === action.file.path);
        const files = exists
          ? m.files.map((f) => (f.path === action.file.path ? action.file : f))
          : [...m.files, action.file];
        return { ...m, files };
      });

    case 'stream/delta':
      return patchActive(state, (m) => {
        const { path, text } = action.delta;
        const exists = m.buffers.some((b) => b.path === path);
        const buffers = exists
          ? m.buffers.map((b) => (b.path === path ? { ...b, text: b.text + text } : b))
          : [...m.buffers, { path, text }];
        return { ...m, buffers, activePath: action.delta.path };
      });

    case 'stream/tokens':
      return {
        ...patchActive(state, (m) => ({ ...m, tokens: action.tokens })),
        tokens: action.tokens,
        modelTier: action.tokens.tier || state.modelTier,
      };

    case 'stream/heal':
      return patchActive(state, (m) => ({ ...m, heals: [...m.heals, action.heal] }));

    case 'stream/result': {
      const result = action.result;
      const next = patchActive(state, (m) => ({
        ...m,
        result,
        tokens: result.tokens ?? m.tokens,
        files: m.files.length > 0 ? m.files : toFileEvents(result.files),
        stagesDone: true,
        stageIndex: 5,
        statusText: '生成完成',
        finished: true,
      }));
      return {
        ...next,
        projectId: result.projectId,
        tokens: result.tokens ?? state.tokens,
        modelTier: result.tokens?.tier || state.modelTier,
        quality: result.quality ?? [],
        preview: {
          html: result.previewHtml,
          files: result.files,
          versions: mergeVersion(state.preview.versions, result),
          activeVersionId: result.versionId,
          versionLabel: result.versionLabel,
        },
      };
    }

    case 'stream/error':
      return {
        ...patchActive(state, (m) => ({
          ...m,
          error: action.message,
          finished: true,
          statusText: '生成失败',
        })),
        streaming: false,
        activeAgentId: null,
      };

    case 'stream/done':
      return { ...patchActive(state, (m) => ({ ...m, finished: true })), streaming: false, activeAgentId: null };

    case 'project/loaded':
    case 'project/versions':
      return applyProjectDetail(state, action.detail);

    case 'project/rename':
      return { ...state, projectName: action.name };

    case 'project/deployed':
      return { ...state, deployed: true, deployKey: action.deployKey };

    // 新建项目：清空会话与预览，回到空状态。后端会在这条新需求发出时创建项目。
    case 'project/new':
      return createInitialState();

    default:
      return state;
  }
}

/* ---------------------------------- 辅助 ---------------------------------- */

function patchActive(
  state: ConversationState,
  updater: (message: AgentMessage) => AgentMessage,
): ConversationState {
  const id = state.activeAgentId;
  if (!id) return state;
  let changed = false;
  const messages = state.messages.map((message) => {
    if (message.id !== id || message.role !== 'agent') return message;
    changed = true;
    return updater(message);
  });
  return changed ? { ...state, messages } : state;
}

function toFileEvents(files: ProjectFile[]): FileEvent[] {
  return files.map((file) => ({ path: file.path, action: 'create', bytes: file.content.length }));
}

/** 新版本插到时间线最前，并把旧版本置为非激活 */
function mergeVersion(versions: VersionInfo[], result: ResultEvent): VersionInfo[] {
  const entry: VersionInfo = {
    id: result.versionId,
    label: result.versionLabel,
    createdAt: new Date().toISOString(),
    summary: result.summary,
    active: true,
  };
  const rest = versions.filter((v) => v.id !== result.versionId).map((v) => ({ ...v, active: false }));
  return [entry, ...rest];
}

function applyProjectDetail(state: ConversationState, detail: ProjectDetail): ConversationState {
  const versions = detail.versions ?? [];
  const active = versions.find((v) => v.active);
  return {
    ...state,
    projectId: detail.id,
    projectName: detail.name || state.projectName,
    deployed: Boolean(detail.deployed),
    deployKey: detail.deployKey ?? null,
    preview: {
      html: detail.previewHtml ?? '',
      files: detail.files ?? [],
      versions,
      activeVersionId: active?.id ?? null,
      versionLabel: active?.label ?? null,
    },
  };
}
