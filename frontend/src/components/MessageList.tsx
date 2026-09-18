import { useEffect, useRef } from 'react';
import { AgentMessageCard } from './AgentMessageCard';
import type { ChatMessage } from '../hooks/conversationReducer';

interface MessageListProps {
  messages: ChatMessage[];
}

export function MessageList({ messages }: MessageListProps) {
  const containerRef = useRef<HTMLDivElement>(null);
  const stickToBottomRef = useRef(true);

  // 用户手动上滑时不打断阅读，只有贴在底部时才自动跟随
  const handleScroll = () => {
    const node = containerRef.current;
    if (!node) return;
    const distance = node.scrollHeight - node.scrollTop - node.clientHeight;
    stickToBottomRef.current = distance < 120;
  };

  useEffect(() => {
    const node = containerRef.current;
    if (!node || !stickToBottomRef.current) return;
    node.scrollTop = node.scrollHeight;
  }, [messages]);

  return (
    <div className="message-list" ref={containerRef} onScroll={handleScroll}>
      {messages.map((message) =>
        message.role === 'user' ? (
          <div className="msg-row is-user" key={message.id}>
            <div className="user-bubble">{message.text}</div>
          </div>
        ) : (
          <div className="msg-row is-agent" key={message.id}>
            <AgentMessageCard message={message} />
          </div>
        ),
      )}
    </div>
  );
}

export default MessageList;
