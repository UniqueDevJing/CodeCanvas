import { afterEach, describe, expect, it, vi } from 'vitest';
import { streamChat } from '../api/chat';

/**
 * SSE 文本协议解析回归（C8）：
 * 事件分块边界、中文多字节、[DONE]、坏 JSON 丢弃、abort 透传。
 * fetch 用桩实现，不依赖后端。
 */

function sseResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  let i = 0;
  const stream = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (i < chunks.length) {
        controller.enqueue(encoder.encode(chunks[i]));
        i += 1;
      } else {
        controller.close();
      }
    },
  });
  return new Response(stream, { status: 200 });
}

function stubFetch(response: Response | Error, captured?: { signal?: AbortSignal }): void {
  vi.stubGlobal('fetch', vi.fn((_url: string, init?: RequestInit) => {
    if (captured && init?.signal) captured.signal = init.signal;
    if (response instanceof Error) return Promise.reject(response);
    return Promise.resolve(response);
  }));
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('streamChat SSE 解析', () => {
  it('解析跨块拆分的事件（分块边界回归）', async () => {
    // 事件被拆在两个网络块中间：data 行被切断
    stubFetch(sseResponse([
      'event: status\ndata: {"text":"正在',
      '生成"}\n\nevent: done\ndata: [DONE]\n\n',
    ]));
    const events: { event: string; data: unknown }[] = [];
    await streamChat({ projectId: null, message: 'x', trigger: 'USER' as const, errors: null },
      { onEvent: (e) => events.push(e) });
    expect(events).toEqual([
      { event: 'status', data: { text: '正在生成' } },
      { event: 'done', data: { ok: true } },
    ]);
  });

  it('中文多字节不被截断（UTF-8 跨块回归）', async () => {
    // "请" 的 UTF-8 是 3 字节，这里把字节级切片交给 decoder stream 模式处理
    const encoder = new TextEncoder();
    const full = encoder.encode('event: status\ndata: {"text":"请帮我做一个待办清单"}\n\n');
    // 从 20 字节处硬切，必然切断某个汉字
    const first = full.slice(0, 20);
    const second = full.slice(20);
    const streamingDecode = (input: Uint8Array, stream: boolean): string =>
      (new TextDecoder('utf-8') as unknown as {
        decode: (input?: Uint8Array, options?: { stream?: boolean }) => string;
      }).decode(input, { stream });
    stubFetch(sseResponse([streamingDecode(first, true), streamingDecode(second, false)]));
    const events: { event: string; data: unknown }[] = [];
    await streamChat({ projectId: null, message: 'x', trigger: 'USER' as const, errors: null },
      { onEvent: (e) => events.push(e) });
    expect(events).toHaveLength(1);
    expect((events[0].data as { text: string }).text).toBe('请帮我做一个待办清单');
  });

  it('丢弃无法解析的 data 分片而不中断流', async () => {
    stubFetch(sseResponse([
      'event: status\ndata: {bad json}\n\nevent: done\ndata: [DONE]\n\n',
    ]));
    const events: { event: string; data: unknown }[] = [];
    await streamChat({ projectId: null, message: 'x', trigger: 'USER' as const, errors: null },
      { onEvent: (e) => events.push(e) });
    expect(events).toEqual([{ event: 'done', data: { ok: true } }]);
  });

  it('HTTP 错误抛出且带状态码', async () => {
    stubFetch(new Response('quota exceeded', { status: 429 }));
    await expect(
      streamChat({ projectId: null, message: 'x', trigger: 'USER' as const, errors: null },
        { onEvent: () => undefined }),
    ).rejects.toThrow('429');
  });

  it('abort 后 fetch 层拒绝并向上透传（取消回归）', async () => {
    const captured: { signal?: AbortSignal } = {};
    stubFetch(new Promise<Response>((_resolve, reject) => {
      setTimeout(() => {
        const err = new Error('The user aborted a request.');
        err.name = 'AbortError';
        reject(err);
      }, 5);
    }) as unknown as Response, captured);
    const controller = new AbortController();
    const run = streamChat({ projectId: null, message: 'x', trigger: 'USER' as const, errors: null },
      { onEvent: () => undefined, signal: controller.signal });
    controller.abort();
    await expect(run).rejects.toMatchObject({ name: 'AbortError' });
    expect(captured.signal?.aborted).toBe(true);
  });

  it('末尾无空行结尾的最后一段事件也会被处理', async () => {
    stubFetch(sseResponse(['event: done\ndata: [DONE]']));
    const events: { event: string; data: unknown }[] = [];
    await streamChat({ projectId: null, message: 'x', trigger: 'USER' as const, errors: null },
      { onEvent: (e) => events.push(e) });
    expect(events).toHaveLength(1);
  });
});
