import { ClockIcon, RefreshIcon, SendIcon, SparkIcon, WandIcon } from './icons';

/**
 * 首次进入的引导态 ——「图纸工坊」首屏。
 *
 * 除了引导输入，还把 Agent 引擎的四种能力摊开：让用户在生成之前就看清背后
 * 跑的是多阶段编排 + 自检闭环 + 分级路由的智能体，而不是黑盒魔法。
 * 参考 v0 / Bolt / Lovable 把「引擎」明示给开发者的做法，但用工程图签的语言重排。
 */
const ENGINE_CAPS = [
  {
    icon: WandIcon,
    title: '五阶段状态机',
    desc: '澄清 → 规划 → 生成 → 校验 → 自检修复，可逐步回放。',
  },
  {
    icon: ClockIcon,
    title: '模型分级路由',
    desc: '按任务难度在旗舰 / 标准 / 轻量档间调度，省成本。',
  },
  {
    icon: RefreshIcon,
    title: '自检修复闭环',
    desc: '质量校验不过或运行时报错，自动定位并修复。',
  },
  {
    icon: SendIcon,
    title: 'ReAct 工具流',
    desc: '自主调用工具、检索上下文、执行校验，过程全可见。',
  },
];

const SPEC_ITEMS = [
  { num: '05', label: 'STAGES' },
  { num: '03', label: 'TIERS' },
  { num: '01', label: 'SELF-HEAL' },
  { num: 'N', label: 'TOOLS' },
];

const EXAMPLES = [
  '做一个番茄钟，25 分钟工作 / 5 分钟休息，能暂停和重置',
  '生成一个记账看板，支持按分类统计和月度趋势',
  '做一个极简的个人作品集首页，深色，带平滑滚动',
];

interface ChatEmptyStateProps {
  onExample?: (text: string) => void;
}

export function ChatEmptyState({ onExample }: ChatEmptyStateProps) {
  return (
    <div className="chat-empty">
      {/* 描边大字水印：不参与交互，也不被读屏 */}
      <span className="chat-empty-watermark" aria-hidden="true">
        CODECANVAS
      </span>

      <div className="chat-empty-inner">
        <div className="ce-eyebrow">Atelier / 01 — Prompt to application</div>

        <span className="chat-empty-icon" aria-hidden="true">
          <SparkIcon size={17} />
        </span>

        <h2 className="chat-empty-title">
          把一句话，<em>落成</em>
          <br />
          一纸可用的应用
        </h2>

        <p className="chat-empty-desc">
          CodeCanvas 是一套多智能体编排引擎：自动完成需求澄清、任务规划、代码生成、质量校验与自检修复。
          你描述意图，它交付成品。
        </p>

        <div className="ce-spec" role="list" aria-label="引擎规格">
          {SPEC_ITEMS.map((item) => (
            <div className="ce-spec-item" role="listitem" key={item.label}>
              <span className="ce-spec-num">{item.num}</span>
              <span className="ce-spec-label">{item.label}</span>
            </div>
          ))}
        </div>

        <div className="ce-section-label">Engine / 引擎能力</div>
        <div className="engine-legend" role="list" aria-label="CodeCanvas Agent 引擎能力">
          {ENGINE_CAPS.map(({ icon: Icon, title, desc }, index) => (
            <div className="engine-cap" role="listitem" key={title}>
              <span className="engine-cap-ico" aria-hidden="true">
                <Icon size={13} />
              </span>
              <div className="engine-cap-body">
                <div className="engine-cap-title">{title}</div>
                <div className="engine-cap-desc">{desc}</div>
              </div>
              <span className="ce-cap-num">0{index + 1}</span>
            </div>
          ))}
        </div>

        <div className="ce-section-label">Try / 从一句开始</div>
        <div className="chat-empty-examples">
          {EXAMPLES.map((text, index) => (
            <button
              type="button"
              className="example-chip"
              key={text}
              onClick={() => onExample?.(text)}
              title={text}
            >
              <span className="ce-chip-idx">0{index + 1}</span>
              {text.length > 18 ? `${text.slice(0, 18)}…` : text}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}

export default ChatEmptyState;
