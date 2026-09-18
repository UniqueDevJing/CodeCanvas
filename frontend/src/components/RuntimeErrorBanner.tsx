import { CloseIcon, SpinnerIcon, WarningIcon } from './icons';
import './RuntimeErrorBanner.css';

export type RuntimeBannerKind = 'healing' | 'limit';

export interface RuntimeBannerState {
  kind: RuntimeBannerKind;
  text: string;
}

interface RuntimeErrorBannerProps {
  banner: RuntimeBannerState;
  onClose: () => void;
}

/** 顶部运行时错误提示条：自动修复中 / 已达到自动修复上限 */
export function RuntimeErrorBanner({ banner, onClose }: RuntimeErrorBannerProps) {
  const healing = banner.kind === 'healing';
  return (
    <div className={`runtime-banner${healing ? '' : ' is-limit'}`} role="status" aria-live="polite">
      <span className="runtime-banner-icon">
        {healing ? <SpinnerIcon size={15} /> : <WarningIcon size={15} />}
      </span>
      <span className="runtime-banner-text">{banner.text}</span>
      {!healing && (
        <button type="button" className="runtime-banner-close" onClick={onClose} aria-label="关闭提示">
          <CloseIcon size={13} />
        </button>
      )}
    </div>
  );
}

export default RuntimeErrorBanner;
