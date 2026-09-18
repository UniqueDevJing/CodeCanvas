/**
 * 统一的 HTTP 基础封装：全部请求走 API 前缀。
 *
 * 前缀由 Vite 的 base 推导，所以同一份代码既能跑在开发环境的根路径，
 * 也能部署到 nginx 的子路径下（如 http://host/canvas/），不需要改代码。
 * 开发时 BASE_URL 为 '/'，请求 /api/** 由 vite proxy 转发到后端。
 */

export class ApiError extends Error {
  readonly status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

export const API_BASE = `${import.meta.env.BASE_URL}api`;

function mergeHeaders(init?: HeadersInit): HeadersInit {
  return { Accept: 'application/json', ...(init as Record<string, string> | undefined) };
}

export async function apiGet<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    method: 'GET',
    headers: mergeHeaders(init?.headers),
  });
  if (!response.ok) {
    throw new ApiError(`请求失败：${response.status} ${response.statusText}`, response.status);
  }
  return (await response.json()) as T;
}

export async function apiPost<T>(path: string, body?: unknown): Promise<T> {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    throw new ApiError(`请求失败：${response.status} ${response.statusText}`, response.status);
  }
  return (await response.json()) as T;
}
