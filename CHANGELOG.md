# Changelog

## v1.0-rc（2026-11-10）

工程化收敛版本：多文件项目、受限构建沙箱、运行时验证门禁、任务持久化、用户鉴权与额度、前端自动化回归、离线评测基线。后端 47 测试类 270 用例 0 失败（含 100 案例评测），前端 22 测试全绿。

### Added（P0/P1 工程化）
- **多文件项目模型**（C3）：ProjectPlan（≤8 文件规划优先，入口文件最后生成）· FileChangeSet（修改走精确变更集，不重写无关文件）· ProjectManifest（随版本落盘）；PreviewAssembler 内联相对引用打通预览/发布全链路；任何解析失败自动降级单文件流水线
- **受限构建沙箱**（C4）：每任务独立临时目录（路径逃逸拒绝）· 依赖白名单（拒绝 file:/git:/http 直链逃逸向量）· 敏感环境变量剥离 · 分阶段硬超时（异步 daemon 线程读流不阻塞 waitFor，超时 destroyForcibly）· 输出/文件数/磁盘配额
- **运行时验证三态门禁**（C5）：VERIFIED / NOT_VERIFIED / SKIPPED 写入质检报告——未验证绝不伪装成通过，报告如实展示原因
- **长任务持久化**（C6）：运行即落 RUNNING 快照，取消/失败显式终态（CANCELLED/FAILED/TERMINATED）；服务重启将遗留 RUNNING 改判 TERMINATED，不留虚假成功
- **用户鉴权与额度**（C7）：SessionAuthInterceptor（X-Session-Token 会话优先 + X-API-Key 共享口令兼容，fail-closed）· UserAuthService（登录 8h 会话，未配置诚实降级 503）· UserQuota（每用户每日次数 + 成本双上限，取消退还，成本按毫元整数累计防浮点漂移）
- **前端自动化测试**（C8）：vitest3 + RTL 22 项回归（conversationReducer 13 项含取消/回滚/发布/切项目、SSE 解析 6 项含中文多字节切分与 abort 透传、useAgentStream 3 项含单流约束与卸载中止）+ Playwright E2E 骨架（CODECANVAS_E2E=1 显式启用）
- **E2E 进 CI 实跑**：e2e job 本地验证通过后固化（后端离线 MOCK 引擎 → 前端 dev server → Chromium 1.65 → 生成/取消/回滚/发布全链路 5.0s），失败上传 Playwright trace；发布步骤按 UI 实况修正（直接结果对话框 + input value 断言，移除骨架期不存在的"确认"假设）；frontend job 补 vitest 22 项
- **依赖漏洞扫描进 CI**：dependency-scan job（OSS Index audit，CVSS≥7 门禁 + 匿名限流重试 3 次），本地实测 0 高危通过
- **离线评测基线**：100 个版本化生成/修改案例，0 模型调用一条命令复现（`mvn test -Dtest=CodeCanvasEvalHarnessTest`），报告落盘 `evals/reports/`；基线 100/100 通过，重构门禁通过率降幅 ≤2pp

### Changed（架构重构）
- **AgentOrchestrator 拆分**：767 行 → 薄编排器 206 行 + 流程组件群（GenerationFlow / MultiFileGeneration / DeliveryFlow / QualityGate / AcceptanceFlow / ImageBindingFlow / InputGuardrails），策略组件可独立测试与替换；重构经 100 案例评测门禁验证行为保持
- **前端依赖升级**：vitest3 + RTL + Playwright 接入，TS 构建通过

### Removed（C9 清理）
- 移除 git 跟踪的 `visual-gateway/**/__pycache__/*.pyc`（9 个文件），.gitignore 补 `__pycache__/` 与 `*.pyc` 规则——仓库不再含生成缓存
- README 与实现全面对齐：目录结构同步拆分后组件群、测试规模更新（29→270+22）、已知边界移除已解决项（单文件限制/无多用户隔离）、后续方向更新

## v0.9（2026-11-10）

基线冻结版本（P0 之前）：五阶段流水线（CLARIFY→PLAN→GENERATE→VALIDATE→HEAL）、自检修复闭环、上下文工程（第 8 轮上下文节省 +85%）、模型分级路由（成本 -75%）、沙箱预览隔离、预算感知调度、执行轨迹与时间旅行、真 ReAct 分支、虚拟线程对照实验（18.5 倍）。
