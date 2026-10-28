# AgentScope JAVA — GARR P1 原生执行与验证实施记录

> **同一架构、语言原生适配，不另定 GARR 算法**。公共 P1 合同：[GARR-P1-EXECUTION-VERIFICATION-v0.1.md](GARR-P1-EXECUTION-VERIFICATION-v0.1.md)，共有 7 份相同镜像；验证向量：[garr-p1-contract-v0.1.json](fixtures/garr-p1-contract-v0.1.json)。本仓 P0 Planner 仍遵从 `GARR-UNIFIED-CONTRACT-v0.1.md` 的 CPARP/0.1。

## 1. 源码与模块装配

- 原生扩展模块：`agentscope-extensions/agentscope-extensions-garr`。
- P1 主要源码：`src/main/java/io/agentscope/extensions/garr/GarrP1.java`。
- Frame 执行端口：GarrP1.reActReadOnlyPort(Model) (fresh ReActAgent, empty Toolkit, RuntimeContext.empty)。
- 运行时并发和预算：Project Reactor Mono/Flux, concatMap sequenced Frames, timeout, CancelSignal with Sink, BudgetLedger synchronized。

只允许 `AgentScope Extensions -> Core` 单向依赖。TinyClaw 的会话、能力图、Channels、设备与用户 UI 不在框架仓库重写。Frame 是任务单元而不是另一个全权限 Agent。正常 ReAct 未启用 GARR 时应完全保持既有行为。

## 2. 类型化 P1 接口与信任链

`FrameRequest` 必须绑定可信 `scope + graph_revision + frame_id + domains + owned/shared read-only nodes + evidence_refs + obligations + Budget`；执行前检查其完整性与唯一性，并拒绝越域 Evidence。模型回复不能赋权：`FrameArtifact.output` 只是数据，`cited_evidence_refs` 必须属于实际已授权的引用。

`ReadOnlyFramePort` 接入现有 AgentScope API，但不得直接提供写入文件、发送消息、Git 提交或设备控制。独立 `ObligationVerifier` 从宿主校验器签发回执，Join 对根义务和 `contract:<constraint_id>` 分别检查。全部真实回执 -> completed；缺一个 -> partial；Frame 身份/图版本/引用越权 -> blocked；模型失败/超时 -> failed；取消 -> cancelled。

`BudgetLedger.reserve/settle` 保证不会从子 Frame 复制父额度或重复结算。真实 Usage 不可用时按已预留上限保守结算，标明 `upper_bound`，不得伪造实际 Token 消耗。

## 3. 平台边界与有界错误

ReadOnly ReAct has no registered Tools; model evidence_refs parsed from JSON but cannot mint VerificationReceipt. Reactor timeout/cancel short-circuits further Frames; uncooperative remote provider cancellation not independently proven.

## 4. 已执行的本地验证和结果

```text
mvn -o -q -pl agentscope-extensions/agentscope-extensions-garr -am '-Dtest=GarrPlannerTest,GarrUtf8OrderingTest,GarrGoldenFixtureTest,GarrP1ContractTest' '-Dsurefire.failIfNoSpecifiedTests=false' test; 22 JUnit methods
```

**实际状态：** P1 compile/test passed, no real provider calls; Kotlin JVM distinct jar not involved.

Host、Mock 与真实模型 / 真机 / 完整 SDK CI 的验收是独立门禁。任何未执行或非可用平台的检查都必须标 `not_run`。共享测试不证明生产租户鉴权已实现；验证器 Mock 回执仅用于合同测试。

## 5. P1/P2 余项

- 真实可信 GraphContextProvider 的存储、权限、scope/revision 索引；目前多使用测试快照或已授权引用。
- 单次模型调用的真实 Usage 与 Deadline/费用硬预算、失败结算；跨 Run 幂等和 durable 账本尚未具备。
- 子 Frame 取消的远端供应商实际确认、超时下的强制停止、跨进程重启恢复。
- garr/0.1 Wire DTO 的严格 Schema runtime 解析、事件可靠重放、完整 GARR-P1-01…10 Golden Runner 的七语言等价输出。
- 接近生产环境的 ReAct/Harness 回归、平台和设备侧真实功能验收。

## 6. Git 交付规则

当前分支 `feat/garr-p1-executor-verifier-20261028` 是独立 worktree，基于该仓已有 HEAD，且仅从原脏工作区白名单复制 GARR P0 源。只提交本分支的 GARR 相关源码、构建入口、测试、统一 P1 镜像和技术文档，保护其他历史迁移/未提交任务。不得因为文档存在而标记完整 GARR Engine 已生产就绪。
