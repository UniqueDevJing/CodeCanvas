import type { ReactNode } from 'react';

export interface IconProps {
  size?: number;
  className?: string;
}

interface SvgProps extends IconProps {
  children: ReactNode;
  strokeWidth?: number;
}

function Svg({ size = 16, className, children, strokeWidth = 1.4 }: SvgProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 16 16"
      fill="none"
      stroke="currentColor"
      strokeWidth={strokeWidth}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden="true"
      focusable="false"
    >
      {children}
    </svg>
  );
}

/**
 * CodeCanvas 品牌标识。
 *
 * 构成：渐变圆角方块（画布）＋白色字母 C（CodeCanvas）＋右上角星光（"一句话生成"）。
 * 字母给识别度，星光给"会自己长出来"的暗示——两者合起来才是这个产品在做的事。
 * 32 网格画好后按 size 缩放，24px 下仍然清楚。
 */
export function BrandMark({ size = 24 }: { size?: number }) {
  return (
    <span className="brand-mark">
      <svg
        width={size}
        height={size}
        viewBox="0 0 32 32"
        fill="none"
        aria-hidden="true"
        focusable="false"
      >
        <defs>
          <linearGradient id="cc-brand-grad" x1="2" y1="1" x2="30" y2="31" gradientUnits="userSpaceOnUse">
            <stop offset="0" stopColor="#8b6bff" />
            <stop offset="0.52" stopColor="#5b46f2" />
            <stop offset="1" stopColor="#8b3af0" />
          </linearGradient>
        </defs>

        <rect width="32" height="32" rx="10" fill="url(#cc-brand-grad)" />

        {/* C：右侧留口，给星光腾位置 */}
        <path
          d="M19.24 10.94A6.6 6.6 0 1 0 19.24 21.06"
          stroke="#ffffff"
          strokeWidth="2.7"
          strokeLinecap="round"
        />

        {/* 星光 */}
        <path
          d="M24.6 7.9l0.92 2.24 2.24 0.92-2.24 0.92-0.92 2.24-0.92-2.24-2.24-0.92 2.24-0.92z"
          fill="#ffffff"
        />
      </svg>
    </span>
  );
}

export function CheckIcon(props: IconProps) {
  return (
    <Svg strokeWidth={1.9} {...props}>
      <path d="M3.4 8.4l3 3 6.2-7" />
    </Svg>
  );
}

export function ChevronDownIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M4 6.2 8 10.2l4-4" />
    </Svg>
  );
}

export function PencilIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M11.1 2.6l2.3 2.3-7.9 7.9-2.9.6.6-2.9z" />
    </Svg>
  );
}

export function PlusIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M8 3.4v9.2M3.4 8h9.2" />
    </Svg>
  );
}

export function SendIcon(props: IconProps) {
  return (
    <Svg strokeWidth={1.5} {...props}>
      <path d="M13.6 2.4 7.2 8.8" />
      <path d="M13.6 2.4 9.3 13.6 7.2 8.8 2.4 6.7z" />
    </Svg>
  );
}

export function StopIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <rect x="4.6" y="4.6" width="6.8" height="6.8" rx="1.4" fill="currentColor" stroke="none" />
    </Svg>
  );
}

export function SpinnerIcon({ className, ...rest }: IconProps) {
  return (
    <Svg className={`spin${className ? ` ${className}` : ''}`} {...rest}>
      <path d="M8 2.4a5.6 5.6 0 1 0 5.6 5.6" />
    </Svg>
  );
}

export function DesktopIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <rect x="1.6" y="3" width="12.8" height="8.2" rx="1.3" />
      <path d="M5.8 13.6h4.4M8 11.2v2.4" />
    </Svg>
  );
}

export function TabletIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <rect x="3.4" y="1.6" width="9.2" height="12.8" rx="1.4" />
      <path d="M7.1 12.3h1.8" />
    </Svg>
  );
}

export function MobileIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <rect x="4.8" y="1.6" width="6.4" height="12.8" rx="1.4" />
      <path d="M7.2 12.3h1.6" />
    </Svg>
  );
}

export function RefreshIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M2.6 8a5.4 5.4 0 0 1 9.2-3.8L13.4 5.6" />
      <path d="M13.4 2.6v3.2h-3.2" />
      <path d="M13.4 8a5.4 5.4 0 0 1-9.2 3.8L2.6 10.4" />
      <path d="M2.6 13.4v-3.2h3.2" />
    </Svg>
  );
}

