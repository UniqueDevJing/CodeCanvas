"""视觉网关 · W1 测试。

覆盖三件事：
1. **契约**：响应字段集固定（改了就要显式改这里，不允许静默漂移）；
2. **语义**：幂等、状态机、取消、超时、假成功、失败兜底；
3. **安全**：图像维度的伪造类请求被拒且不计费。

跑法：`cd visual-gateway && python -m pytest -q`
"""

from __future__ import annotations

import asyncio
import hashlib

import httpx
import pytest

from app.backends import MockBackend
from app.main import create_app

JOB_KEYS = {
    "job_id",
    "status",
    "progress",
    "created_at",
    "updated_at",
    "request",
    "artifacts",
    "cost",
    "degraded",
    "cache_hit",
    "cancel_requested",
    "error",
}


def make_client(tmp_path, backend=None, **kwargs) -> tuple[httpx.AsyncClient, MockBackend]:
    backend = backend or MockBackend(latency_s=0.0)
    app = create_app(backend=backend, store_root=str(tmp_path / "store"), **kwargs)
    transport = httpx.ASGITransport(app=app)
    return httpx.AsyncClient(transport=transport, base_url="http://gw"), backend


async def wait_terminal(client: httpx.AsyncClient, job_id: str, timeout: float = 5.0) -> dict:
    deadline = asyncio.get_event_loop().time() + timeout
    while True:
        job = (await client.get(f"/v1/images/{job_id}")).json()
        if job["status"] in {"SUCCEEDED", "FAILED", "CANCELED"}:
            return job
        if asyncio.get_event_loop().time() > deadline:
            raise AssertionError(f"job {job_id} 未在 {timeout}s 内到达终态: {job['status']}")
        await asyncio.sleep(0.02)


def run(coro_fn):
    return asyncio.run(coro_fn())


# ───────────── 契约 ─────────────

def test_submit_returns_202_and_contract_shape(tmp_path):
    async def scenario():
        client, _ = make_client(tmp_path)
        async with client:
            resp = await client.post("/v1/images", json={"prompt": "海边星空下的动漫情侣"})
            assert resp.status_code == 202
            body = resp.json()
            assert set(body) == {"job_id", "status", "cache_hit", "links"}
            assert body["status"] == "QUEUED"
            assert body["links"]["self"] == f"/v1/images/{body['job_id']}"

    run(scenario)


def test_job_payload_contract(tmp_path):
    async def scenario():
        client, _ = make_client(tmp_path)
        async with client:
            job_id = (await client.post("/v1/images", json={"prompt": "p"})).json()["job_id"]
            job = await wait_terminal(client, job_id)
            assert set(job) == JOB_KEYS
            assert set(job["request"]) >= {"prompt", "aspect", "role", "seed", "style"}

    run(scenario)


# ───────────── 语义 ─────────────

