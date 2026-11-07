"""HTTP 入口（W1）。路由刻意窄：提交、查、取消、取图、健康、指标。

契约要点（契约测试 `tests/test_gateway_pytest.py` 会锁死）：
- 提交成功 **202 + job_id**（不是 200，语义上是"已受理、未完成"）；
- 查任务返回**完整状态机**（QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELED）；
- 安全拦截 **400**，且不入队、不计费；
- 资产走内容寻址 URL（`/v1/assets/{sha256}.png`），可长期缓存。
"""

from __future__ import annotations

import os
from pathlib import Path

from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.responses import JSONResponse

from .backends import Backend, ComfyBackend, MockBackend
from .jobs import JobManager
from .models import ImageRequest, Job, SubmitResponse
from .safety import SafetyRejection
from .store import ArtifactStore

DEFAULT_STORE = os.environ.get("GV_STORE_DIR", str(Path(__file__).resolve().parent.parent / "store"))


def default_backend() -> Backend:
    kind = os.environ.get("GV_BACKEND", "mock").lower()
    if kind == "comfy":
        base = os.environ.get("COMFY_URL", "http://127.0.0.1:8188")
        workflow = os.environ.get(
            "COMFY_WORKFLOW", str(Path(__file__).resolve().parent.parent / "workflows" / "sdxl_txt2img.json")
        )
        return ComfyBackend(base, workflow)
    return MockBackend(latency_s=float(os.environ.get("GV_MOCK_LATENCY_S", "0.3")))


def create_app(
    backend: Backend | None = None,
    store_root: str | None = None,
    max_concurrent: int = 1,
    poll_interval_s: float = 0.05,
    default_timeout_s: float = 300.0,
) -> FastAPI:
    app = FastAPI(title="Visual Capability Gateway", version="0.1.0")
    store = ArtifactStore(store_root or DEFAULT_STORE)
    manager = JobManager(
        backend or default_backend(),
        store,
        max_concurrent=max_concurrent,
        poll_interval_s=poll_interval_s,
        default_timeout_s=default_timeout_s,
    )
    app.state.manager = manager

    @app.exception_handler(SafetyRejection)
    async def _safety(_: Request, exc: SafetyRejection) -> JSONResponse:
        manager.counters["rejected_safety"] += 1
        return JSONResponse(
            status_code=400,
            content={"error": "prompt-rejected", "labels": exc.labels},
        )

    @app.post("/v1/images", status_code=202, response_model=SubmitResponse)
    async def submit(req: ImageRequest) -> SubmitResponse:
        job, cache_hit = await manager.submit(req)
        return SubmitResponse(
            job_id=job.job_id,
            status=job.status,
            cache_hit=cache_hit,
            links={
                "self": f"/v1/images/{job.job_id}",
                "cancel": f"/v1/images/{job.job_id}",
            },
        )

    @app.get("/v1/images/{job_id}", response_model=Job)
    async def get_job(job_id: str) -> Job:
        job = manager.get(job_id)
        if job is None:
            raise HTTPException(status_code=404, detail="job-not-found")
        return job

    @app.delete("/v1/images/{job_id}", status_code=202, response_model=Job)
    async def cancel_job(job_id: str) -> Job:
        job = manager.cancel(job_id)
        if job is None:
            raise HTTPException(status_code=404, detail="job-not-found")
        return job

    @app.get("/v1/assets/{asset_id}.png")
    async def get_asset(asset_id: str) -> Response:
        if not all(c in "0123456789abcdef" for c in asset_id.lower()) or len(asset_id) < 16:
            raise HTTPException(status_code=400, detail="bad-asset-id")
        data = store.get(asset_id)
        if data is None:
            raise HTTPException(status_code=404, detail="asset-not-found")
        return Response(
            content=data,
            media_type="image/png",
            headers={"Cache-Control": "public, max-age=31536000, immutable"},
        )

    @app.get("/healthz")
    async def healthz() -> dict:
        health = await manager.health()
        return health.model_dump()

    @app.get("/metrics")
    async def metrics() -> Response:
        return Response(content=manager.metrics_text(), media_type="text/plain; version=0.0.4")

    return app


app = create_app()