export function ExternalIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M9.6 2.6h3.8v3.8" />
      <path d="M13.4 2.6 7.8 8.2" />
      <path d="M12 9.6v3a1.4 1.4 0 0 1-1.4 1.4H4a1.4 1.4 0 0 1-1.4-1.4V5.4A1.4 1.4 0 0 1 4 4h3" />
    </Svg>
  );
}

/** 最大化：四角朝外——把预览铺满整个窗口 */
export function ExpandIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M6 2.6H2.6V6" />
      <path d="M2.6 2.6 6.4 6.4" />
      <path d="M10 13.4h3.4V10" />
      <path d="M13.4 13.4 9.6 9.6" />
      <path d="M13.4 6V2.6H10" />
      <path d="M13.4 2.6 9.6 6.4" />
      <path d="M2.6 10v3.4H6" />
      <path d="M2.6 13.4 6.4 9.6" />
    </Svg>
  );
}

/** 还原：四角朝内——退出全屏 */
export function ShrinkIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M6.4 2.6V6H2.9" />
      <path d="M2.9 2.6 6.4 6.1" />
      <path d="M9.6 13.4V10h3.5" />
      <path d="M13.1 13.4 9.6 9.9" />
      <path d="M9.6 6V2.6h3.5" />
      <path d="M13.1 2.6 9.6 6.1" />
      <path d="M6.4 10v3.4H2.9" />
      <path d="M2.9 13.4 6.4 9.9" />
    </Svg>
  );
}

/** 文件夹：我的项目 */
export function FolderIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M2.6 4.8a1.4 1.4 0 0 1 1.4-1.4h2.1l1.5 1.7h4.4a1.4 1.4 0 0 1 1.4 1.4v5a1.4 1.4 0 0 1-1.4 1.4H4a1.4 1.4 0 0 1-1.4-1.4z" />
    </Svg>
  );
}

export function GridIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <rect x="2.6" y="2.6" width="4.8" height="4.8" rx="1.2" />
      <rect x="8.6" y="2.6" width="4.8" height="4.8" rx="1.2" />
      <rect x="2.6" y="8.6" width="4.8" height="4.8" rx="1.2" />
      <rect x="8.6" y="8.6" width="4.8" height="4.8" rx="1.2" />
    </Svg>
  );
}

export function DeployIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M4.9 12.4h6.4a2.6 2.6 0 0 0 .3-5.2 3.6 3.6 0 0 0-7-1 2.9 2.9 0 0 0 .3 6.2z" />
      <path d="M8 9.6V6.4M6.6 7.8 8 6.4l1.4 1.4" />
    </Svg>
  );
}

export function ShareIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <circle cx="12.1" cy="3.7" r="1.9" />
      <circle cx="3.9" cy="8" r="1.9" />
      <circle cx="12.1" cy="12.3" r="1.9" />
      <path d="M5.6 7.1 10.4 4.6M5.6 8.9l4.8 2.5" />
    </Svg>
  );
}

export function CloseIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M4.2 4.2 11.8 11.8M11.8 4.2 4.2 11.8" />
    </Svg>
  );
}

export function WarningIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M8 2.6 14.4 13.4H1.6z" />
      <path d="M8 6.4v3.2M8 11.7v.02" />
    </Svg>
  );
}

export function FileIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M9.1 1.8H4.6a1.3 1.3 0 0 0-1.3 1.3v9.8a1.3 1.3 0 0 0 1.3 1.3h6.8a1.3 1.3 0 0 0 1.3-1.3V4.7z" />
      <path d="M9.1 1.8v2.9h2.9" />
    </Svg>
  );
}

export function ClockIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <circle cx="8" cy="8" r="5.8" />
      <path d="M8 4.6V8l2.4 1.5" />
    </Svg>
  );
}

export function SparkIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M8 2.2 9.4 6.1 13.4 7.6 9.4 9.1 8 13 6.6 9.1 2.6 7.6 6.6 6.1z" />
    </Svg>
  );
}

export function WandIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M2.6 13.4 10 6" />
      <path d="M11.2 2.2v2.2M11.2 8.6v2.2M8.2 5.4h2.2M12 5.4h2.2" />
    </Svg>
  );
}

export function UndoIcon(props: IconProps) {
  return (
    <Svg {...props}>
      <path d="M3 6.4h6.4a3.4 3.4 0 0 1 0 6.8H6.2" />
      <path d="M5.4 3.8 2.8 6.4l2.6 2.6" />
    </Svg>
  );
}
