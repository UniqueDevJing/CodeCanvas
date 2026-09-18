import { useEffect, useState } from 'react';
import { CloseIcon, ExternalIcon } from './icons';
import { fetchGallery } from '../api/projects';
import { buildShareUrl } from '../api/projects';
import type { GalleryItem } from '../types';
import './GalleryView.css';

interface GalleryViewProps {
  open: boolean;
  onClose: () => void;
}

/**
 * 作品广场：所有已发布的作品。
 *
 * 发布过的作品会出现在这里，任何人都能点开直接使用——
 * 让"做出来的东西"有地方被看见，而不只是躺在自己的会话记录里。
 */
export function GalleryView({ open, onClose }: GalleryViewProps) {
  const [items, setItems] = useState<GalleryItem[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!open) return;
    let cancelled = false;
    setFailed(false);
    void (async () => {
      try {
        const list = await fetchGallery();
        if (!cancelled) setItems(list);
      } catch {
        if (!cancelled) {
          setItems([]);
          setFailed(true);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [open]);

  if (!open) return null;

  return (
    <section className="gallery" aria-label="作品广场">
      <div className="gallery-head">
        <div>
          <h1 className="gallery-title">作品广场</h1>
          <p className="gallery-sub">这里都是已经发布的作品，点开就能直接用。</p>
        </div>
        <button type="button" className="btn" onClick={onClose}>
          <CloseIcon size={15} />
          返回
        </button>
      </div>

      <div className="gallery-body">
        {items === null && <div className="gallery-hint">正在加载…</div>}

        {items !== null && failed && (
          <div className="gallery-hint">没能读取作品列表，请稍后重试。</div>
        )}

        {items !== null && !failed && items.length === 0 && (
          <div className="gallery-hint">
            还没有公开发布的作品。生成一个应用后点右上角「发布」，它就会出现在这里。
          </div>
        )}

        {items !== null && items.length > 0 && (
          <ul className="gallery-grid">
            {items.map((item) => (
              <li className="gallery-card" key={item.id}>
                <div className="gallery-card-top">
                  <h2 className="gallery-card-title">{item.name}</h2>
                </div>
                <p className="gallery-card-desc">{item.summary || item.prompt}</p>
                <div className="gallery-card-foot">
                  <span className="gallery-card-time">{formatDate(item.deployedAt)}</span>
                  <a
                    className="btn btn-primary btn-sm"
                    href={buildShareUrl(item.deployKey)}
                    target="_blank"
                    rel="noreferrer"
                  >
                    <ExternalIcon size={14} />
                    打开
                  </a>
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}

function formatDate(value: string): string {
  if (!value) return '';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(
    date.getDate(),
  ).padStart(2, '0')}`;
}

export default GalleryView;
