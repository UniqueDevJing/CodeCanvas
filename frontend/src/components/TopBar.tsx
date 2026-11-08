import { useEffect, useRef, useState } from 'react';
import { BrandMark, DeployIcon, FolderIcon, GridIcon, PencilIcon, PlusIcon } from './icons';
import './TopBar.css';

// 顶栏只保留用户真正需要的事：新建、我的项目、作品广场、项目名、发布。
// 模型名/档位/用量这些是内部实现细节，不该出现在产品界面上。
interface TopBarProps {
  projectName: string;
  onRename: (name: string) => void;
  onNewProject: () => void;
  onOpenProjects: () => void;
  onOpenGallery: () => void;
  onDeploy: () => void;
  deployed: boolean;
  busy: boolean;
}

export function TopBar({
  projectName,
  onRename,
  onNewProject,
  onOpenProjects,
  onOpenGallery,
  onDeploy,
  deployed,
  busy,
}: TopBarProps) {
  return (
    <header className="topbar">
      <div className="topbar-left">
        <div className="brand">
          <BrandMark />
          <span className="brand-name">CodeCanvas</span>
        </div>
        <span className="topbar-divider" aria-hidden="true" />
        <button type="button" className="btn btn-ghost" onClick={onNewProject} title="清空当前会话，开始一个新项目">
          <PlusIcon size={15} />
          新建
        </button>
        <span className="topbar-divider" aria-hidden="true" />
        <ProjectNameField name={projectName} onRename={onRename} />
      </div>

      <div className="topbar-right">
        <ThemeToggle />
        <button type="button" className="btn" onClick={onOpenProjects} title="我做过的全部项目">
          <FolderIcon size={15} />
          我的项目
        </button>
        <button type="button" className="btn" onClick={onOpenGallery} title="已经发布出来的作品">
          <GridIcon size={15} />
          作品广场
        </button>
        <button type="button" className="btn btn-primary" onClick={onDeploy} disabled={busy}>
          <DeployIcon size={15} />
          {busy ? '发布中…' : deployed ? '查看发布链接' : '发布'}
        </button>
      </div>
    </header>
  );
}

interface ProjectNameFieldProps {
  name: string;
  onRename: (name: string) => void;
}

const SunIcon = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
    <circle cx="12" cy="12" r="4" />
    <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
  </svg>
);

const MoonIcon = () => (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
    <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z" />
  </svg>
);

/** 暗/亮主题切换：写 data-theme + localStorage；初始值由 index.html 内联脚本提前设置避免闪烁 */
function ThemeToggle() {
  const [theme, setTheme] = useState<'light' | 'dark'>(() =>
    document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light',
  );
  const toggle = () => {
    const next = theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    try {
      localStorage.setItem('cc-theme', next);
    } catch {
      /* 隐私模式下写不进就算了 */
    }
    setTheme(next);
  };
  return (
    <button
      type="button"
      className="btn btn-icon btn-ghost"
      onClick={toggle}
      title={theme === 'dark' ? '切换到亮色' : '切换到暗色'}
      aria-label="切换主题"
    >
      {theme === 'dark' ? <SunIcon /> : <MoonIcon />}
    </button>
  );
}

/** 就地编辑的项目名，回车/失焦提交，Esc 撤销 */
function ProjectNameField({ name, onRename }: ProjectNameFieldProps) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(name);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (!editing) setDraft(name);
  }, [name, editing]);

  useEffect(() => {
    if (editing) inputRef.current?.select();
  }, [editing]);

  const commit = () => {
    const next = draft.trim();
    if (next && next !== name) onRename(next);
    setEditing(false);
  };

  if (editing) {
    return (
      <input
        ref={inputRef}
        className="project-name-input"
        value={draft}
        maxLength={40}
        onChange={(event) => setDraft(event.target.value)}
        onBlur={commit}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault();
            commit();
          } else if (event.key === 'Escape') {
            event.preventDefault();
            setDraft(name);
            setEditing(false);
          }
        }}
      />
    );
  }

  return (
    <button
      type="button"
      className="project-name"
      onClick={() => setEditing(true)}
      title="点击重命名项目"
    >
      <span className="project-name-text">{name}</span>
      <PencilIcon size={13} className="project-name-pencil" />
    </button>
  );
}

export default TopBar;
