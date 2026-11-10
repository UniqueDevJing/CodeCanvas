import { beforeEach, describe, expect, it } from 'vitest';
import {
  conversationReducer,
  createInitialState,
  type AgentMessage,
  type ConversationState,
} from '../hooks/conversationReducer';

/** 快捷构造：run/start 之后的状态（流式中） */
function started(overrides?: Partial<ConversationState>): ConversationState {
  let s = conversationReducer(createInitialState(), {
    type: 'run/start',
    id: 'm1',
    trigger: 'USER' as const,
    text: '做一个待办清单',
    echoUser: true,
  });
  return overrides ? { ...s, ...overrides } : s;
}

function activeAgent(state: ConversationState): AgentMessage {
  const m = state.messages.find((x) => x.id === state.activeAgentId);
  expect(m).toBeDefined();
  return m as AgentMessage;
}

describe('conversationReducer', () => {
  let state: ConversationState;

  beforeEach(() => {
    state = started();
  });

  it('run/start 开启流式并回显用户消息', () => {
    expect(state.streaming).toBe(true);
    expect(state.messages[0].role).toBe('user');
    const agent = activeAgent(state);
    expect(agent.finished).toBe(false);
    expect(agent.statusText).toBe('正在建立连接…');
  });

  it('session 事件绑定项目', () => {
    const next = conversationReducer(state, { type: 'stream/session', projectId: 'p1' });
    expect(next.projectId).toBe('p1');
  });

  it('stage 事件推进阶段且不回退', () => {
    let next = conversationReducer(state, {
      type: 'stream/stage',
      stage: { stage: 'GENERATE', label: '写代码', index: 3, total: 5, detail: '正在写页面代码' },
    });
    expect(activeAgent(next).stageIndex).toBe(3);
    next = conversationReducer(next, {
      type: 'stream/stage',
      stage: { stage: 'PLAN', label: '规划', index: 2, total: 5, detail: '规划' },
    });
    expect(activeAgent(next).stageIndex).toBe(3);
  });

  it('status 事件追加决策记录且去重', () => {
    let next = conversationReducer(state, { type: 'stream/status', text: '预算约束已生效' });
    next = conversationReducer(next, { type: 'stream/status', text: '预算约束已生效' });
    const agent = activeAgent(next);
    expect(agent.decisions.filter((d) => d === '预算约束已生效').length).toBe(1);
    expect(agent.statusText).toBe('预算约束已生效');
  });

  it('delta 事件按文件聚合流式缓冲', () => {
    let next = conversationReducer(state, { type: 'stream/delta', delta: { path: 'index.html', text: '<html' } });
    next = conversationReducer(next, { type: 'stream/delta', delta: { path: 'index.html', text: '>' } });
    next = conversationReducer(next, { type: 'stream/delta', delta: { path: 'styles.css', text: 'body{}' } });
    const agent = activeAgent(next);
    expect(agent.buffers).toHaveLength(2);
    expect(agent.buffers[0]).toEqual({ path: 'index.html', text: '<html>' });
    expect(agent.activePath).toBe('styles.css');
  });

  it('result 事件置版本为激活并把旧版本下线（回滚回归）', () => {
    // 预置一个旧版本
    const withOldVersion: ConversationState = {
      ...state,
      preview: {
        ...state.preview,
        versions: [{ id: 'v1', label: '第 1 版', createdAt: 'T0', summary: '旧', active: true }],
      },
    };
    const next = conversationReducer(withOldVersion, {
      type: 'stream/result',
      result: {
        projectId: 'p1',
        versionId: 'v2',
        versionLabel: '第 2 版',
        summary: '改了标题',
        files: [{ path: 'index.html', content: '<html></html>' }],
        previewHtml: '<html></html>',
        selfHeal: { attempts: 1, recovered: true },
        quality: [{ id: 'q', label: '结构', passed: true, detail: '' }],
      } as never,
    });
    expect(next.preview.versions[0].id).toBe('v2');
    expect(next.preview.versions[0].active).toBe(true);
    expect(next.preview.versions[1].id).toBe('v1');
    expect(next.preview.versions[1].active).toBe(false);
    expect(next.quality).toHaveLength(1);
    expect(next.streaming).toBe(true);
  });

  it('done 事件结束流式', () => {
    const next = conversationReducer(state, { type: 'stream/done' });
    expect(next.streaming).toBe(false);
    const agent = next.messages.find((m) => m.id === 'm1') as AgentMessage;
    expect(agent.finished).toBe(true);
  });

  it('error 事件终止流式并标记失败', () => {
    const next = conversationReducer(state, { type: 'stream/error', message: '模型调用失败' });
    expect(next.streaming).toBe(false);
    const agent = next.messages.find((m) => m.id === 'm1') as AgentMessage;
    expect(agent.error).toBe('模型调用失败');
    expect(agent.statusText).toBe('生成失败');
  });

  it('run/stop 停止生成（取消回归）', () => {
    let next = conversationReducer(state, { type: 'stream/delta', delta: { path: 'index.html', text: '<html>' } });
    next = conversationReducer(next, { type: 'run/stop' });
    expect(next.streaming).toBe(false);
    expect(next.activeAgentId).toBe(null);
    const agent = next.messages.find((m) => m.id === 'm1') as AgentMessage;
    expect(agent.stopped).toBe(true);
    expect(agent.finished).toBe(true);
    expect(agent.statusText).toBe('已停止生成');
    expect(agent.buffers).toHaveLength(1);
  });

  it('project/loaded 清空会话（切项目回归）', () => {
    let next = conversationReducer(state, { type: 'stream/session', projectId: 'p1' });
    next = conversationReducer(next, {
      type: 'project/loaded',
      detail: {
        id: 'p2',
        name: '另一个项目',
        previewHtml: '<html>x</html>',
        files: [],
        versions: [{ id: 'v1', label: '第 1 版', createdAt: 'T', summary: '', active: true }],
        deployed: false,
      } as never,
    });
    expect(next.projectId).toBe('p2');
    expect(next.messages).toHaveLength(0);
    expect(next.preview.html).toBe('<html>x</html>');
  });

  it('project/versions 保留会话（回滚回归）', () => {
    let next = conversationReducer(state, { type: 'stream/session', projectId: 'p1' });
    next = conversationReducer(next, { type: 'stream/status', text: '决策留痕' });
    next = conversationReducer(next, {
      type: 'project/versions',
      detail: {
        id: 'p1',
        name: '项目',
        previewHtml: '<html>v1</html>',
        files: [],
        versions: [
          { id: 'v1', label: '第 1 版', createdAt: 'T', summary: '', active: true },
          { id: 'v2', label: '第 2 版', createdAt: 'T2', summary: '', active: false },
        ],
        deployed: false,
      } as never,
    });
    expect(next.preview.versions[0].id).toBe('v1');
    expect(next.messages.length).toBeGreaterThan(0);
    expect(next.projectId).toBe('p1');
  });

  it('project/deployed 记录发布状态（发布回归）', () => {
    const next = conversationReducer(state, { type: 'project/deployed', deployKey: 'ab2345cd' });
    expect(next.deployed).toBe(true);
    expect(next.deployKey).toBe('ab2345cd');
  });

  it('project/new 重置到初始状态', () => {
    let next = conversationReducer(state, { type: 'stream/session', projectId: 'p1' });
    next = conversationReducer(next, { type: 'project/deployed', deployKey: 'k' });
    next = conversationReducer(next, { type: 'project/new' });
    expect(next.projectId).toBe(null);
    expect(next.deployed).toBe(false);
    expect(next.messages).toHaveLength(0);
  });
});
