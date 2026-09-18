import { PencilIcon, PlusIcon } from './icons';
import { formatBytes } from '../utils/format';
import type { FileEvent } from '../types';

interface FileChangeListProps {
  files: FileEvent[];
}

const ACTION_LABEL: Record<string, string> = {
  create: '新增',
  modify: '修改',
  update: '修改',
  delete: '删除',
};

export function FileChangeList({ files }: FileChangeListProps) {
  if (files.length === 0) return null;

  return (
    <ul className="file-changes">
      {files.map((file) => {
        const isCreate = file.action === 'create';
        const isDelete = file.action === 'delete';
        return (
          <li className="file-change" key={file.path}>
            <span className={`file-change-icon${isCreate ? ' is-create' : ''}${isDelete ? ' is-delete' : ''}`}>
              {isCreate ? <PlusIcon size={12} /> : <PencilIcon size={12} />}
            </span>
            <span className="file-change-path" title={file.path}>
              {file.path}
            </span>
            <span className="file-change-action">{ACTION_LABEL[file.action] ?? file.action}</span>
            <span className="file-change-bytes">{formatBytes(file.bytes)}</span>
          </li>
        );
      })}
    </ul>
  );
}

export default FileChangeList;
