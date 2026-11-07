"""后端适配层：把"生成一张图"抽象成三个动作（submit / poll / cancel）。

为什么要抽这一层：ComfyUI 只是**开发期后端**。同一份网关要能切到云 GPU（RunPod / 自建
集群 / 商业 API），切换成本必须落在"换一个 adapter"，而不是改业务侧。这条也是面试可讲的点：
**能力依赖抽象，不依赖具体实现**。
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import struct
import time
import zlib
from typing import Protocol

import httpx

from .models import ASPECT_SIZES, ImageRequest


def make_png(width: int, height: int, color: tuple[int, int, int]) -> bytes:
    """纯 Python 生成合法 PNG（不引 Pillow）：颜色由 prompt/seed 派生，便于断言内容不同。"""
    raw = b"".join(b"\x00" + bytes(color) * width for _ in range(height))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return (
            struct.pack(">I", len(data))
            + tag
            + data
            + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
        )

    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 6))
        + chunk(b"IEND", b"")
    )


def png_size(data: bytes) -> tuple[int, int]:
    """从 PNG 头读出宽高，用于校验"后端给的尺寸是否符合请求比例"。"""
    if not data.startswith(b"\x89PNG\r\n\x1a\n") or len(data) < 24:
        raise ValueError("not a png")
    width, height = struct.unpack(">II", data[16:24])
    return width, height


class Backend(Protocol):
    name: str

    async def submit(self, req: ImageRequest) -> str: ...
    async def poll(self, backend_id: str) -> tuple[str, float, list[bytes] | None]: ...
    async def cancel(self, backend_id: str) -> None: ...
    async def healthy(self) -> bool: ...


class MockBackend:
    """测试与本地演示用：确定性强、无 GPU、可注入失败与取消。"""

    name = "mock"

    def __init__(self, latency_s: float = 0.0, fail: bool = False, hang: bool = False) -> None:
        self.latency_s = latency_s
        self.fail = fail
        self.hang = hang
        self._jobs: dict[str, tuple[ImageRequest, float]] = {}
        self.submit_count = 0
        self.cancel_count = 0

    async def submit(self, req: ImageRequest) -> str:
        self.submit_count += 1
        bid = f"mock-{self.submit_count}"
        self._jobs[bid] = (req, time.monotonic())
        return bid

    async def poll(self, backend_id: str) -> tuple[str, float, list[bytes] | None]:
        req, started = self._jobs[backend_id]
        elapsed = time.monotonic() - started
        if self.hang:
            return "RUNNING", 0.1, None
        if self.latency_s and elapsed < self.latency_s:
            return "RUNNING", round(min(0.95, elapsed / self.latency_s), 3), None
        if self.fail:
            return "FAILED", 1.0, None
        width, height = ASPECT_SIZES[req.aspect]
        digest = hashlib.sha256(
            f"{req.prompt}|{req.negative_prompt}|{req.seed}|{req.aspect}|{req.style}".encode()
        ).digest()
        data = make_png(width // 8, height // 8, (digest[0], digest[1], digest[2]))
        return "SUCCEEDED", 1.0, [data]

    async def cancel(self, backend_id: str) -> None:
        self.cancel_count += 1

    async def healthy(self) -> bool:
        return not self.hang


class ComfyBackend:
    """真实 ComfyUI 后端（API 格式 workflow）。

    约定（写在 README，别靠猜）：workflow 模板里
      节点 `6` = 正向 CLIP 文本，`7` = 负向，`5` = EmptyLatentImage，`3` = KSampler(seed)。
    换模型/换模板只改模板文件，不改网关。
    """

    name = "comfyui"

    def __init__(
        self,
        base_url: str,
        workflow_path: str,
        timeout_s: float = 30.0,
        poll_interval_s: float = 0.5,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.workflow_path = workflow_path
        self.timeout_s = timeout_s
        self.poll_interval_s = poll_interval_s
        # 注入 transport 只用于测试（httpx.MockTransport），生产为 None
        self.transport = transport

    def _client(self) -> httpx.AsyncClient:
        return httpx.AsyncClient(timeout=self.timeout_s, transport=self.transport)

    def _workflow(self, req: ImageRequest) -> dict:
        template = json.loads(open(self.workflow_path, encoding="utf-8").read())
        width, height = ASPECT_SIZES[req.aspect]
        template["6"]["inputs"]["text"] = req.prompt
        if "7" in template:
            template["7"]["inputs"]["text"] = req.negative_prompt
        template["5"]["inputs"]["width"] = width
        template["5"]["inputs"]["height"] = height
        template["3"]["inputs"]["seed"] = req.seed if req.seed is not None else 0
        return template

    async def submit(self, req: ImageRequest) -> str:
        async with self._client() as client:
            resp = await client.post(
                f"{self.base_url}/prompt",
                json={"prompt": self._workflow(req), "client_id": "visual-gateway"},
            )
            resp.raise_for_status()
            return str(resp.json()["prompt_id"])

    async def poll(self, backend_id: str) -> tuple[str, float, list[bytes] | None]:
        async with self._client() as client:
            history = await client.get(f"{self.base_url}/history/{backend_id}")
            history.raise_for_status()
            entry = history.json().get(backend_id)
            if not entry:
                return "RUNNING", 0.5, None
            status = (entry.get("status") or {}).get("status_str", "success")
            if status != "success":
                return "FAILED", 1.0, None
            images: list[bytes] = []
            for node_out in (entry.get("outputs") or {}).values():
                for img in node_out.get("images", []):
                    view = await client.get(
                        f"{self.base_url}/view",
                        params={
                            "filename": img["filename"],
                            "subfolder": img.get("subfolder", ""),
                            "type": img.get("type", "output"),
                        },
                    )
                    view.raise_for_status()
                    images.append(view.content)
            return "SUCCEEDED", 1.0, images

    async def cancel(self, backend_id: str) -> None:
        async with self._client() as client:
            await client.post(f"{self.base_url}/interrupt")
            await client.post(f"{self.base_url}/queue", json={"delete": [backend_id]})

    async def healthy(self) -> bool:
        try:
            async with httpx.AsyncClient(timeout=3.0, transport=self.transport) as client:
                return (await client.get(f"{self.base_url}/system_stats")).status_code == 200
        except httpx.HTTPError:
            return False


async def sleep_poll(interval: float) -> None:
    await asyncio.sleep(interval)