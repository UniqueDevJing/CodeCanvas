"""视觉能力网关 · 数据契约（W1）。

设计原则：**字段集就是对外契约**。改字段先改 `tests/test_gateway_pytest.py` 里的快照期望，
再改实现——沿用文本侧契约测试的同一条纪律。
"""

from __future__ import annotations

from enum import Enum
from typing import Literal

from pydantic import BaseModel, Field

Aspect = Literal["1:1", "16:9", "9:16", "4:3", "3:4"]
Role = Literal["hero", "icon", "illustration", "og", "placeholder"]

ASPECT_SIZES: dict[str, tuple[int, int]] = {
    "1:1": (1024, 1024),
    "16:9": (1536, 864),
    "9:16": (864, 1536),
    "4:3": (1280, 960),
    "3:4": (960, 1280),
}


class JobStatus(str, Enum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    CANCELED = "CANCELED"


TERMINAL = {JobStatus.SUCCEEDED, JobStatus.FAILED, JobStatus.CANCELED}


class ImageRequest(BaseModel):
    prompt: str = Field(min_length=1, max_length=2000)
    negative_prompt: str = Field(default="", max_length=1000)
    aspect: Aspect = "16:9"
    seed: int | None = Field(default=None, ge=0, le=2**32 - 1)
    style: str = Field(default="default", max_length=64)
    role: Role = "illustration"
    # 不传则按内容哈希自动生成：同 prompt+参数 → 同 job，天然去重
    idempotency_key: str | None = Field(default=None, max_length=128)
    timeout_s: float | None = Field(default=None, gt=0, le=600)


class Artifact(BaseModel):
    asset_id: str
    url: str
    width: int
    height: int
    bytes: int
    content_type: str = "image/png"


class Cost(BaseModel):
    gpu_seconds: float
    estimated_usd: float


class Job(BaseModel):
    job_id: str
    status: JobStatus
    progress: float = 0.0
    created_at: str
    updated_at: str
    request: ImageRequest
    artifacts: list[Artifact] = Field(default_factory=list)
    cost: Cost | None = None
    degraded: bool = False
    cache_hit: bool = False
    cancel_requested: bool = False
    error: str | None = None


class Health(BaseModel):
    status: Literal["ok", "degraded"]
    backend: str
    backend_healthy: bool
    inflight: int
    queued: int


class SubmitResponse(BaseModel):
    job_id: str
    status: JobStatus
    cache_hit: bool
    links: dict[str, str]