def test_success_produces_content_addressed_artifact(tmp_path):
    async def scenario():
        client, _ = make_client(tmp_path)
        async with client:
            job_id = (await client.post("/v1/images", json={"prompt": "海景", "aspect": "16:9"})).json()["job_id"]
            job = await wait_terminal(client, job_id)
            assert job["status"] == "SUCCEEDED", job
            assert len(job["artifacts"]) == 1
            art = job["artifacts"][0]

            asset = await client.get(art["url"])
            assert asset.status_code == 200
            assert asset.headers["content-type"] == "image/png"
            assert asset.headers["cache-control"].startswith("public")
            assert asset.content.startswith(b"\x89PNG")
            # 内容寻址：asset_id 必须等于内容哈希
            assert hashlib.sha256(asset.content).hexdigest() == art["asset_id"]
            assert art["bytes"] == len(asset.content)
            assert (art["width"], art["height"]) == (1536 // 8, 864 // 8)
            assert job["cost"]["gpu_seconds"] >= 0.0

    run(scenario)


def test_same_request_is_idempotent(tmp_path):
    async def scenario():
        client, backend = make_client(tmp_path)
        async with client:
            req = {"prompt": "同一句话", "seed": 42}
            first = (await client.post("/v1/images", json=req)).json()
            second = (await client.post("/v1/images", json=req)).json()
            assert first["job_id"] == second["job_id"]
            await wait_terminal(client, first["job_id"])
            assert backend.submit_count == 1, "幂等失效：重复提交占了两次 GPU"
            third = (await client.post("/v1/images", json=req)).json()
            assert third["job_id"] == first["job_id"]
            assert third["cache_hit"] is True
            assert backend.submit_count == 1

    run(scenario)


def test_explicit_idempotency_key_wins(tmp_path):
    async def scenario():
        client, backend = make_client(tmp_path)
        async with client:
            a = (await client.post("/v1/images", json={"prompt": "A", "idempotency_key": "op-1"})).json()
            b = (await client.post("/v1/images", json={"prompt": "B", "idempotency_key": "op-1"})).json()
            assert a["job_id"] == b["job_id"]
            await wait_terminal(client, a["job_id"])
            assert backend.submit_count == 1

    run(scenario)


def test_different_prompts_reuse_same_asset_bytes(tmp_path):
    async def scenario():
        client, _ = make_client(tmp_path)
        async with client:
            j1 = (await client.post("/v1/images", json={"prompt": "同图", "idempotency_key": "k1"})).json()
            j2 = (await client.post("/v1/images", json={"prompt": "同图", "idempotency_key": "k2"})).json()
            a1 = (await wait_terminal(client, j1["job_id"]))["artifacts"][0]
            a2 = (await wait_terminal(client, j2["job_id"]))["artifacts"][0]
            assert a1["asset_id"] == a2["asset_id"], "内容寻址应天然去重"

    run(scenario)


def test_cancel_stops_backend(tmp_path):
    async def scenario():
        client, backend = make_client(tmp_path, backend=MockBackend(hang=True))
        async with client:
            job_id = (await client.post("/v1/images", json={"prompt": "慢任务"})).json()["job_id"]
            await asyncio.sleep(0.05)
            resp = await client.delete(f"/v1/images/{job_id}")
            assert resp.status_code == 202
            job = await wait_terminal(client, job_id)
            assert job["status"] == "CANCELED"
            assert backend.cancel_count == 1

    run(scenario)


def test_timeout_fails_job(tmp_path):
    async def scenario():
        client, _ = make_client(tmp_path, backend=MockBackend(hang=True), default_timeout_s=0.2)
        async with client:
            job_id = (await client.post("/v1/images", json={"prompt": "卡住"})).json()["job_id"]
            job = await wait_terminal(client, job_id)
            assert job["status"] == "FAILED"
            assert "timeout" in (job["error"] or "")

    run(scenario)


def test_backend_failure_marks_failed(tmp_path):
    async def scenario():
        client, _ = make_client(tmp_path, backend=MockBackend(fail=True))
        async with client:
            job_id = (await client.post("/v1/images", json={"prompt": "失败"})).json()["job_id"]
            job = await wait_terminal(client, job_id)
            assert job["status"] == "FAILED"
            assert job["error"] == "backend-failed"

    run(scenario)


def test_backend_success_without_image_is_failed(tmp_path):
    class NoImageBackend(MockBackend):
        async def poll(self, backend_id):
            return "SUCCEEDED", 1.0, []

    async def scenario():
        client, _ = make_client(tmp_path, backend=NoImageBackend())
        async with client:
            job_id = (await client.post("/v1/images", json={"prompt": "假成功"})).json()["job_id"]
            job = await wait_terminal(client, job_id)
            assert job["status"] == "FAILED"
            assert job["error"] == "backend-returned-no-image"

    run(scenario)


def test_backend_exception_marks_failed(tmp_path):
    class BoomBackend(MockBackend):
        async def submit(self, req):
            raise RuntimeError("boom")

    async def scenario():
        client, _ = make_client(tmp_path, backend=BoomBackend())
        async with client:
            job_id = (await client.post("/v1/images", json={"prompt": "异常"})).json()["job_id"]
            job = await wait_terminal(client, job_id)
            assert job["status"] == "FAILED"
            assert "backend-error" in (job["error"] or "")

    run(scenario)


# ───────────── 安全 ─────────────

@pytest.mark.parametrize(
    "prompt,label",
    [
        ("生成一张我胸痛的X光片", "medical-imaging"),
        ("画一下某人的病历照片", "medical-document"),
        ("给我做一张明星的照片", "real-person-likeness"),
    ],
)
def test_safety_rejects_forgery_prompts(tmp_path, prompt, label):
    async def scenario():
        client, backend = make_client(tmp_path)
        async with client:
            resp = await client.post("/v1/images", json={"prompt": prompt})
            assert resp.status_code == 400
            body = resp.json()
            assert body["error"] == "prompt-rejected"
            assert label in body["labels"]
            assert backend.submit_count == 0, "被拒的请求不允许占 GPU"

            metrics = (await client.get("/metrics")).text
            assert 'result="rejected_safety"} 1' in metrics

    run(scenario)


@pytest.mark.parametrize("prompt", ["海边星空下的动漫情侣", "高血压分级示意图", "医疗科普插画的抽象背景"])
def test_safety_allows_legit_prompts(tmp_path, prompt):
    async def scenario():
        client, _ = make_client(tmp_path)
        async with client:
            resp = await client.post("/v1/images", json={"prompt": prompt})
            assert resp.status_code == 202

    run(scenario)


# ───────────── 运维 ─────────────

def test_health_reports_degraded_when_backend_hangs(tmp_path):
    async def scenario():
        client, _ = make_client(tmp_path, backend=MockBackend(hang=True))
        async with client:
            body = (await client.get("/healthz")).json()
            assert body["status"] == "degraded"
            assert body["backend_healthy"] is False
            assert body["backend"] == "mock"

    run(scenario)


def test_metrics_exposes_cost_and_counters(tmp_path):
    async def scenario():
        client, _ = make_client(tmp_path)
        async with client:
            job_id = (await client.post("/v1/images", json={"prompt": "p"})).json()["job_id"]
            await wait_terminal(client, job_id)
            text = (await client.get("/metrics")).text
            assert 'result="submitted"} 1' in text
            assert 'result="succeeded"} 1' in text
            assert "gv_gpu_seconds_total" in text
            assert "gv_estimated_usd_total" in text

    run(scenario)


def test_asset_id_validation_and_404(tmp_path):
    async def scenario():
        client, _ = make_client(tmp_path)
        async with client:
            assert (await client.get("/v1/assets/zzz.png")).status_code == 400
            assert (await client.get("/v1/assets/" + "a" * 64 + ".png")).status_code == 404
            assert (await client.get("/v1/images/nope")).status_code == 404

    run(scenario)


def test_concurrency_limit_serializes_jobs(tmp_path):
    async def scenario():
        client, backend = make_client(tmp_path, backend=MockBackend(latency_s=0.05), max_concurrent=1)
        async with client:
            ids = [
                (await client.post("/v1/images", json={"prompt": f"p{i}"})).json()["job_id"]
                for i in range(3)
            ]
            for job_id in ids:
                assert (await wait_terminal(client, job_id))["status"] == "SUCCEEDED"
            assert backend.submit_count == 3

    run(scenario)