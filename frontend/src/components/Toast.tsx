import { useEffect } from 'react';
import './Toast.css';

interface ToastProps {
  text: string;
  onDismiss: () => void;
}

/** 轻量浮层提示，自动消失 */
export function Toast({ text, onDismiss }: ToastProps) {
  useEffect(() => {
    const timer = window.setTimeout(onDismiss, 2600);
    return () => window.clearTimeout(timer);
  }, [text, onDismiss]);

  return (
    <div className="toast" role="status" aria-live="polite">
      {text}
    </div>
  );
}

export default Toast;
