import { useEffect, useRef } from 'react';

/**
 * 图纸工坊背景层。
 *
 * 固定铺满视口、不接收指针事件、不参与布局。指针坐标只写 CSS 自定义属性，
 * 不经过 React state —— 否则每次移动都会触发整棵应用树重渲染。
 */
export function AtelierBackdrop() {
  const layerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const node = layerRef.current;
    if (!node) return;
    const onMove = (event: PointerEvent) => {
      node.style.setProperty('--cc-mx', `${event.clientX}px`);
      node.style.setProperty('--cc-my', `${event.clientY}px`);
    };
    window.addEventListener('pointermove', onMove, { passive: true });
    return () => window.removeEventListener('pointermove', onMove);
  }, []);

  return (
    <div className="atelier-backdrop" ref={layerRef} aria-hidden="true">
      <span className="ab-grid" />
      <span className="ab-glow" />
      <span className="ab-plot" />
      <span className="ab-noise" />
      <span className="ab-mark ab-mark--tl" />
      <span className="ab-mark ab-mark--tr" />
      <span className="ab-mark ab-mark--bl" />
      <span className="ab-mark ab-mark--br" />
      <span className="ab-titleblock">
        <span>CODECANVAS / ATELIER</span>
        <span>SHEET 01 — PROMPT TO APPLICATION</span>
        <b>REV. 2026.11</b>
      </span>
    </div>
  );
}

export default AtelierBackdrop;
