import { SparkIcon } from './icons';

/**
 * 首次进入的引导态。
 *
 * 只放一句中性引导，不放任何示例需求标签——预设"你可能会想做这些东西"
 * 会把用户往固定的几个方向推，也容易让人以为自己进错了页面。
 */
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
          CodeCanvas 会自动完成需求澄清、任务规划、代码生成、质量校验与自检修复。
        </p>
      </div>
    </div>
  );
}

export default ChatEmptyState;
