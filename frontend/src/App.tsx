import { useCallback, useEffect, useReducer, useRef, useState } from 'react';
import { ChatPanel } from './components/ChatPanel';
import { DeployDialog } from './components/DeployDialog';
import { GalleryView } from './components/GalleryView';
import { PreviewPanel } from './components/PreviewPanel';
import { RuntimeErrorBanner } from './components/RuntimeErrorBanner';
import type { RuntimeBannerState } from './components/RuntimeErrorBanner';
import { Toast } from './components/Toast';
import { TopBar } from './components/TopBar';
import { activateVersion, buildShareUrl, deployProject } from './api/projects';
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
  const [previewCollapsed, setPreviewCollapsed] = useState(false);

  const stateRef = useRef(state);
  const toastSeqRef = useRef(0);

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
    showToast('已清空会话，说说你想要的新应用吧');
  }, [showToast]);

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

  /* ---------------------------------- 渲染 --------------------------------- */

  return (
    <div className="app">
      <TopBar
        projectName={state.projectName}
        onRename={handleRename}
        onNewProject={handleNewProject}
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

      {galleryOpen ? (
        <GalleryView open={galleryOpen} onClose={() => setGalleryOpen(false)} />
      ) : (
        <main className="app-main">
          <ChatPanel
            messages={state.messages}
            streaming={state.streaming}
            onSend={handleSend}
            onStop={handleStop}
          />
          <PreviewPanel
            html={state.preview.html}
            files={state.preview.files}
            versions={state.preview.versions}
            versionLabel={state.preview.versionLabel}
            quality={state.quality}
            rollingBack={rollingBack}
            collapsed={previewCollapsed}
            onToggleCollapse={() => setPreviewCollapsed((v) => !v)}
            onRollback={handleRollback}
            onNotify={showToast}
          />
        </main>
      )}

      {toast && <Toast key={toast.id} text={toast.text} onDismiss={() => setToast(null)} />}
    </div>
  );
}
