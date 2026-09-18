import { ClockIcon, UndoIcon } from './icons';
import { formatTime } from '../utils/format';
import type { VersionInfo } from '../types';

interface VersionTimelineProps {
  versions: VersionInfo[];
  rollingBack: boolean;
  onRollback: (versionId: string) => void;
}

/** 版本时间线：最新在最上，当前版本高亮，其余可回滚 */
export function VersionTimeline({ versions, rollingBack, onRollback }: VersionTimelineProps) {
  if (versions.length === 0) {
    return <div className="preview-empty">还没有版本记录，生成后会自动创建第一个版本</div>;
  }

  return (
    <div className="version-timeline">
      {versions.map((version, index) => (
        <div className={`version-item${version.active ? ' is-active' : ''}`} key={version.id}>
          <div className="version-rail" aria-hidden="true">
            <span className="version-dot" />
            {index < versions.length - 1 && <span className="version-line" />}
          </div>

          <div className="version-body">
            <div className="version-head">
              <span className="version-label">{version.label}</span>
              {version.active && <span className="tag tag-accent">当前版本</span>}
              <span className="version-time">
                <ClockIcon size={11} />
                {formatTime(version.createdAt)}
              </span>
            </div>
            <p className="version-summary">{version.summary || '暂无变更说明'}</p>
            {!version.active && (
              <button
                type="button"
                className="btn btn-sm version-rollback"
                onClick={() => onRollback(version.id)}
                disabled={rollingBack}
              >
                <UndoIcon size={13} />
                {rollingBack ? '回滚中…' : '回滚'}
              </button>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}

export default VersionTimeline;
