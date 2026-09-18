import { CheckIcon, WarningIcon } from './icons';
import type { QualityCheck } from '../types';
import './QualityPanel.css';

interface QualityPanelProps {
  checks: QualityCheck[];
}

/**
 * 质检报告。
 *
 * 把"代码生成得对不对"这件事显式摊开给用户看——四项检查逐条列出通过与否。
 * 相比只在出问题时弹个红字，这样用户随时能自己确认交付物的质量。
 */
export function QualityPanel({ checks }: QualityPanelProps) {
  if (checks.length === 0) {
    return (
      <div className="quality-empty">
        <p>还没有可检查的内容，先生成一个应用。</p>
      </div>
    );
  }

  const passedCount = checks.filter((check) => check.passed).length;

  return (
    <div className="quality-scroll">
      <div className="quality-head">
        <span className={`quality-score${passedCount === checks.length ? ' is-ok' : ' is-warn'}`}>
          {passedCount} / {checks.length}
        </span>
        <span className="quality-head-text">
          {passedCount === checks.length
            ? '全部检查通过，页面可以直接使用'
            : '存在未通过项，已保留可运行的部分'}
        </span>
      </div>

      <ul className="quality-list">
        {checks.map((check) => (
          <li className={`quality-item${check.passed ? ' is-ok' : ' is-warn'}`} key={check.id}>
            <span className="quality-icon" aria-hidden="true">
              {check.passed ? <CheckIcon size={13} /> : <WarningIcon size={13} />}
            </span>
            <div className="quality-body">
              <span className="quality-label">{check.label}</span>
              <span className="quality-detail">{check.detail}</span>
            </div>
          </li>
        ))}
      </ul>
    </div>
  );
}

export default QualityPanel;
