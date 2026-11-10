# CodeCanvas 离线评测基线（C2）

100 个确定性案例，不依赖 LLM API Key，全部可在本地/CI 离线执行。

## 一条命令出报告

```bash
cd backend
mvn test -Dtest=CodeCanvasEvalHarnessTest
```

报告落盘 `evals/reports/latest.md` 与 `latest-report.json`（含时间戳快照）。

## 数据集构成（100 案例）

| 文件 | 维度 | 案例 | 评测对象 |
|---|---|---|---|
| routing.json | 模型分级路由 | 30 | ModelRouter 打分规则（任务类型/长度/关键词/首轮/文件数 → 三档） |
| validator.json | HTML 校验器 | 30 | HtmlValidator.validate（结构/围栏/占位/标签配平/脚本/图片） |
| extractor.json | 代码提取 | 10 | CodeExtractor.extractHtml（围栏剥离/寒暄截断/收尾清理） |
| tokens.json | Token 估算 | 5 | TokenEstimator（CJK=1、其他=1/4 向上取整） |
| acceptance.json | 验收断言 | 15 | AcceptanceSpec.parse + violations（字段契约/上限截断/降级） |
| budget.json | 预算调度 | 10 | BudgetPolicy.decide（档位降级/修复轮次分配/超预算关闭） |

预算案例定价口径（每千 token 元）：旗舰 0.004/0.016，标准 0.001/0.004，轻量 0.0005/0.002。

## 重构门禁

任何编排层重构（如 C1 Orchestrator 拆分）后重跑本 harness：

- 通过率降幅 <= 2pp 视为行为保持（方案 v0.9 门禁）
- 首轮基线：100/100（见 reports/latest-report.json）
