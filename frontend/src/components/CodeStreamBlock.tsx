import { useEffect, useRef, useState } from 'react';
import { ChevronDownIcon, FileIcon, SpinnerIcon } from './icons';
import type { CodeBuffer } from '../hooks/conversationReducer';

interface CodeStreamBlockProps {
  buffers: CodeBuffer[];
  activePath: string | null;
  streaming: boolean;
}

/** 流式代码输出块：等宽字体，超过 12 行内部滚动，右上角可折叠 */
export function CodeStreamBlock({ buffers, activePath, streaming }: CodeStreamBlockProps) {
  const [collapsed, setCollapsed] = useState(false);
  const [selectedPath, setSelectedPath] = useState<string | null>(activePath);
  const bodyRef = useRef<HTMLPreElement>(null);

  useEffect(() => {
    setSelectedPath(activePath);
  }, [activePath]);

  const current =
    buffers.find((buffer) => buffer.path === selectedPath) ?? buffers[buffers.length - 1] ?? null;

  const text = current?.text ?? '';
  const lineCount = text.length === 0 ? 0 : text.split('\n').length;

  // 流式追加时保持滚动到底部，跟住最新生成的代码
  useEffect(() => {
    if (collapsed) return;
    const node = bodyRef.current;
    if (!node) return;
    node.scrollTop = node.scrollHeight;
  }, [text, collapsed]);

  if (!current) return null;

  return (
    <div className="code-stream">
      <div className="code-stream-head">
        <span className="code-stream-file">
          <FileIcon size={13} />
          <span className="code-stream-path">{current.path}</span>
        </span>

        {buffers.length > 1 && (
          <span className="code-stream-tabs">
            {buffers.map((buffer) => (
              <button
                key={buffer.path}
                type="button"
                className={`code-tab${buffer.path === current.path ? ' is-active' : ''}`}
                onClick={() => setSelectedPath(buffer.path)}
              >
                {buffer.path}
              </button>
            ))}
          </span>
        )}

        <span className="code-stream-meta">
          {streaming && <SpinnerIcon size={12} />}
          <span>{lineCount} 行</span>
          <button
            type="button"
            className="code-stream-toggle"
            onClick={() => setCollapsed((value) => !value)}
            aria-expanded={!collapsed}
            title={collapsed ? '展开代码' : '折叠代码'}
          >
            <ChevronDownIcon size={14} className={collapsed ? 'is-flipped' : undefined} />
          </button>
        </span>
      </div>

      {!collapsed && (
        <pre className="code-stream-body" ref={bodyRef}>
          <code>{text}</code>
          {streaming && <span className="code-caret" aria-hidden="true" />}
        </pre>
      )}
    </div>
  );
}

export default CodeStreamBlock;
