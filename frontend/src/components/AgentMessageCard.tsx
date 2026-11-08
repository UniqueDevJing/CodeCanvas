import { FileChangeList } from './FileChangeList';
import { CodeStreamBlock } from './CodeStreamBlock';
import { ResultSummary } from './ResultSummary';
import { StageStepper } from './StageStepper';
import { SpinnerIcon, WarningIcon, WandIcon } from './icons';
import { formatTime } from '../utils/format';
import type { AgentMessage } from '../hooks/conversationReducer';

interface AgentMessageCardProps {
  message: AgentMessage;
  onRetry: (text: string) => void;
  /** 最近一条用户消息文本（失败重试时重发）；为空则不显示重试按钮 */
  retryText: string;
}

/** Agent 消息卡片：阶段进度 → 状态文案 → 文件变更 → 代码输出 → 结果摘要 */
export function AgentMessageCard({ message, onRetry, retryText }: AgentMessageCardProps) {
  const streaming = !message.finished && message.result === null && message.error === null;

  return (
    <article className="agent-card">
      <div className="agent-card-head">
        <span className="agent-avatar" aria-hidden="true">
          <WandIcon size={13} />
        </span>
        <span className="agent-title">CodeCanvas</span>
        {message.trigger === 'RUNTIME_ERROR' && <span className="tag tag-warn">运行时自愈</span>}
        <span className="agent-time">{formatTime(new Date(message.createdAt).toISOString())}</span>
      </div>

      <StageStepper stageIndex={message.stageIndex} done={message.stagesDone} />

      {(message.statusText || streaming) && (
        <div className="agent-status">
          {streaming && <SpinnerIcon size={13} />}
          <span>{message.statusText || '处理中…'}</span>
        </div>
      )}

      {message.heals.map((heal) => (
        <div className="heal-note" key={`${heal.attempt}-${heal.reason}`}>
          <WarningIcon size={13} />
          <span className="heal-note-title">第 {heal.attempt} 次自检修复</span>
          <span className="heal-note-reason">{heal.reason}</span>
        </div>
      ))}

      <FileChangeList files={message.files} />

      <CodeStreamBlock
        buffers={message.buffers}
        activePath={message.activePath}
        streaming={streaming}
      />

      {message.result && <ResultSummary result={message.result} />}

      {message.error && (
        <div className="agent-error">
          <WarningIcon size={13} />
          <span>{message.error}</span>
          {retryText && (
            <button type="button" className="agent-retry" onClick={() => onRetry(retryText)}>
              重试
            </button>
          )}
        </div>
      )}

      {message.stopped && <div className="agent-stopped">已手动停止本次生成</div>}
    </article>
  );
}

export default AgentMessageCard;
