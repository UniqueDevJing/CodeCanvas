import { act, renderHook, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useAgentStream } from '../hooks/useAgentStream';

/**
 * useAgentStream 生命周期回归（C8）：同一时刻一条流、stop 取消、卸载关闭。
 * streamChat 用模块桩替换，不依赖网络。
 */

const streamState = { resolve: null as ((v: void) => void) | null };

vi.mock('../api/chat', () => ({
  streamChat: vi.fn((_req: unknown, handlers: { signal?: AbortSignal }) => {
    const promise = new Promise<void>((resolve, reject) => {
      streamState.resolve = resolve;
      // abort 事件同步 dispatch，reject 放进宏任务并给 promise 挂自愈 catch，
      // 避免外部 matcher 未及时 attach 时被记成 unhandled rejection
      handlers.signal?.addEventListener('abort', () => {
        setTimeout(() => {
          const err = new Error('aborted');
          err.name = 'AbortError';
          reject(err);
        }, 0);
      });
    });
    promise.catch(() => undefined);
    return promise;
  }),
  CHAT_STREAM_PATH: '/api/chat/stream',
}));

afterEach(() => {
  streamState.resolve = null;
  vi.clearAllMocks();
});


describe('useAgentStream', () => {
  it('stop 中止当前流', async () => {
    const { result } = renderHook(() => useAgentStream());
    const promise = result.current.run({
      projectId: null,
      message: '做一个计算器',
      trigger: 'USER' as const,
      errors: null,
      onEvent: () => undefined,
    });
    await waitFor(() => expect(streamState.resolve).not.toBeNull());
    act(() => result.current.stop());
    await expect(promise).rejects.toMatchObject({ name: 'AbortError' });
  });

  it('重复 run 会先中断上一条（同一时刻只允许一条流）', async () => {
    const { result } = renderHook(() => useAgentStream());
    const first = result.current.run({
      projectId: null, message: '第一句', trigger: 'USER' as const, errors: null, onEvent: () => undefined,
    });
    await waitFor(() => expect(streamState.resolve).not.toBeNull());
    const second = result.current.run({
      projectId: null, message: '第二句', trigger: 'USER' as const, errors: null, onEvent: () => undefined,
    });
    await first.catch((e: Error) => expect(e.name).toBe('AbortError'));
    // 第二条流仍然活着：只有显式 stop/正常结束才会 settle
    let secondSettled = false;
    void second.then(() => { secondSettled = true; }, () => undefined);
    await new Promise((r) => setTimeout(r, 10));
    expect(secondSettled).toBe(false);
    act(() => result.current.stop());
    await second.catch((e: Error) => expect(e.name).toBe('AbortError'));
  });

  it('组件卸载时中止在途流', async () => {
    const { result, unmount } = renderHook(() => useAgentStream());
    const promise = result.current.run({
      projectId: null, message: 'x', trigger: 'USER' as const, errors: null, onEvent: () => undefined,
    });
    await waitFor(() => expect(streamState.resolve).not.toBeNull());
    unmount();
    await promise.catch((e: Error) => expect(e.name).toBe('AbortError'));
  });
});
