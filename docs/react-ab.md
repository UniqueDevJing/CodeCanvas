# C5：真 ReAct 分支 —— 与固定流水线的 A/B 对照

## 为什么要有这一条

CodeCanvas 原本的五阶段（澄清 → 规划 → 生成 → 校验 → 修复）是一条**固定流水线**：
每一步做什么、调用什么工具，都是编排代码写死的。这个设计稳定，但缺一个关键证据——
"模型自己决定调用什么工具"的能力（面试里讲 ReAct 时的第一追问）。

C5 在同一套工具与校验设施之上，加了一条**模型自主的 ReAct 循环**：

```
Thought: 先写页面
Action: write_file
Action Input: {"path":"index.html","content":"<html>…完整 HTML…</html>"}
Observation: 已写入 index.html（2048 字符）
Thought: 检查一下
Action: validate_html
Action Input: {"path":"index.html"}
Observation: 校验通过
Thought: 完成
Action: finish
Action Input: {}
```

## 实现

| 组件 | 位置 | 说明 |
|---|---|---|
| `ReActParser` | `agent/react/` | 解析 Thought/Action/Action Input；缺任一行判 invalid 并回喂纠正 |
| `ReActAgent` | `agent/react/` | 循环本体（纯 Java）：对模型的调用经 `Invoker` 函数注入，编排层接 LlmGateway |
| `JsonMini` | `agent/react/` | Action Input 的零依赖 JSON 解析（一层对象），让循环逻辑可进 no-Maven harness |
| 编排接入 | `AgentOrchestrator.doRun` | 请求 `strategy=react` 时走自主循环；产不出页面自动退回流水线兜底 |
| 请求入口 | `ChatRequestDto.strategy` | `"react"` 即切换；缺省 pipeline。前端可不改 |

**三重终止条件**（防失控）：
1. `codecanvas.react.max-steps`（默认 10）步数上限；
2. 预算耗尽（复用 C2 的 `BudgetPolicy.exceeded`，每步检查）；
3. 校验通过：模型发 `finish` 时，`HtmlValidator` 全绿才真正收尾；不通过会把具体问题作为
   Observation 回喂，让它继续修。

**兜底**：循环结束但工作区没有产出页面 → 自动退回固定流水线重新生成，交付不受影响。

## A/B 对照方法

同一需求、同一模型路由、同一预算约束，分别以 `strategy=pipeline` 与 `strategy=react`
各跑一次（C3 的执行轨迹会记录 `strategy` 字段，`/api/traces` 可直接取数）：

```bash
# pipeline 基线
curl -N -X POST /api/chat/stream -d '{"message":"做一个番茄钟页面","strategy":"pipeline"}'
# react 对照
curl -N -X POST /api/chat/stream -d '{"message":"做一个番茄钟页面","strategy":"react"}'
```

对比维度（即验收指标）：

| 指标 | pipeline 基线 | react 对照 | 取数位置 |
|---|---|---|---|
| 首轮通过率（静态校验 + 验收断言全绿） | | | `RunTrace.ok` / 质检报告 |
| 修复次数 | | | `RunTrace.healAttempts` |
| 总成本（¥）与反事实节省 | | | `RunTrace.cost / baselineCost` |
| 总延迟（s） | | | `RunTrace.durationMs` |
| 工具调用数 | | | `RunTrace.toolCalls` |
| 轨迹可读性（排查耗时） | | | `/api/traces/{runId}` 回放 |

> 实测数据需在部署环境（配置了真实模型密钥）跑若干组后填入；沙箱内仅验证了循环机制
> 本身（脚本化假模型 harness 17/17），不虚构数字。

## 已知取舍（诚实边界）

- ReAct 用的是**文本协议**而不是 function calling：网关同时支持 OpenAI/Anthropic 两种协议，
  文本协议对两者都零改动，也天然规避了部分厂商工具调用兼容性问题；代价是格式错误要靠
  纠正观察兜底（harness 场景 C/D 覆盖了这条路径）。
- ReAct 轮内不逐 token 流式（`call` 而非 `callStreaming`）：自主循环的中间轮次本来就不是
  给用户看正文，工具事件经监听器实时透出；最终页面仍走统一的校验/预览/落版本链路。
- "看截图"工具暂未纳入：运行时截图依赖 Playwright 环境（C1 的 `verify_runtime`），
  当前以 `validate_html` 静态校验作为 finish 判据，运行时验证保持旁路不阻断。
