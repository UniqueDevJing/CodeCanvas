import { useEffect, useState } from 'react';
import { fetchProjects } from '../api/projects';
import { ClockIcon, CloseIcon, FileIcon } from './icons';
import { formatTime } from '../utils/format';
import type { ProjectSummary } from '../types';
import './ProjectListView.css';

interface ProjectListViewProps {
  open: boolean;
  onClose: () => void;
  /** 打开某个项目；由 App 负责拉详情并切换当前会话 */
  onOpenProject: (projectId: string) => Promise<void>;
}

/**
 * 我的项目：全部做过的应用，按最近更新排在前面。
 *
 * 为什么需要它：作品广场只列**已发布**的作品，没发布过的项目刷新后就找不回来了。
 * 这里是"我的东西都在这儿"的入口，跟发布与否无关。
 */
export function ProjectListView({ open, onClose, onOpenProject }: ProjectListViewProps) {
  const [items, setItems] = useState<ProjectSummary[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [busyId, setBusyId] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setFailed(false);
    setItems(null);

    void (async () => {
      try {
        const list = await fetchProjects();
        // 最近更新的排前面——用户多半是来找刚做过的那个
        if (!cancelled) {
          setItems([...list].sort((a, b) => (a.updatedAt < b.updatedAt ? 1 : -1)));
        }
      } catch {
        if (!cancelled) setFailed(true);
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [open]);

  if (!open) return null;

  const handleOpen = async (projectId: string) => {
    if (busyId) return;
    setBusyId(projectId);
    try {
      await onOpenProject(projectId);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <section className="project-list" aria-label="我的项目">
      <div className="project-list-head">
        <div>
          <h1 className="project-list-title">我的项目</h1>
          <p className="project-list-sub">按最近更新排序，点开就能接着改。发布过的作品也会在作品广场出现。</p>
        </div>
        <button type="button" className="btn" onClick={onClose}>
          <CloseIcon size={15} />
          返回
        </button>
      </div>

      <div className="project-list-body">
        {items === null && !failed && <div className="project-list-hint">正在加载…</div>}

        {failed && <div className="project-list-hint">没能读取项目列表，请确认后端已启动后重试。</div>}

        {items !== null && items.length === 0 && (
          <div className="project-list-hint">还没有项目。返回后描述一句你想要的应用，就会出现在这里。</div>
        )}

        {items !== null && items.length > 0 && (
          <ul className="project-rows">
            {items.map((project) => (
              <li className="project-row" key={project.id}>
                <button
                  type="button"
                  className="project-row-main"
                  onClick={() => void handleOpen(project.id)}
                  disabled={busyId !== null}
                >
                  <span className="project-row-name">{project.name}</span>
                  <span className="project-row-meta">
                    {project.deployed && <span className="project-badge">已发布</span>}
                    <span className="project-row-meta-item">
                      <ClockIcon size={12} />
                      {formatTime(project.updatedAt) || '—'}
                    </span>
                    <span className="project-row-meta-item">
                      <FileIcon size={12} />
                      {project.versionCount} 个版本
                    </span>
                  </span>
                </button>

                <span className="project-row-action">
                  {busyId === project.id ? '打开中…' : '打开'}
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}

export default ProjectListView;
