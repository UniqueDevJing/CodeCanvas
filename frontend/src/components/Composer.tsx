import { useEffect, useRef } from 'react';
import type { KeyboardEvent as ReactKeyboardEvent } from 'react';
import { SendIcon, StopIcon } from './icons';
import './Composer.css';

interface ComposerProps {
  value: string;
  onChange: (value: string) => void;
  onSubmit: () => void;
  onStop: () => void;
  streaming: boolean;
}

const MAX_HEIGHT = 160;

export function Composer({ value, onChange, onSubmit, onStop, streaming }: ComposerProps) {
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  // 多行自适应，最高 160px 后内部滚动
  useEffect(() => {
    const node = textareaRef.current;
    if (!node) return;
    node.style.height = 'auto';
    node.style.height = `${Math.min(node.scrollHeight, MAX_HEIGHT)}px`;
  }, [value]);

  const canSend = value.trim().length > 0 && !streaming;

  const handleKeyDown = (event: ReactKeyboardEvent<HTMLTextAreaElement>) => {
    // 中文输入法组合中不拦截回车
    if (event.nativeEvent.isComposing) return;
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault();
      if (canSend) onSubmit();
    }
  };

  return (
    <div className="composer">
      <div className="composer-box">
        <textarea
          ref={textareaRef}
          className="composer-input"
          value={value}
          rows={1}
          placeholder="描述你想要的应用"
          onChange={(event) => onChange(event.target.value)}
          onKeyDown={handleKeyDown}
        />
        <div className="composer-actions">
          <span className="composer-hint">Enter 发送 · Shift + Enter 换行</span>
          {streaming ? (
            <button type="button" className="composer-send is-stop" onClick={onStop} title="停止生成">
              <StopIcon size={15} />
              <span>停止</span>
            </button>
          ) : (
            <button
              type="button"
              className="composer-send"
              onClick={onSubmit}
              disabled={!canSend}
              title="发送"
            >
              <SendIcon size={15} />
            </button>
          )}
        </div>
      </div>
    </div>
  );
}

export default Composer;
