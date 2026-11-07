"""任务管理：幂等键、状态机、超时、取消、成本、指标。

这一层是整个网关最难替代的部分，也是最值得在面试里展开的部分：

1. **幂等**：图像生成是有副作用的（占 GPU、花钱）。同一请求重复提交必须收敛到同一个 job；
   键默认由内容哈希得出，调用方也可以显式传 `idempotency_key`（用于"重试同一次用户操作"）。
2. **异步**：ComfyUI 是分钟级单队列，Agent 工具调用是小超时。所以对外只暴露
   `submit → job_id`，进度用轮询（生产可换 SSE/WebSocket），状态机单向推进。
3. **可取消**：用户改主意就要能真的停 GPU，不只是标记位。
4. **可计价**：GPU 秒数是成本的第一性指标，必须逐 job 记录。
"""

from __future__ import annotations

import asyncio
import hashlib
import os
import time
import uuid
from datetime import datetime, timezone

from .backends import Backend
from .models import Artifact, Cost, Health, ImageRequest, Job, JobStatus, TERMINAL
from .safety import screen
from .store import ArtifactStore


def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def idempotency_key(req: ImageRequest) -> str:
    if req.idempotency_key:
        return req.idempotency_key
    canonical = "|".join(
        [req.prompt, req.negative_prompt, req.aspect, str(req.seed), req.style, req.role]
    )
    return hashlib.sha256(canonical.encode()).hexdigest()


