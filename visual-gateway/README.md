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
## 真机联调记录（W1.5 · 2026-11-04）

ComfyUI Desktop 实例 + 复用本机已有的 SD1.5 权重（免下载 4GB）：

```yaml
# <ComfyUI>/extra_model_paths.yaml
a1111:
  base_path: C:/Users/Dominion/stable-diffusion-webui/models
  checkpoints: Stable-diffusion
```

```bash
# 1) ComfyUI（Desktop 实例自带 venv：Python 3.13 + torch 2.12.1+cu130）
python main.py --port 8188 --listen 127.0.0.1
# 2) 网关接真后端
GV_BACKEND=comfy COMFY_URL=http://127.0.0.1:8188 \
COMFY_WORKFLOW=workflows/sd15_txt2img.json python -m uvicorn app.main:app --port 8089
```

实测（RTX 4060 Laptop 8GB，SD1.5，12 步，1024×1024）：

| 指标 | 实测值 |
|---|---|
| 端到端 | 提交 → 出图 → 落盘 → 取回，**18.7s**（GPU 时间） |
| 产物 | 1024×1024 PNG，**1.42 MB**，`asset_id` = 内容哈希 |
| 成本 | `gpu_seconds=18.748`，`estimated_usd=0.001875`（按 0.0001 USD/s 计） |
| 二次同请求 | 命中幂等缓存，**0 次 GPU 调用** |

### 真机才暴露的一个 bug（已修）

Java 的 `HttpClient` 对 `http://` 默认发起 **h2c 升级**（`Connection: Upgrade` + `HTTP2-Settings`），
uvicorn 不做 h2c，结果**请求体被丢弃**，网关回 `422 body missing`。
用 JDK `HttpServer` 搭的假网关测不出来（它忽略升级头），真机联调一眼看穿。
修法：`HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)`。

同时给客户端补了一条：非 202 时把网关返回的校验片段带进降级原因（截断 180 字符），
否则线上只能看到一个 422，排查靠猜。
