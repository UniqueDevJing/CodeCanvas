import { useEffect, useState } from 'react';
import { FileIcon } from './icons';
import type { ProjectFile } from '../types';

interface CodeViewerProps {
  files: ProjectFile[];
}

/** 代码视图：左侧文件列表 + 右侧带行号的代码内容 */
export function CodeViewer({ files }: CodeViewerProps) {
  const [activePath, setActivePath] = useState<string | null>(files[0]?.path ?? null);

  useEffect(() => {
    if (files.length === 0) {
      setActivePath(null);
      return;
    }
    if (!files.some((file) => file.path === activePath)) {
      setActivePath(files[0].path);
    }
  }, [files, activePath]);

  const active = files.find((file) => file.path === activePath) ?? files[0] ?? null;

  if (!active) {
    return <div className="preview-empty">暂无可查看的代码</div>;
  }

  const lines = active.content.split('\n');

  return (
    <div className="code-viewer">
      <aside className="code-file-list">
        <div className="code-file-list-title">文件</div>
        {files.map((file) => (
          <button
            key={file.path}
            type="button"
            className={`code-file-item${file.path === active.path ? ' is-active' : ''}`}
            onClick={() => setActivePath(file.path)}
          >
            <FileIcon size={13} />
            <span className="code-file-name">{file.path}</span>
          </button>
        ))}
      </aside>

      <div className="code-content">
        <div className="code-content-head">
          <span>{active.path}</span>
          <span className="code-content-meta">{lines.length} 行</span>
        </div>
        <div className="code-content-body">
          {lines.map((line, index) => (
            <div className="code-line" key={index}>
              <span className="code-ln">{index + 1}</span>
              <span className="code-lc">{line.length > 0 ? line : ' '}</span>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

export default CodeViewer;
