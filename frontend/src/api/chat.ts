import { API_BASE } from './client';
import type { ChatStreamRequest, ParsedStreamEvent } from '../types';

export const CHAT_STREAM_PATH = `${API_BASE}/chat/stream`;

/** 构建期注入的访问口令 (与后端 CODECANVAS_API_KEY 一致)。浏览器可见，属访问口令而非机密。 */
const API_KEY = (import.meta.env.VITE_API_KEY as string | undefined) ?? '';

export interface StreamChatHandlers {
  onEvent: (event: ParsedStreamEvent) => void;
  signal?: AbortSignal;
}

/**
 * 因为需要 POST 请求体，无法使用 EventSource，
 * 这里用原生 fetch + ReadableStream 手动解析 SSE 文本协议。
 */
export async function streamChat(
  request: ChatStreamRequest,
  handlers: StreamChatHandlers,
): Promise<void> {
  const response = await fetch(CHAT_STREAM_PATH, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(API_KEY ? { 'X-API-Key': API_KEY } : {}),
    },
    body: JSON.stringify(request),
    signal: handlers.signal,
  });

  if (!response.ok) {
    throw new Error(`生成请求失败：${response.status} ${response.statusText}`);
  }
  const body = response.body;
  if (!body) {
    throw new Error('当前浏览器不支持流式响应');
  }

  const reader = body.getReader();
  const decoder = new TextDecoder('utf-8');
  let buffer = '';

  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      // stream: true 保证多字节中文不会被截断
      buffer += decoder.decode(value, { stream: true });
      buffer = buffer.replace(/\r\n/g, '\n');

      let boundary = buffer.indexOf('\n\n');
      while (boundary !== -1) {
        const block = buffer.slice(0, boundary);
        buffer = buffer.slice(boundary + 2);
        emitBlock(block, handlers.onEvent);
        boundary = buffer.indexOf('\n\n');
      }
    }

    // 冲刷解码器 + 处理最后一段没有以空行结尾的数据
    buffer += decoder.decode();
    buffer = buffer.replace(/\r\n/g, '\n');
    if (buffer.trim().length > 0) {
      emitBlock(buffer, handlers.onEvent);
    }
  } finally {
    reader.releaseLock();
  }
}

/** 解析一个 SSE 事件块：event: xxx \n data: {...} */
function emitBlock(block: string, onEvent: (event: ParsedStreamEvent) => void): void {
  let eventName = 'message';
  const dataLines: string[] = [];

  for (const line of block.split('\n')) {
    if (line.length === 0 || line.startsWith(':')) continue;
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);

    if (field === 'event') eventName = value;
    else if (field === 'data') dataLines.push(value);
  }

  if (dataLines.length === 0) return;
  const raw = dataLines.join('\n');
  if (raw === '[DONE]') {
    onEvent({ event: 'done', data: { ok: true } });
    return;
  }

  let data: unknown;
  try {
    data = JSON.parse(raw);
  } catch {
    return; // 丢弃无法解析的分片，保证流不中断
  }
  onEvent({ event: eventName, data } as ParsedStreamEvent);
}
