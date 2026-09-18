export type PreviewTab = 'preview' | 'code' | 'quality' | 'versions';

export type DeviceKind = 'desktop' | 'tablet' | 'mobile';

/** 不同设备下的预览宽度 */
export const DEVICE_WIDTH: Record<DeviceKind, string> = {
  desktop: '100%',
  tablet: '768px',
  mobile: '390px',
};

export const DEVICE_LABEL: Record<DeviceKind, string> = {
  desktop: '桌面',
  tablet: '平板',
  mobile: '手机',
};
