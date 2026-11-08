import { useEffect, useRef, useState } from 'react';
import { AgentMessageCard } from './AgentMessageCard';
import type { ChatMessage } from '../hooks/conversationReducer';

interface MessageListProps {
  messages: ChatMessage[];
  onSend: (text: string) => void;
}

/** 用户气泡：hover 时出现复制按钮 */
function UserBubble({ text }: { text: string }) {
  const [copied, setCopied] = useState(false);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    } catch {
      // 剪贴板不可用（非安全上下文）时静默忽略
    }
  };

  return (
    <div className="user-bubble-wrap">
      <div className="user-bubble">{text}</div>
      <button type="button" className="bubble-copy" onClick={copy} title="复制">
        {copied ? '已复制' : '复制'}
      </button>
    </div>
  );
}

export function MessageList({ messages, onSend }: MessageListProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const stickToBottomRef = useRef(true);
  const [showJump, setShowJump] = useState(false);

  // 用户手动上滑时不打断阅读，只有贴在底部时才自动跟随
  const handleScroll = () => {
    const node = containerRef.current;
    if (!node) return;
    const distance = node.scrollHeight - node.scrollTop - node.clientHeight;
    stickToBottomRef.current = distance < 120;
    setShowJump(distance >= 200);
  };

  useEffect(() => {
    const node = containerRef.current;
    if (!node || !stickToBottomRef.current) return;
    node.scrollTop = node.scrollHeight;
  }, [messages]);

  const jumpToBottom = () => {
    const node = containerRef.current;
    if (!node) return;
    stickToBottomRef.current = true;
    setShowJump(false);
    node.scrollTo({ top: node.scrollHeight, behavior: 'smooth' });
  };

  // 失败重试：最近一条用户消息文本
  const retryText = [...messages].reverse().find((m) => m.role === 'user')?.text ?? '';

  return (
    <div className="message-list-wrap">
      <div className="message-list" ref={containerRef} onScroll={handleScroll}>
        {messages.map((message) =>
          message.role === 'user' ? (
            <div className="msg-row is-user" key={message.id}>
              <UserBubble text={message.text} />
            </div>
          ) : (
            <div className="msg-row is-agent" key={message.id}>
              <AgentMessageCard message={message} onRetry={onSend} retryText={retryText} />
            </div>
          ),
        )}
      </div>
      {showJump && (
        <button type="button" className="jump-bottom" onClick={jumpToBottom} title="回到底部">
          ↓ 回到底部
        </button>
      )}
    </div>
  );
}

export default MessageList;
