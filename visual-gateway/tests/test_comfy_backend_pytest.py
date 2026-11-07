"""真实 ComfyUI 后端的请求形状测试（不需要 GPU、不需要起 ComfyUI）。

用 `httpx.MockTransport` 断言我们**发给 ComfyUI 的报文长什么样**：
workflow 注入位置、尺寸映射、history 解析、/view 取图、取消时打到 /interrupt 与 /queue。
这层的价值：真机上出问题时，能立刻区分"网关发错了"还是"ComfyUI 那边的问题"。
"""

from __future__ import annotations

import asyncio
import json
import struct
import zlib

import httpx

from app.backends import ComfyBackend, png_size
from app.models import ImageRequest

WORKFLOW = {
    "3": {"class_type": "KSampler", "inputs": {"seed": 0, "steps": 20}},
    "5": {"class_type": "EmptyLatentImage", "inputs": {"width": 1024, "height": 1024}},
    "6": {"class_type": "CLIPTextEncode", "inputs": {"text": ""}},
    "7": {"class_type": "CLIPTextEncode", "inputs": {"text": ""}},
    "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "gw"}},
}


def tiny_png(width: int = 16, height: int = 16) -> bytes:
    raw = b"".join(b"\x00" + bytes([10, 20, 30]) * width for _ in range(height))

    def chunk(tag: bytes, data: bytes) -> bytes:
        return struct.pack(">I", len(data)) + tag + data + struct.pack(
            ">I", zlib.crc32(tag + data) & 0xFFFFFFFF
        )

    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(raw, 6))
        + chunk(b"IEND", b"")
    )


def backend_with(tmp_path, handler) -> ComfyBackend:
    wf = tmp_path / "wf.json"
    wf.write_text(json.dumps(WORKFLOW), encoding="utf-8")
    return ComfyBackend(
        "http://comfy.test:8188",
        str(wf),
        transport=httpx.MockTransport(handler),
    )


def test_submit_injects_prompt_size_and_seed(tmp_path):
    captured: dict = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured["path"] = request.url.path
        captured["body"] = json.loads(request.content)
        return httpx.Response(200, json={"prompt_id": "pid-1", "number": 3})

    backend = backend_with(tmp_path, handler)
    bid = asyncio.run(backend.submit(ImageRequest(prompt="星空情侣", aspect="9:16", seed=7)))

    assert bid == "pid-1"
    assert captured["path"] == "/prompt"
    wf = captured["body"]["prompt"]
    assert wf["6"]["inputs"]["text"] == "星空情侣"
    assert wf["5"]["inputs"]["width"] == 864 and wf["5"]["inputs"]["height"] == 1536
    assert wf["3"]["inputs"]["seed"] == 7
    assert captured["body"]["client_id"] == "visual-gateway"


def test_poll_returns_running_until_history_has_entry(tmp_path):
    calls = {"n": 0}

    def handler(request: httpx.Request) -> httpx.Response:
        calls["n"] += 1
        if request.url.path == "/history/pid-1":
            return httpx.Response(200, json={})
        return httpx.Response(404)

    backend = backend_with(tmp_path, handler)
    state, progress, images = asyncio.run(backend.poll("pid-1"))
    assert (state, images) == ("RUNNING", None)
    assert 0 <= progress <= 1


def test_poll_fetches_images_from_view(tmp_path):
    png = tiny_png()

    def handler(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/history/pid-1":
            return httpx.Response(
                200,
                json={
                    "pid-1": {
                        "status": {"status_str": "success", "completed": True},
                        "outputs": {
                            "9": {"images": [{"filename": "gw_0001.png", "subfolder": "", "type": "output"}]}
                        },
                    }
                },
            )
        if request.url.path == "/view":
            assert request.url.params["filename"] == "gw_0001.png"
            return httpx.Response(200, content=png, headers={"content-type": "image/png"})
        return httpx.Response(404)

    backend = backend_with(tmp_path, handler)
    state, progress, images = asyncio.run(backend.poll("pid-1"))
    assert state == "SUCCEEDED" and progress == 1.0
    assert images and images[0] == png
    assert png_size(images[0]) == (16, 16)


def test_poll_maps_failed_status(tmp_path):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200, json={"pid-1": {"status": {"status_str": "error", "completed": True}, "outputs": {}}}
        )

    backend = backend_with(tmp_path, handler)
    state, _, images = asyncio.run(backend.poll("pid-1"))
    assert (state, images) == ("FAILED", None)


def test_cancel_hits_interrupt_and_queue(tmp_path):
    seen: list[tuple[str, bytes]] = []

    def handler(request: httpx.Request) -> httpx.Response:
        seen.append((request.url.path, request.content))
        return httpx.Response(200, json={})

    backend = backend_with(tmp_path, handler)
    asyncio.run(backend.cancel("pid-1"))
    paths = [p for p, _ in seen]
    assert "/interrupt" in paths and "/queue" in paths
    queue_body = json.loads(dict(seen)["/queue"])
    assert queue_body["delete"] == ["pid-1"]


def test_healthy_checks_system_stats(tmp_path):
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200 if request.url.path == "/system_stats" else 500, json={})

    backend = backend_with(tmp_path, handler)
    assert asyncio.run(backend.healthy()) is True


def test_healthy_false_when_unreachable(tmp_path):
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("no route")

    backend = backend_with(tmp_path, handler)
    assert asyncio.run(backend.healthy()) is False