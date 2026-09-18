import { CheckIcon, WarningIcon } from './icons';
import type { ResultEvent } from '../types';

interface ResultSummaryProps {
  result: ResultEvent;
}

/**
 * 生成完成后的结果摘要。
 *
 * 只保留用户关心的两件事：这次做成了什么、有没有自动修复过。
 * token 数、成本、版本号这些属于内部指标，放在界面上只会制造噪音。
 */
export function ResultSummary({ result }: ResultSummaryProps) {
  const attempts = result.selfHeal?.attempts ?? 0;
  const recovered = result.selfHeal?.recovered ?? true;

  return (
    <div className="result-summary">
      <div className="result-summary-head">
        <span className={`result-badge${recovered ? ' is-ok' : ' is-warn'}`}>
          {recovered ? <CheckIcon size={12} /> : <WarningIcon size={12} />}
        </span>
        <span className="result-summary-text">{result.summary}</span>
      </div>

      {attempts > 0 && (
        <div className="result-stats">
          <span className="result-stat">
            自动修复 {attempts} 次{recovered ? '后已可用' : ''}
          </span>
        </div>
      )}
    </div>
  );
}

export default ResultSummary;
