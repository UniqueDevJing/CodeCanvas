import { useState } from 'react';
import { ChatEmptyState } from './ChatEmptyState';
import { Composer } from './Composer';
import { MessageList } from './MessageList';
import type { ChatMessage } from '../hooks/conversationReducer';
import './ChatPanel.css';

interface ChatPanelProps {
  messages: ChatMessage[];
  streaming: boolean;
  onSend: (text: string) => void;
  onStop: () => void;
}

/** 左侧对话区：消息流 + 输入框。这是主区域。 */
export function ChatPanel({ messages, streaming, onSend, onStop }: ChatPanelProps) {
  const [draft, setDraft] = useState('');

  const submit = () => {
    const text = draft.trim();
    if (!text || streaming) return;
    onSend(text);
    setDraft('');
  };

  return (
    <section className="chat-pane" aria-label="对话区">
      {messages.length === 0 ? (
        <ChatEmptyState />
      ) : (
        <MessageList messages={messages} />
      )}

      <Composer
        value={draft}
        onChange={setDraft}
        onSubmit={submit}
        onStop={onStop}
        streaming={streaming}
      />
    </section>
  );
}

export default ChatPanel;
