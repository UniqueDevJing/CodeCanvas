import { FileChangeList } from './FileChangeList';
import { CodeStreamBlock } from './CodeStreamBlock';
import { ResultSummary } from './ResultSummary';
import { StageStepper } from './StageStepper';
import { SpinnerIcon, WarningIcon, WandIcon } from './icons';
import { formatTime } from '../utils/format';
import { MODEL_TIER_LABEL } from '../types';
import type { TokensInfo, ToolEvent } from '../types';
import type { AgentMessage } from '../hooks/conversationReducer';

interface AgentMessageCardProps {
  message: AgentMessage;
  onRetry: (text: string) => void;
  /** 最近一条用户消息文本（失败重试时重发）；为空则不显示重试按钮 */
  retryText: string;
}

function money(n: number): string {
  return `$${n.toFixed(4)}`;
}

/**
 * 模型路由决策面板：把「为什么用这个档位、花了多少、省了多少」摊开。
 * 对标 Bolt 的"模型路由明示"——路由不再是黑盒，成本有反事实对照。
 */
function RoutingPanel({ tokens }: { tokens: TokensInfo }) {
  const hasCost = tokens.baselineCost > 0 || tokens.cost > 0;
  const hasContext =
    tokens.contextSavedPercent != null && tokens.contextSavedPercent > 0;
  return (
    <div className="route-block">
      <div className="route-head">
        <span className="route-title">模型路由决策</span>
        <span className="tag tag-accent">
          {MODEL_TIER_LABEL[tokens.tier]} 档
        </span>
        {tokens.model && <span className="route-model">{tokens.model}</span>}
        {tokens.provider && <span className="route-provider">· {tokens.provider}</span>}
        {tokens.degraded && <span className="tag tag-warn">已降级/回退</span>}
      </div>
      {tokens.routeReason && <div className="route-reason">路由原因：{tokens.routeReason}</div>}
      {hasCost && (
        <div className="route-cost">
          <span>全走旗舰档 ≈ <b>{money(tokens.baselineCost)}</b></span>
          <span className="route-arrow">→</span>
          <span>本轮 <b>{money(tokens.cost)}</b></span>
          {tokens.savedPercent > 0 && (
            <span className="route-save">省 {tokens.savedPercent.toFixed(1)}%</span>
          )}
        </div>
      )}
      {hasContext && (
        <div className="route-context">
          上下文工程：注入 {tokens.contextInjected} tok（未压缩 ≈ {tokens.contextRaw}，压缩{' '}
          {tokens.contextSavedPercent}%）
        </div>
      )}
    </div>
  );
}

function stringifyArgs(args: unknown): string {
  if (args == null) return '';
  if (typeof args === 'string') return args;
  try {
    const s = JSON.stringify(args);
    return s.length > 120 ? s.slice(0, 120) + '…' : s;
  } catch {
    return String(args);
  }
}

/**
 * 单条 ReAct 工具调用：名称（可点击感）+ 入参 + 返回。
 * 这是「自主选工具 / 自检修复」过程的可见化，此前前端完全漏接。
 */
function ToolStep({ tool, index }: { tool: ToolEvent; index: number }) {
  const hasResult = tool.result != null && String(tool.result).length > 0;
  return (
    <li className="react-tool">
      <span className="react-tool-idx">{index + 1}</span>
      <div className="react-tool-body">
        <div className="react-tool-line">
          <span className="react-tool-name">{tool.tool}</span>
          {tool.args != null && <span className="react-tool-args">{stringifyArgs(tool.args)}</span>}
        </div>
        {hasResult && <div className="react-tool-result">{stringifyArgs(tool.result)}</div>}
      </div>
    </li>
  );
}

/** Agent 消息卡片：阶段进度 → 状态文案 → 路由决策 → 决策记录 → ReAct 工具流 → 自检修复 → 文件变更 → 代码输出 → 结果摘要 */
export function AgentMessageCard({ message, onRetry, retryText }: AgentMessageCardProps) {
  const streaming = !message.finished && message.result === null && message.error === null;

  // 模型分级路由：流式结束的 tokens 先到，result 落地后取更准的 model/tierLabel
  const modelLabel = message.result?.model ?? message.tokens?.model ?? null;
  const tierLabel =
    message.result?.tierLabel ?? (message.tokens ? MODEL_TIER_LABEL[message.tokens.tier] : null);
  const heal = message.result?.selfHeal ?? null;
  const showHealLoop = heal && (heal.attempts > 0 || !heal.recovered);

  return (
    <article className="agent-card">
      <div className="agent-card-head">
        <span className="agent-avatar" aria-hidden="true">
          <WandIcon size={13} />
        </span>
        <span className="agent-title">CodeCanvas</span>
        {message.trigger === 'RUNTIME_ERROR' && <span className="tag tag-warn">运行时自愈</span>}
        {tierLabel && (
          <span className="tag tag-accent" title={modelLabel ?? ''}>
            {tierLabel} 档{modelLabel ? ` · ${modelLabel}` : ''}
          </span>
        )}
        {showHealLoop && (
          <span className={`tag ${heal!.recovered ? 'tag-ok' : 'tag-warn'}`}>
            自检修复闭环 · {heal!.attempts} 次
          </span>
        )}
        <span className="agent-time">{formatTime(new Date(message.createdAt).toISOString())}</span>
      </div>

      <StageStepper stageIndex={message.stageIndex} done={message.stagesDone} />

      {(message.statusText || streaming) && (
        <div className="agent-status">
          {streaming && <SpinnerIcon size={13} />}
          <span>{message.statusText || '处理中…'}</span>
        </div>
      )}

      {/* 模型路由决策：档位/原因/反事实成本/降级/上下文压缩，全部显形 */}
      {message.tokens && <RoutingPanel tokens={message.tokens} />}

      {/* 决策记录：预算约束 / ReAct 模式 / 回退保版本等，全程留痕（对标 gpt-pilot 的规划可见） */}
      {message.decisions.length > 0 && (
        <div className="decision-block">
          <div className="decision-head">决策记录 · {message.decisions.length}</div>
          <ol className="decision-list">
            {message.decisions.map((d, i) => (
              <li key={i}>{d}</li>
            ))}
          </ol>
        </div>
      )}

      {/* ReAct 工具流：把「agent 在想什么、调了什么工具」摊开给开发者看 */}
      {message.tools.length > 0 && (
        <div className="react-block">
          <div className="react-block-head">
            <span className="react-dot" />
            ReAct 工具流 · {message.tools.length} 步
          </div>
          <ul className="react-list">
            {message.tools.map((tool, i) => (
              <ToolStep key={`${tool.tool}-${i}`} tool={tool} index={i} />
            ))}
          </ul>
        </div>
      )}

      {message.heals.map((h) => (
        <div className="heal-note" key={`${h.attempt}-${h.reason}`}>
          <WarningIcon size={13} />
          <span className="heal-note-title">第 {h.attempt} 次自检修复</span>
          <span className="heal-note-reason">{h.reason}</span>
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
