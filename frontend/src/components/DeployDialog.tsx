import { useEffect, useRef, useState } from 'react';
import { CheckIcon, CloseIcon, ExternalIcon } from './icons';
import './DeployDialog.css';

interface DeployDialogProps {
  open: boolean;
  url: string;
  /** 是不是第一次发布（影响文案：首次说"发布成功"，之后说"已发布"） */
  firstTime: boolean;
  onClose: () => void;
}

/**
 * 发布结果弹窗。
 *
 * 只做一件事：把公开链接清楚地给到用户，并能一键复制。
 * 参考同类产品的做法——生成之后的下一步就是"拿给别人看"。
 */
export function DeployDialog({ open, url, firstTime, onClose }: DeployDialogProps) {
  const [copied, setCopied] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!open) return;
    setCopied(false);
    const timer = window.setTimeout(() => inputRef.current?.select(), 80);
    return () => window.clearTimeout(timer);
  }, [open, url]);

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [open, onClose]);

  if (!open) return null;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(url);
      setCopied(true);
      window.setTimeout(() => setCopied(false), 2200);
    } catch {
      // 非安全上下文或浏览器拒绝授权时退回手动选择
      inputRef.current?.select();
    }
  };

  return (
    <div className="deploy-mask" role="dialog" aria-modal="true" aria-label="发布结果">
      <div className="deploy-dialog">
        <button type="button" className="deploy-close" onClick={onClose} aria-label="关闭">
          <CloseIcon size={15} />
        </button>

        <div className="deploy-head">
          <span className="deploy-badge">
            <CheckIcon size={14} />
          </span>
          <div>
            <h2 className="deploy-title">{firstTime ? '发布成功' : '已发布'}</h2>
            <p className="deploy-sub">
              把这个链接发给任何人，对方打开就能直接用，<strong>不需要登录</strong>。
            </p>
          </div>
        </div>

        <div className="deploy-link-row">
          <input ref={inputRef} className="deploy-link" value={url} readOnly onFocus={(e) => e.currentTarget.select()} />
          <button type="button" className="btn btn-primary" onClick={copy}>
            {copied ? '已复制' : '复制链接'}
          </button>
        </div>

        <div className="deploy-actions">
          <a className="btn" href={url} target="_blank" rel="noreferrer">
            <ExternalIcon size={15} />
            打开看看
          </a>
          <span className="deploy-hint">继续修改并重新发布时，链接不变，内容会自动更新。</span>
        </div>
      </div>
    </div>
  );
}

export default DeployDialog;
