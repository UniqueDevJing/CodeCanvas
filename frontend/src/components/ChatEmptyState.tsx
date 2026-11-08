import { ClockIcon, RefreshIcon, SendIcon, SparkIcon, WandIcon } from './icons';

/**
 * 首次进入的引导态。
 *
 * 除了一句中性的引导，还把「Agent 引擎」的四种能力摊开 ——
 * 让用户在还没生成前就看清背后跑的是一套多阶段编排 + 自检闭环 + 分级路由的智能体，
 * 而不是「黑盒魔法」。这参考了 v0 / Bolt / Lovable 把「引擎」明示给开发者的做法。
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

export function ChatEmptyState() {
  return (
    <div className="chat-empty">
      {/* 背景水印：给空页面一点纵深，不参与交互也不被读屏 */}
      <span className="chat-empty-watermark" aria-hidden="true">
        CODECANVAS
      </span>

      <div className="chat-empty-inner">
        <span className="chat-empty-icon" aria-hidden="true">
          <SparkIcon size={18} />
        </span>
        <h2 className="chat-empty-title">用一句话描述你想要的网页应用</h2>
        <p className="chat-empty-desc">
          CodeCanvas 是一套多智能体编排引擎：自动完成需求澄清、任务规划、代码生成、质量校验与自检修复。
        </p>

        <div className="engine-legend" role="list" aria-label="CodeCanvas Agent 引擎能力">
          {ENGINE_CAPS.map(({ icon: Icon, title, desc }) => (
            <div className="engine-cap" role="listitem" key={title}>
              <span className="engine-cap-ico" aria-hidden="true">
                <Icon size={13} />
              </span>
              <div className="engine-cap-body">
                <div className="engine-cap-title">{title}</div>
                <div className="engine-cap-desc">{desc}</div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

export default ChatEmptyState;