class JobManager:
    def __init__(
        self,
        backend: Backend,
        store: ArtifactStore,
        max_concurrent: int = 1,
        default_timeout_s: float = 300.0,
        poll_interval_s: float = 0.2,
        gpu_usd_per_second: float | None = None,
    ) -> None:
        self.backend = backend
        self.store = store
        self.max_concurrent = max_concurrent
        self.default_timeout_s = default_timeout_s
        self.poll_interval_s = poll_interval_s
        self.gpu_usd_per_second = (
            gpu_usd_per_second
            if gpu_usd_per_second is not None
            else float(os.environ.get("GV_GPU_USD_PER_SECOND", "0.0001"))
        )
        self._jobs: dict[str, Job] = {}
        self._by_key: dict[str, str] = {}
        self._sem = asyncio.Semaphore(max_concurrent)
        self._tasks: set[asyncio.Task] = set()
        self._backend_ids: dict[str, str] = {}
        self.counters = {
            "submitted": 0,
            "cache_hits": 0,
            "succeeded": 0,
            "failed": 0,
            "canceled": 0,
            "rejected_safety": 0,
            "gpu_seconds": 0.0,
            "estimated_usd": 0.0,
        }

    # ── 对外 API ────────────────────────────────────────────────

    async def submit(self, req: ImageRequest) -> tuple[Job, bool]:
        screen(req.prompt)  # 命中直接抛 SafetyRejection（=> 400，不入队不计费）
        key = idempotency_key(req)
        existing_id = self._by_key.get(key)
        if existing_id:
            job = self._jobs[existing_id]
            if job.status not in TERMINAL:
                return job, False  # 在途：同一个 job，不重复占 GPU
            if job.status == JobStatus.SUCCEEDED:
                job.cache_hit = True
                self.counters["cache_hits"] += 1
                return job, True
            # FAILED / CANCELED 允许重试：新建 job，覆盖键映射
        job = Job(
            job_id=uuid.uuid4().hex,
            status=JobStatus.QUEUED,
            created_at=_now(),
            updated_at=_now(),
            request=req,
        )
        self._jobs[job.job_id] = job
        self._by_key[key] = job.job_id
        self.counters["submitted"] += 1
        task = asyncio.create_task(self._run(job))
        self._tasks.add(task)
        task.add_done_callback(self._tasks.discard)
        return job, False

    def get(self, job_id: str) -> Job | None:
        return self._jobs.get(job_id)

    def cancel(self, job_id: str) -> Job | None:
        job = self._jobs.get(job_id)
        if job is None:
            return None
        if job.status in TERMINAL:
            return job
        job.cancel_requested = True
        job.updated_at = _now()
        return job

    async def health(self) -> Health:
        inflight = sum(1 for j in self._jobs.values() if j.status == JobStatus.RUNNING)
        queued = sum(1 for j in self._jobs.values() if j.status == JobStatus.QUEUED)
        try:
            ok = await asyncio.wait_for(self.backend.healthy(), timeout=3.0)
        except (asyncio.TimeoutError, Exception):  # noqa: BLE001 - 健康检查永不抛
            ok = False
        return Health(
            status="ok" if ok else "degraded",
            backend=self.backend.name,
            backend_healthy=bool(ok),
            inflight=inflight,
            queued=queued,
        )

    def metrics_text(self) -> str:
        lines = [
            "# HELP gv_jobs_total 按终态统计的任务数",
            "# TYPE gv_jobs_total counter",
        ]
        for name in ("submitted", "cache_hits", "succeeded", "failed", "canceled", "rejected_safety"):
            lines.append(f'gv_jobs_total{{result="{name}"}} {self.counters[name]}')
        lines += [
            "# HELP gv_gpu_seconds_total 累计 GPU 秒数（成本第一性指标）",
            "# TYPE gv_gpu_seconds_total counter",
            f"gv_gpu_seconds_total {self.counters['gpu_seconds']:.3f}",
            "# HELP gv_estimated_usd_total 累计估算成本（美元）",
            "# TYPE gv_estimated_usd_total counter",
            f"gv_estimated_usd_total {self.counters['estimated_usd']:.6f}",
        ]
        return "\n".join(lines) + "\n"

    # ── 内部执行 ────────────────────────────────────────────────

    async def _run(self, job: Job) -> None:
        async with self._sem:
            if job.cancel_requested:
                self._finish(job, JobStatus.CANCELED)
                return
            started = time.monotonic()
            timeout = job.request.timeout_s or self.default_timeout_s
            try:
                backend_id = await self.backend.submit(job.request)
                self._backend_ids[job.job_id] = backend_id
                self._set(job, JobStatus.RUNNING, progress=0.05)
                while True:
                    if job.cancel_requested:
                        await self.backend.cancel(backend_id)
                        self.counters["gpu_seconds"] += time.monotonic() - started
                        self._finish(job, JobStatus.CANCELED)
                        return
                    if time.monotonic() - started > timeout:
                        await self.backend.cancel(backend_id)
                        self._finish(job, JobStatus.FAILED, error=f"timeout after {timeout:.0f}s")
                        return
                    state, progress, images = await self.backend.poll(backend_id)
                    if state == "SUCCEEDED":
                        self._complete(job, images or [], time.monotonic() - started)
                        return
                    if state == "FAILED":
                        self._finish(job, JobStatus.FAILED, error="backend-failed")
                        return
                    self._set(job, JobStatus.RUNNING, progress=max(0.05, min(0.95, progress)))
                    await asyncio.sleep(self.poll_interval_s)
            except asyncio.CancelledError:
                raise
            except Exception as exc:  # noqa: BLE001 - 任何后端异常都必须落成 FAILED，不能悬空
                self._finish(job, JobStatus.FAILED, error=f"backend-error: {type(exc).__name__}")

    def _complete(self, job: Job, images: list[bytes], elapsed: float) -> None:
        artifacts: list[Artifact] = []
        for data in images:
            asset_id, path = self.store.put(data)
            from .backends import png_size

            width, height = png_size(data)
            artifacts.append(
                Artifact(
                    asset_id=asset_id,
                    url=self.store.url(asset_id),
                    width=width,
                    height=height,
                    bytes=path.stat().st_size,
                )
            )
        job.artifacts = artifacts
        cost = Cost(
            gpu_seconds=round(elapsed, 3),
            estimated_usd=round(elapsed * self.gpu_usd_per_second, 6),
        )
        job.cost = cost
        self.counters["gpu_seconds"] += elapsed
        self.counters["estimated_usd"] += cost.estimated_usd
        if artifacts:
            self._finish(job, JobStatus.SUCCEEDED)
        else:
            # 后端报成功但一张图都没有 —— 这是"假成功"，必须显式失败
            self._finish(job, JobStatus.FAILED, error="backend-returned-no-image")

    def _finish(self, job: Job, status: JobStatus, error: str | None = None) -> None:
        self.counters[{JobStatus.SUCCEEDED: "succeeded", JobStatus.FAILED: "failed",
                       JobStatus.CANCELED: "canceled"}.get(status, "failed")] += 1
        self._set(job, status, progress=1.0 if status == JobStatus.SUCCEEDED else job.progress,
                  error=error)

    def _set(self, job: Job, status: JobStatus, progress: float | None = None,
             error: str | None = None) -> None:
        job.status = status
        if progress is not None:
            job.progress = progress
        if error is not None:
            job.error = error
        job.updated_at = _now()