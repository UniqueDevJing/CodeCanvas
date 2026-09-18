import { useCallback, useEffect, useRef } from 'react';
import { streamChat } from '../api/chat';
import type { ParsedStreamEvent, RuntimeErrorPayload, StreamTrigger } from '../types';

export interface AgentRunOptions {
  projectId: string | null;
  message: string;
  trigger: StreamTrigger;
  errors: RuntimeErrorPayload[] | null;
  onEvent: (event: ParsedStreamEvent) => void;
}

export interface AgentStream {
  /** 发起一次流式生成；同一时刻只允许一条流，重复调用会先中断上一条 */
  run: (options: AgentRunOptions) => Promise<void>;
  /** 中止当前流 */
  stop: () => void;
}

/**
 * 封装 SSE 流式生成的生命周期：AbortController 管理、中断、错误透传。
 * 对话状态本身由 useReducer 维护，这里只负责「流」。
 */
export function useAgentStream(): AgentStream {
  const controllerRef = useRef<AbortController | null>(null);

  const stop = useCallback(() => {
    const controller = controllerRef.current;
    if (!controller) return;
    controllerRef.current = null;
    controller.abort();
  }, []);

  const run = useCallback(
    async (options: AgentRunOptions): Promise<void> => {
      stop();

      const controller = new AbortController();
      controllerRef.current = controller;

      try {
        await streamChat(
          {
            projectId: options.projectId,
            message: options.message,
            trigger: options.trigger,
            errors: options.errors,
          },
          { signal: controller.signal, onEvent: options.onEvent },
        );
      } finally {
        if (controllerRef.current === controller) {
          controllerRef.current = null;
        }
      }
    },
    [stop],
  );

  // 组件卸载时确保连接被关闭，避免内存泄漏
  useEffect(
    () => () => {
      controllerRef.current?.abort();
      controllerRef.current = null;
    },
    [],
  );

  return { run, stop };
}
