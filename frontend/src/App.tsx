import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import type {
  CSSProperties,
  KeyboardEvent as ReactKeyboardEvent,
  PointerEvent as ReactPointerEvent,
} from 'react';
import { ChatPanel } from './components/ChatPanel';
import { DeployDialog } from './components/DeployDialog';
import { GalleryView } from './components/GalleryView';
import { PreviewPanel } from './components/PreviewPanel';
import { ProjectListView } from './components/ProjectListView';
import { RuntimeErrorBanner } from './components/RuntimeErrorBanner';
import type { RuntimeBannerState } from './components/RuntimeErrorBanner';
import { Toast } from './components/Toast';
import { TopBar } from './components/TopBar';
import { activateVersion, buildShareUrl, deployProject, fetchProject } from './api/projects';
import { conversationReducer, createInitialState } from './hooks/conversationReducer';
import { useAgentStream } from './hooks/useAgentStream';
import { createId } from './utils/format';
import type {
  CodeCanvasMessage,
  ParsedStreamEvent,
  RuntimeErrorPayload,
  StreamTrigger,
} from './types';
import './App.css';

/** 同一个项目最多自动触发的运行时修复次数，超过则改为提示用户手动输入 */
const MAX_AUTO_HEAL = 2;

/* --------------------------- 预览区宽度（可拖拽） --------------------------- */

/** 预览区最小宽度：再窄工具条的四个标签就放不下了（实测阈值） */
const PREVIEW_MIN_W = 360;
/** 对话区最小宽度：无论怎么拖，都要给它留出能正常读消息的空间 */
const CHAT_MIN_W = 360;
/** 记住用户拖出来的宽度，下次打开还是这个尺寸 */
const PREVIEW_W_STORE_KEY = 'codecanvas.previewWidth';

/**
 * 默认宽度。
 *
 * 早先这里硬顶 620px，宽屏上生成的应用会被挤扁、根本看不全——
 * 现在放宽到 760px，并且允许用户自己拖到更大。
 */
function defaultPreviewWidth(): number {
  const viewport = typeof window === 'undefined' ? 1440 : window.innerWidth;
  return Math.round(Math.min(760, Math.max(400, viewport * 0.46)));
}

function readStoredPreviewWidth(): number {
  try {
    const raw = Number(window.localStorage.getItem(PREVIEW_W_STORE_KEY));
    if (Number.isFinite(raw) && raw >= PREVIEW_MIN_W) return raw;
  } catch {
    // 隐私模式 / 禁用存储时读不到，用默认值即可
  }
  return defaultPreviewWidth();
}

function isAbortError(error: unknown): boolean {
  return (
    typeof error === 'object' &&
    error !== null &&
    (error as { name?: string }).name === 'AbortError'
  );
}

