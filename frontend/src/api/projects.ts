import { apiGet, apiPost } from './client';
import type { DeployInfo, GalleryItem, ProjectDetail, ProjectSummary } from '../types';

interface ProjectListResponse {
  projects: ProjectSummary[];
}

/** GET /api/projects */
export async function fetchProjects(): Promise<ProjectSummary[]> {
  const data = await apiGet<ProjectListResponse>('/projects');
  return data.projects ?? [];
}

/** GET /api/projects/{id} */
export function fetchProject(projectId: string): Promise<ProjectDetail> {
  return apiGet<ProjectDetail>(`/projects/${encodeURIComponent(projectId)}`);
}

/** POST /api/projects/{id}/versions/{versionId}/activate —— 回滚到指定版本 */
export function activateVersion(projectId: string, versionId: string): Promise<ProjectDetail> {
  return apiPost<ProjectDetail>(
    `/projects/${encodeURIComponent(projectId)}/versions/${encodeURIComponent(versionId)}/activate`,
  );
}

/**
 * POST /api/projects/{id}/deploy —— 发布项目，拿到公开访问的部署密钥。
 * 幂等：重复调用返回同一个密钥，已分享出去的链接不会失效。
 */
export function deployProject(projectId: string): Promise<DeployInfo> {
  return apiPost<DeployInfo>(`/projects/${encodeURIComponent(projectId)}/deploy`);
}

/**
 * GET /api/gallery —— 作品广场：所有已发布的作品。
 * 是公开接口，不需要登录。
 */
export async function fetchGallery(): Promise<GalleryItem[]> {
  const data = await apiGet<{ items: GalleryItem[] }>('/gallery');
  return data.items ?? [];
}

/**
 * 由部署密钥推出公开访问地址。
 *
 * 不用后端返回的绝对地址：后端不知道自己被部署在哪个域名/子路径下，
 * 用当前页面的 origin + 当前部署前缀推导出来的链接才是永远正确的
 * （换成域名、加 HTTPS、改前缀都自动跟随）。
 */
export function buildShareUrl(deployKey: string): string {
  const base = import.meta.env.BASE_URL || '/';
  return `${window.location.origin}${base}a/${deployKey}`;
}
