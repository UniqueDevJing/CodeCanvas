import { useCallback, useState } from 'react';
import { CodeViewer } from './CodeViewer';
import { PreviewStage } from './PreviewStage';
import { PreviewToolbar } from './PreviewToolbar';
import { QualityPanel } from './QualityPanel';
import { VersionTimeline } from './VersionTimeline';
import { ChevronDownIcon, SparkIcon } from './icons';
import type { DeviceKind, PreviewTab } from './previewTypes';
import type { ProjectFile, QualityCheck, VersionInfo } from '../types';
import './PreviewPanel.css';

interface PreviewPanelProps {
  html: string;
  files: ProjectFile[];
  versions: VersionInfo[];
  versionLabel: string | null;
  quality: QualityCheck[];
  rollingBack: boolean;
  collapsed: boolean;
  full: boolean;
  onToggleCollapse: () => void;
  onToggleFull: () => void;
  onRollback: (versionId: string) => void;
  onNotify: (text: string) => void;
}

/**
 * 右侧辅助区：预览 / 代码 / 质检 / 版本。
 *
 * 它可以整块收起——预览是"看结果"的地方，不该跟对话抢空间。
 */
export function PreviewPanel({
  html,
  files,
  versions,
  versionLabel,
  quality,
  rollingBack,
  collapsed,
  full,
  onToggleCollapse,
  onToggleFull,
  onRollback,
  onNotify,
}: PreviewPanelProps) {
  const [tab, setTab] = useState<PreviewTab>('preview');
  const [device, setDevice] = useState<DeviceKind>('desktop');
  const [reloadKey, setReloadKey] = useState(0);

  const hasPreview = html.trim().length > 0;

  const handleRefresh = useCallback(() => setReloadKey((key) => key + 1), []);

  const handleOpenExternal = useCallback(() => {
    if (!hasPreview) return;
    const blob = new Blob([html], { type: 'text/html;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const opened = window.open(url, '_blank', 'noopener,noreferrer');
    if (!opened) {
      onNotify('浏览器拦截了新窗口，请允许弹出窗口后重试');
    }
    window.setTimeout(() => URL.revokeObjectURL(url), 60_000);
  }, [html, hasPreview, onNotify]);

  if (collapsed) {
    return (
      <button
        type="button"
        className="preview-rail"
        onClick={onToggleCollapse}
        title="展开预览区"
      >
        <ChevronDownIcon size={15} className="preview-rail-icon" />
        <span className="preview-rail-label">展开预览</span>
      </button>
    );
  }

  return (
    <section className={`preview-pane${full ? ' is-full' : ''}`} aria-label="预览区">
      <PreviewToolbar
        tab={tab}
        onTabChange={setTab}
        device={device}
        onDeviceChange={setDevice}
        onRefresh={handleRefresh}
        onOpenExternal={handleOpenExternal}
        onCollapse={onToggleCollapse}
        onToggleFull={onToggleFull}
        full={full}
        canRefresh={hasPreview}
      />

      <div className="preview-body">
        {tab === 'preview' &&
          (hasPreview ? (
            <PreviewStage html={html} device={device} reloadKey={reloadKey} />
          ) : (
            <div className="preview-empty-state">
              <span className="preview-empty-icon">
                <SparkIcon size={17} />
              </span>
              <h2>还没有可以预览的内容</h2>
              <p>在左侧描述你想要的应用，生成完成后这里会立刻显示效果。</p>
            </div>
          ))}

        {tab === 'code' && <CodeViewer files={files} />}

        {tab === 'quality' && <QualityPanel checks={quality} />}

        {tab === 'versions' && (
          <div className="version-scroll">
            <div className="version-panel-head">
              <span className="version-panel-title">版本历史</span>
              <span className="version-panel-current">
                {versionLabel ? `当前：${versionLabel}` : `共 ${versions.length} 个版本`}
              </span>
            </div>
            <VersionTimeline
              versions={versions}
              rollingBack={rollingBack}
              onRollback={onRollback}
            />
          </div>
        )}
      </div>
    </section>
  );
}

export default PreviewPanel;