export default function App() {
  const [state, dispatch] = useReducer(conversationReducer, undefined, createInitialState);
  const { run, stop } = useAgentStream();

  const [toast, setToast] = useState<{ id: number; text: string } | null>(null);
  const [banner, setBanner] = useState<RuntimeBannerState | null>(null);
  const [rollingBack, setRollingBack] = useState(false);
  const [pendingError, setPendingError] = useState<RuntimeErrorPayload | null>(null);
  const [healCounts, setHealCounts] = useState<Record<string, number>>({});
  const [deployOpen, setDeployOpen] = useState(false);
  const [deployFirstTime, setDeployFirstTime] = useState(true);
  const [deploying, setDeploying] = useState(false);
  const [shareUrl, setShareUrl] = useState('');
  const [galleryOpen, setGalleryOpen] = useState(false);
  const [projectsOpen, setProjectsOpen] = useState(false);
  const [previewCollapsed, setPreviewCollapsed] = useState(false);
  const [previewFull, setPreviewFull] = useState(false);
  const [previewWidth, setPreviewWidth] = useState(readStoredPreviewWidth);

  const stateRef = useRef(state);
  const toastSeqRef = useRef(0);
  const mainRef = useRef<HTMLElement>(null);
  const draggingRef = useRef(false);
  const previewWidthRef = useRef(previewWidth);

  useEffect(() => {
    stateRef.current = state;
  }, [state]);

  const showToast = useCallback((text: string) => {
    toastSeqRef.current += 1;
    setToast({ id: toastSeqRef.current, text });
  }, []);

  /* ------------------------------ 流式事件分发 ----------------------------- */

  const handleEvent = useCallback((event: ParsedStreamEvent) => {
    switch (event.event) {
      case 'session':
        dispatch({ type: 'stream/session', projectId: event.data.projectId });
        break;
      case 'stage':
        dispatch({ type: 'stream/stage', stage: event.data });
        break;
      case 'status':
        dispatch({ type: 'stream/status', text: event.data.text });
        break;
      case 'file':
        dispatch({ type: 'stream/file', file: event.data });
        break;
      case 'delta':
        dispatch({ type: 'stream/delta', delta: event.data });
        break;
      case 'tokens':
        dispatch({ type: 'stream/tokens', tokens: event.data });
        break;
      case 'heal':
        dispatch({ type: 'stream/heal', heal: event.data });
        break;
      case 'result':
        dispatch({ type: 'stream/result', result: event.data });
        break;
      case 'error':
        dispatch({ type: 'stream/error', message: event.data.message });
        break;
      case 'done':
        dispatch({ type: 'stream/done' });
        break;
      default:
        break;
    }
  }, []);

  /* -------------------------------- 发起生成 ------------------------------- */

  const startRun = useCallback(
    async (
      message: string,
      trigger: StreamTrigger,
      errors: RuntimeErrorPayload[] | null,
      echoUser: boolean,
    ) => {
      if (stateRef.current.streaming) return;

      dispatch({ type: 'run/start', id: createId('m'), trigger, text: message, echoUser });

      try {
        await run({
          projectId: stateRef.current.projectId,
          message,
          trigger,
          errors,
          onEvent: handleEvent,
        });
        // 兜底：后端若未发送 done 事件就关闭连接，也要收敛「生成中」状态
        dispatch({ type: 'stream/done' });
      } catch (error) {
        if (isAbortError(error)) {
          dispatch({ type: 'run/stop' });
        } else {
          dispatch({
            type: 'stream/error',
            message: error instanceof Error ? error.message : '生成失败，请稍后重试',
          });
        }
      }
    },
    [handleEvent, run],
  );

  const handleSend = useCallback(
    (text: string) => {
      void startRun(text, 'USER', null, true);
    },
    [startRun],
  );

  const handleStop = useCallback(() => {
    stop();
    dispatch({ type: 'run/stop' });
  }, [stop]);

  /* --------------------- iframe 运行时错误回传（自愈入口） -------------------- */

  useEffect(() => {
    const onMessage = (event: MessageEvent) => {
      const data = event.data as Partial<CodeCanvasMessage> | null;
      if (!data || typeof data !== 'object') return;
      if (data.source !== 'codecanvas' || data.type !== 'runtime-error') return;
      if (!data.payload || typeof data.payload.message !== 'string') return;
      setPendingError(data.payload);
    };

    window.addEventListener('message', onMessage);
    return () => window.removeEventListener('message', onMessage);
  }, []);

  useEffect(() => {
    if (!pendingError || state.streaming) return;

    const projectId = state.projectId;
    const used = projectId ? healCounts[projectId] ?? 0 : 0;

    if (!projectId) {
      setBanner({ kind: 'limit', text: '检测到运行时错误，请先在左侧补充你的修改需求' });
      setPendingError(null);
      return;
    }

    if (used >= MAX_AUTO_HEAL) {
      setBanner({
        kind: 'limit',
        text: `检测到运行时错误，已自动修复 ${MAX_AUTO_HEAL} 次仍未解决，请在左侧手动描述问题继续修复`,
      });
      setPendingError(null);
      return;
    }

    const payload = pendingError;
    setPendingError(null);
    setHealCounts((prev) => ({ ...prev, [projectId]: used + 1 }));
    setBanner({ kind: 'healing', text: '检测到运行时错误，正在自动修复…' });

    void startRun(
      `预览页面运行时报错：${payload.message}（${payload.source}${payload.line ? `:${payload.line}` : ''}）。请定位原因并修复。`,
      'RUNTIME_ERROR',
      [payload],
      false,
    );
  }, [pendingError, state.streaming, state.projectId, healCounts, startRun]);

  // 自动修复结束后收起「正在修复」提示条
  useEffect(() => {
    if (state.streaming || pendingError) return;
    setBanner((current) => (current && current.kind === 'healing' ? null : current));
  }, [state.streaming, pendingError]);

  /* --------------------------------- 顶栏动作 ------------------------------- */

  const handleRename = useCallback((name: string) => {
    dispatch({ type: 'project/rename', name });
  }, []);

  const handleNewProject = useCallback(() => {
    if (stateRef.current.streaming) {
      showToast('正在生成中，先等这一轮结束再新建');
      return;
    }
    dispatch({ type: 'project/new' });
    setGalleryOpen(false);
    setProjectsOpen(false);
    showToast('已清空会话，说说你想要的新应用吧');
  }, [showToast]);

  /**
   * 从「我的项目」打开一个已存在的项目。
   *
   * 生成中途切走会让流式结果落到错误的会话上，所以正在生成时直接拦掉。
   */
  const handleOpenProject = useCallback(
    async (projectId: string) => {
      if (stateRef.current.streaming) {
        showToast('正在生成中，先等这一轮结束再切换项目');
        return;
      }
      try {
        const detail = await fetchProject(projectId);
        dispatch({ type: 'project/loaded', detail });
        setProjectsOpen(false);
        setGalleryOpen(false);
        // 换了项目，之前的自动修复计数不该带过来
        setHealCounts({});
        showToast(`已打开「${detail.name}」`);
      } catch {
        showToast('打开项目失败，请稍后重试');
      }
    },
    [showToast],
  );

  const handleDeploy = useCallback(async () => {
    const projectId = stateRef.current.projectId;
    if (!projectId) {
      showToast('还没有可以发布的应用，先生成一个吧');
      return;
    }
    if (stateRef.current.streaming) {
      showToast('正在生成中，等这一轮结束再发布');
      return;
    }

    setDeploying(true);
    try {
      const info = await deployProject(projectId);
      const isFirstTime = !stateRef.current.deployed;
      dispatch({ type: 'project/deployed', deployKey: info.deployKey });
      setShareUrl(buildShareUrl(info.deployKey));
      setDeployFirstTime(isFirstTime);
      setDeployOpen(true);
    } catch {
      showToast('发布失败，请稍后重试');
    } finally {
      setDeploying(false);
    }
  }, [showToast]);

  /** 已经发布过的项目再点按钮，直接展示链接，不用重新请求。 */
  const handleDeployClick = useCallback(() => {
    const current = stateRef.current;
    if (current.deployed && current.projectId && !deploying) {
      setDeployFirstTime(false);
      setShareUrl(buildShareUrl(current.deployKey ?? ''));
      setDeployOpen(true);
      return;
    }
    void handleDeploy();
  }, [deploying, handleDeploy]);

  /* ---------------------------------- 回滚 --------------------------------- */

  const handleRollback = useCallback(
    async (versionId: string) => {
      const projectId = stateRef.current.projectId;
      if (!projectId || stateRef.current.streaming) return;

      setRollingBack(true);
      try {
        const detail = await activateVersion(projectId, versionId);
        dispatch({ type: 'project/versions', detail });
        showToast('已回滚到所选版本');
      } catch {
        showToast('回滚失败，请稍后重试');
      } finally {
        setRollingBack(false);
      }
    },
    [showToast],
  );

  /* ------------------------------- 预览区布局 ------------------------------- */

  useEffect(() => {
    previewWidthRef.current = previewWidth;
  }, [previewWidth]);

  const clampPreviewWidth = useCallback((next: number) => {
    const main = mainRef.current;
    const total = main?.clientWidth ?? window.innerWidth;
    // 分隔条自己也要占宽度，不算进来的话对话区会被挤到下限定值以下
    const splitter = main?.querySelector('.pane-splitter')?.getBoundingClientRect().width ?? 0;
    const max = Math.max(PREVIEW_MIN_W, total - CHAT_MIN_W - splitter);
    return Math.round(Math.min(Math.max(next, PREVIEW_MIN_W), max));
  }, []);

  const persistPreviewWidth = useCallback((width: number) => {
    try {
      window.localStorage.setItem(PREVIEW_W_STORE_KEY, String(width));
    } catch {
      // 存不下不影响本次使用，忽略
    }
  }, []);

  const handleSplitterDown = useCallback((event: ReactPointerEvent<HTMLDivElement>) => {
    event.preventDefault();
    event.currentTarget.setPointerCapture(event.pointerId);
    draggingRef.current = true;
    document.body.classList.add('is-col-resizing');
  }, []);

  const handleSplitterMove = useCallback(
    (event: ReactPointerEvent<HTMLDivElement>) => {
      if (!draggingRef.current) return;
      const rect = mainRef.current?.getBoundingClientRect();
      if (!rect) return;
      setPreviewWidth(clampPreviewWidth(rect.right - event.clientX));
    },
    [clampPreviewWidth],
  );

  const handleSplitterUp = useCallback(
    (event: ReactPointerEvent<HTMLDivElement>) => {
      if (!draggingRef.current) return;
      draggingRef.current = false;
      event.currentTarget.releasePointerCapture(event.pointerId);
      document.body.classList.remove('is-col-resizing');
      // 拖动过程中不写 localStorage（每帧同步写会卡），松手才落盘
      persistPreviewWidth(previewWidthRef.current);
    },
    [persistPreviewWidth],
  );

  /** 键盘也能调：聚焦分隔条后按左右方向键，Shift 加速 */
  const handleSplitterKeyDown = useCallback(
    (event: ReactKeyboardEvent<HTMLDivElement>) => {
      if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return;
      event.preventDefault();
      const step = event.shiftKey ? 80 : 24;
      setPreviewWidth((current) => {
        const next = clampPreviewWidth(event.key === 'ArrowLeft' ? current + step : current - step);
        persistPreviewWidth(next);
        return next;
      });
    },
    [clampPreviewWidth, persistPreviewWidth],
  );

  // 窗口变窄时把预览宽度收进合理范围，别让对话区被挤没
  useEffect(() => {
    const onResize = () => setPreviewWidth((current) => clampPreviewWidth(current));
    window.addEventListener('resize', onResize);
    return () => window.removeEventListener('resize', onResize);
  }, [clampPreviewWidth]);

  // 全屏预览时按 Esc 退出
  useEffect(() => {
    if (!previewFull) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setPreviewFull(false);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [previewFull]);

  const splitterVisible = !previewFull && !previewCollapsed;

  /** 双击分隔条回到默认宽度 */
  const resetPreviewWidth = useCallback(() => {
    const next = clampPreviewWidth(defaultPreviewWidth());
    setPreviewWidth(next);
    persistPreviewWidth(next);
  }, [clampPreviewWidth, persistPreviewWidth]);

  /* ---------------------------------- 渲染 --------------------------------- */

  return (
    <div className="app">
      <TopBar
        projectName={state.projectName}
        onRename={handleRename}
        onNewProject={handleNewProject}
        onOpenProjects={() => setProjectsOpen(true)}
        onOpenGallery={() => setGalleryOpen(true)}
        onDeploy={handleDeployClick}
        deployed={state.deployed}
        busy={deploying}
      />

      {banner && <RuntimeErrorBanner banner={banner} onClose={() => setBanner(null)} />}

      <DeployDialog
        open={deployOpen}
        url={shareUrl}
        firstTime={deployFirstTime}
        onClose={() => setDeployOpen(false)}
      />

      {projectsOpen ? (
        <ProjectListView
          open={projectsOpen}
          onClose={() => setProjectsOpen(false)}
          onOpenProject={handleOpenProject}
        />
      ) : galleryOpen ? (
        <GalleryView open={galleryOpen} onClose={() => setGalleryOpen(false)} />
      ) : (
        <main
          className="app-main"
          ref={mainRef}
          style={{ '--preview-w': `${previewWidth}px` } as CSSProperties}
        >
          {!previewFull && (
            <ChatPanel
              messages={state.messages}
              streaming={state.streaming}
              onSend={handleSend}
              onStop={handleStop}
            />
          )}

          {/* 拖动它来分配对话区与预览区的宽度，双击复位 */}
          {splitterVisible && (
            <div
              className="pane-splitter"
              role="separator"
              aria-orientation="vertical"
              aria-label="调整预览区宽度"
              aria-valuenow={previewWidth}
              aria-valuemin={PREVIEW_MIN_W}
              tabIndex={0}
              onPointerDown={handleSplitterDown}
              onPointerMove={handleSplitterMove}
              onPointerUp={handleSplitterUp}
              onPointerCancel={handleSplitterUp}
              onDoubleClick={resetPreviewWidth}
              onKeyDown={handleSplitterKeyDown}
              title="拖动调整预览区宽度，双击复位"
            />
          )}

          <PreviewPanel
            html={state.preview.html}
            files={state.preview.files}
            versions={state.preview.versions}
            versionLabel={state.preview.versionLabel}
            quality={state.quality}
            rollingBack={rollingBack}
            collapsed={previewCollapsed}
            full={previewFull}
            onToggleCollapse={() => setPreviewCollapsed((v) => !v)}
            onToggleFull={() => setPreviewFull((v) => !v)}
            onRollback={handleRollback}
            onNotify={showToast}
          />
        </main>
      )}

      {toast && <Toast key={toast.id} text={toast.text} onDismiss={() => setToast(null)} />}
    </div>
  );
}
