/** 通用格式化与小工具函数 */

export function formatBytes(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes <= 0) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`;
}

/** 后端返回 2026-09-16T22:10:00 形式，统一展示为 09-16 22:10 */
export function formatTime(input: string): string {
  if (!input) return '';
  const date = new Date(input);
  if (Number.isNaN(date.getTime())) return input;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

export function formatCost(value: number): string {
  if (!Number.isFinite(value)) return '$0.0000';
  return `$${value.toFixed(4)}`;
}

let seq = 0;

export function createId(prefix = 'id'): string {
  seq += 1;
  return `${prefix}_${Date.now().toString(36)}${seq.toString(36)}`;
}

export function countLines(text: string): number {
  if (!text) return 0;
  return text.split('\n').length;
}
