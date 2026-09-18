import {
  ChevronDownIcon,
  DesktopIcon,
  ExpandIcon,
  ExternalIcon,
  MobileIcon,
  RefreshIcon,
  ShrinkIcon,
  TabletIcon,
} from './icons';
import type { DeviceKind, PreviewTab } from './previewTypes';

const TABS: { key: PreviewTab; label: string }[] = [
  { key: 'preview', label: '预览' },
  { key: 'code', label: '代码' },
  { key: 'quality', label: '质检' },
  { key: 'versions', label: '版本' },
];

const DEVICES: { key: DeviceKind; label: string; icon: typeof DesktopIcon }[] = [
  { key: 'desktop', label: '桌面', icon: DesktopIcon },
  { key: 'tablet', label: '平板', icon: TabletIcon },
  { key: 'mobile', label: '手机', icon: MobileIcon },
];

interface PreviewToolbarProps {
  tab: PreviewTab;
  onTabChange: (tab: PreviewTab) => void;
  device: DeviceKind;
  onDeviceChange: (device: DeviceKind) => void;
  onRefresh: () => void;
  onOpenExternal: () => void;
  onCollapse: () => void;
  onToggleFull: () => void;
  full: boolean;
  canRefresh: boolean;
}

export function PreviewToolbar({
  tab,
  onTabChange,
  device,
  onDeviceChange,
  onRefresh,
  onOpenExternal,
  onCollapse,
  onToggleFull,
  full,
  canRefresh,
}: PreviewToolbarProps) {
  return (
    <div className="preview-toolbar">
      <div className="tab-group" role="tablist" aria-label="视图切换">
        {TABS.map((item) => (
          <button
            key={item.key}
            type="button"
            role="tab"
            aria-selected={tab === item.key}
            className={`tab-btn${tab === item.key ? ' is-active' : ''}`}
            onClick={() => onTabChange(item.key)}
          >
            {item.label}
          </button>
        ))}
      </div>

      <div className="toolbar-actions">
        <div className="device-group" role="group" aria-label="设备预览">
          {DEVICES.map((item) => {
            const Icon = item.icon;
            return (
              <button
                key={item.key}
                type="button"
                className={`device-btn${device === item.key ? ' is-active' : ''}`}
                onClick={() => onDeviceChange(item.key)}
                title={item.label}
                aria-pressed={device === item.key}
              >
                <Icon size={15} />
                <span className="visually-hidden">{item.label}</span>
              </button>
            );
          })}
        </div>

        <span className="toolbar-divider toolbar-optional" aria-hidden="true" />

        <button
          type="button"
          className="btn btn-icon btn-sm"
          onClick={onRefresh}
          disabled={!canRefresh}
          title="刷新预览"
        >
          <RefreshIcon size={14} />
        </button>
        <button
          type="button"
          className="btn btn-icon btn-sm"
          onClick={onOpenExternal}
          disabled={!canRefresh}
          title="在新窗口打开"
        >
          <ExternalIcon size={14} />
          <span className="visually-hidden">在新窗口打开</span>
        </button>

        <span className="toolbar-divider" aria-hidden="true" />

        {/* 全屏：让预览占满整个主区，对话区暂时让位——看完整页面用 */}
        <button
          type="button"
          className="btn btn-icon btn-sm"
          onClick={onToggleFull}
          title={full ? '退出全屏（Esc）' : '全屏查看（看清整个页面）'}
          aria-pressed={full}
        >
          {full ? <ShrinkIcon size={15} /> : <ExpandIcon size={15} />}
        </button>

        {/* 收起辅助区，把整个宽度让给对话 */}
        {!full && (
          <button
            type="button"
            className="btn btn-icon btn-sm toolbar-collapse"
            onClick={onCollapse}
            title="收起预览区"
          >
            <ChevronDownIcon size={15} />
          </button>
        )}
      </div>
    </div>
  );
}

export default PreviewToolbar;
