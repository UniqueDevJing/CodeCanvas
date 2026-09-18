import { DEVICE_WIDTH } from './previewTypes';
import type { DeviceKind } from './previewTypes';

interface PreviewStageProps {
  html: string;
  device: DeviceKind;
  reloadKey: number;
}

/**
 * 预览舞台：居中白色圆角卡片 + iframe srcdoc 渲染。
 *
 * 安全说明：sandbox 只给 allow-scripts 与 allow-modals，
 * 刻意不加 allow-same-origin —— 这样 iframe 处于 opaque origin，
 * 无法读取父窗口的 DOM、localStorage 与 cookie，
 * 同时仍可通过 window.parent.postMessage 回传运行时错误。
 */
export function PreviewStage({ html, device, reloadKey }: PreviewStageProps) {
  return (
    <div className="preview-stage">
      <div className="device-frame" style={{ width: DEVICE_WIDTH[device] }}>
        <iframe
          key={reloadKey}
          className="preview-frame"
          title="应用预览"
          srcDoc={html}
          sandbox="allow-scripts allow-modals"
        />
      </div>
    </div>
  );
}

export default PreviewStage;
