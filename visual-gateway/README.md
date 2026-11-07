# Visual Capability Gateway（视觉能力网关）

CodeCanvas 的「出图」能力不是直接调 ComfyUI，而是经过这一层。原因：
**ComfyUI 是有状态、单队列、分钟级的外部服务**，而 Agent 的工具调用是同步短超时。
直接对接会把两种时间尺度硬绑在一起，最后一定是超时、重试风暴和重复计费。

## 它解决什么

| 问题 | 做法 |
|---|---|
| 分钟级任务 vs 秒级工具调用 | `POST /v1/images` 立刻返回 **202 + job_id**，进度靠查 |
| 重复提交 = 重复花钱 | **内容哈希幂等**：同 prompt/参数收敛到同一 job；成功后再提交命中缓存 |
| 产物会丢（ComfyUI 的 /view 是临时文件） | **内容寻址落盘**：`asset_id = sha256(bytes)`，URL 永久可缓存 |
| 成本不可见 | 每个 job 记 `gpu_seconds` 与 `estimated_usd`，`/metrics` 汇总 |
| 用户改主意停不下来 | `DELETE` 真调后端 `/interrupt`，不是只打个标记 |
| 伪造/侵权提示词 | 前置 `screen()`，命中即 **400**，不入队不计费 |
| ComfyUI 挂了拖垮主链路 | 后端异常一律落成 `FAILED`；`/healthz` 报 degraded；客户端降级为「无配图」 |

## 接口

```
POST   /v1/images                202 {job_id, status, cache_hit, links}
GET    /v1/images/{job_id}       200 {job_id,status,progress,artifacts[],cost,...}
DELETE /v1/images/{job_id}       202 取消
GET    /v1/assets/{sha256}.png   200 image/png（immutable 缓存头）
GET    /healthz                  200 {status, backend, backend_healthy, inflight, queued}
GET    /metrics                  200 Prometheus 文本
```

请求体：

```json
{ "prompt": "海边星空下的动漫情侣", "aspect": "16:9", "role": "hero", "seed": 42, "idempotency_key": "op-123" }
```

## 跑起来

```bash
cd visual-gateway
pip install -r requirements.txt

# 1) 无 GPU 演示/测试：mock 后端
python -m uvicorn app.main:app --port 8089

# 2) 接真实 ComfyUI（Desktop 默认 8188）
GV_BACKEND=comfy COMFY_URL=http://127.0.0.1:8188 python -m uvicorn app.main:app --port 8089

# 测试
python -m pytest -q
```

环境变量：

| 变量 | 默认 | 说明 |
|---|---|---|
| `GV_BACKEND` | `mock` | `mock` / `comfy` |
| `COMFY_URL` | `http://127.0.0.1:8188` | ComfyUI 地址 |
| `COMFY_WORKFLOW` | `workflows/sdxl_txt2img.json` | API 格式 workflow 模板 |
| `GV_STORE_DIR` | `./store` | 产物目录 |
| `GV_GPU_USD_PER_SECOND` | `0.0001` | 计费单价（默认按本地电费量级，生产改云价） |
| `GV_MOCK_LATENCY_S` | `0.3` | mock 模拟耗时 |

## workflow 模板约定

`workflows/sdxl_txt2img.json` 是 **API 格式**（不是 UI 导出格式），节点 id 固定：

- `6` 正向 CLIP 文本 ← `prompt`
- `7` 负向 CLIP 文本 ← `negative_prompt`
- `5` EmptyLatentImage ← 按 `aspect` 映射宽高
- `3` KSampler ← `seed`

换模型只需换 `4` 的 `ckpt_name` 或整份模板，网关代码不动。

## 测试覆盖（28 例）

- **契约**：提交/查询响应字段集固定（改字段必须显式改测试）
- **语义**：幂等（含显式 key）、内容寻址去重、取消、超时、后端失败、**后端报成功但没有图 → FAILED**
- **安全**：X 光/病历/身份证/明星照片类提示词 400 且不占 GPU；正常请求不误伤
- **真后端报文**：`httpx.MockTransport` 断言 workflow 注入点、尺寸映射、`/view` 取图、`/interrupt`+`/queue` 取消
- **并发**：`max_concurrent=1` 时多任务串行且全部完成

## 明确的限制

1. **状态在内存里**：单进程演示够用，多副本部署需换 Redis/DB（接口已隔离在 `JobManager`）。
2. **真 ComfyUI 链路未在本机实测**（本机 Docker/ComfyUI 实例未起），只用 mock transport 验证了报文形状；
   真机跑通需要 SDXL 权重 + 8GB 显存，建议先跑 `mock` 打通调用链。
3. **质量不可自动评测**：这里只管工程指标（成功率、P95、成本），图像质量靠人工抽检。