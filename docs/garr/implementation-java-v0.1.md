# AgentScope Java 17：GARR v0.1 详细技术实现与源码对照

> **本文是语言专属的实施文档，不是另一套 GARR 架构。**
>
> **唯一规范源**：[`GARR-UNIFIED-CONTRACT-v0.1.md`](GARR-UNIFIED-CONTRACT-v0.1.md)，统一编号 `partme.agentscope.garr/0.1`；所有类型语义、CPARP 评分、Graph Closure、Proof Obligation、状态机、预算/权限和 Wire Schema 不得随语言变化。
>
> **跨语言源码映射总览**：`partme-docs/docs/standards/garr/GARR-MULTILANGUAGE-IMPLEMENTATION-v0.1.md`。本文可记载语言 API、模块/线程/异常处理、构建验证和实际缺口。

## 1. 目标工程与当前真实性

**仓库：** `agentscope-java`。
**可选 GARR 扩展：** `agentscope-extensions/agentscope-extensions-garr`。
**实现状态：** 新增 Maven 扩展与 `GarrPlanner.java`，12 项 JUnit 通过（GarrPlannerTest 10、GarrUtf8OrderingTest 1、GarrGoldenFixtureTest 1），后者自动解析共享 JSON 的 10 个场景；目前只实现 P0，FrameExecutor/JoinVerifier/预算持久账本未完成。

Java 17 / Maven Reactor / existing Reactive Model API；扩展是 `agentscope-extensions` 下的独立子模块，父聚合 POM 已显式加入，不修改 `agentscope-core`。

本仓 GARR 仅在明确启用时运行。**AgentScope 是底层框架**，只提供结构图、ReasoningFrame、Planner、预算与执行器等能力；TinyClaw 是类似 OpenClaw 的**应用软件**，负责任务路由、Capability Graph、工具与渠道、用户记忆、权限审批、UI/CLI/Service/Embed。不要向本仓导入 TinyClaw 产品服务或重新实现模型/Tool 运行链。

## 2. 本语言模块分层与真实源码位置

### 2.1 Graph / Evidence / Constraint

`GarrPlanner.Node / Edge / Constraint / Snapshot` 是 Java record/enum，权威的 `scope/revision` 不来自 LLM；`Constraint.appliesTo(Phase)` 区分任务阶段。GraphContextProvider 尚未实现，下一步可适配 AST/SQL/Repo Map 或产品侧 CapabilityGraph。

统一模型映射要求：`GraphSnapshotRef(scope,revision)`、`GraphNode`、`GraphEdge(edge_kind,evidence_state,evidence_refs)`、`GraphConstraint(scope_nodes,coupling,phases,validator_ref)`、`ReasoningFrame(anchors,owned/shared,obligations,budget_lease)`。当前 P0 若暂未实现某个对象或字段，需在 P1/P2 **增量补齐**，不能以本语言实现不足删去统一字段。

### 2.2 Planner / Reasoning Decision

`src/main/java/io/agentscope/extensions/garr/GarrPlanner.java` 的 `plan(Frame,Snapshot)` 已实现有向遍历、约束闭包、Proposed/Disputed 门禁、Atomic 领域并查集、跨边界验证器、Direct/Split 代价和 Budget 保留；本版本与 Rust 固定 policy 目标一致。

该 Planner 必须精确执行 `policy_id=garr-cparp-0.1` 同一逻辑：**可信 scope/revision → typed BFS → 完整硬约束闭包 → disputed 优先 Verify / proposed-stale 优先 Probe → Atomic 合并 → Contractible Validator → n/k/x/c 评分 → 子 Frame 与 Join/Verify 预算门禁 → Trace**。只要缺失必需权限或证明义务，就不得输出虚假的 Completed。此处只描述语言映射，不复制算法权威。

### 2.3 FrameExecutor / JoinVerifier / Budget Ledger

未来 `AgentScopeGarrFrameExecutor` 不应复制 `ReActAgent` 内部循环；通过 `io.agentscope.core.ReActAgent` / `AgentTool` / `RuntimeContext` 适配已知 API；只读阶段用禁用工具或空 Toolkit Agent；正式副作用仍由上层权限与 HITL 执行。

本地 P0 无线程、无外部请求；P1 用 Reactor cancellation/timeout 传播到 Agent call、工具运行及预算结算，禁止 `block()` 或创建第二套 ReAct；多租户 scope 从认证 ContextView 取得而不是 Java DTO 自填。

目标依赖方向：`GARR Extension → 现有 AgentScope Core/Model/Toolkit`；不得让 AgentScope Core 反向依赖扩展，或让 GARR 自行创造一套 Tool Permission。Frame 是受限任务单元，不等于每个 Frame 必须新建一个 Agent。所有 GraphContextRef 的读取应由可信 Provider 结合当前认证主体校验。

## 3. 语言调用示例

~~~java
// 已实现的 P0 Planner（仅规划，不自动授权执行）
GarrPlanner planner = new GarrPlanner();
GarrPlanner.Decision decision = planner.plan(frame, graph);
if (decision.action() == GarrPlanner.Action.SPLIT) {
    GarrPlanner.SplitPlan plan = decision.plan();
    // P1 执行器需再次验证边界、申请 BudgetLease 后创建 ReAct Frame。
}
// P1 建议的 Reactor API（尚未写入生产代码）：
// Mono<FrameArtifact> execute(ReasoningFrame frame, FrameContext context);
~~~

示例中的规划结果不能直接当作外部写操作授权；`split` 的执行还需要冻结 GraphSnapshot、核查 Boundary Contract、预留 BudgetLease、提交可信 FrameExecutionContext，最后按独立验收义务 Join。

## 4. 协议与测试对接

**共享规范文件**：[统一 Wire Schema](schema/garr-wire-v0.1.schema.json) 与 [统一 Golden Fixture](fixtures/garr-v0.1-conformance.json)。任何 DTO 序列化映射均必须输出 `schema_version=garr/0.1`、固定的 snake_case 字段和枚举字符串；无能力的 ClientLite 返回 `unsupported_capability` 而不是默认 Direct。

**本语言当前执行命令：**

~~~text
mvn -o -pl agentscope-extensions/agentscope-extensions-garr -am '-Dtest=GarrPlannerTest,GarrUtf8OrderingTest,GarrGoldenFixtureTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
~~~

**实际验证证据/限制：** Maven Offline：`GarrPlannerTest` 10 + `GarrUtf8OrderingTest` 1 + `GarrGoldenFixtureTest` 1 = **12 个 JUnit 方法、0 failed、0 errors**。其中 `GarrGoldenFixtureTest` 从同一 `garr-v0.1-conformance.json` 逐项执行 **10 个公共场景**；额外的 `GarrUtf8OrderingTest` 覆盖 GARR-015 UTF-8 字节序。完整输出 JSON 对照与 Wire Schema 合规仍未实现。

所有新增实现需覆盖至少以下共同规则：GARR-001 单领域 Direct；GARR-002 不可靠证据门禁；GARR-003 Atomic vs Contractible；GARR-004 关系干预；GARR-006 版本/作用域；GARR-007 预算。后续必须补齐 GARR-005..014 的完整 Wire/Usage/Frame/权限/取消兼容矩阵，并与 Rust 相同 `policy_id` 做规范化逐字段对比。请保留失败报告，不得使用跳过测试冒充兼容。

## 5. 后续代码实施步骤（未完成不得勾选）

1. **P1-1（共享 JSON 驱动已实现，规范化输出未完成）**：`GarrGoldenFixtureTest` 已直接执行 10 个 Golden Cases；下一步为 reason_code、Frame scope、Budget 的规范化 JSON 输出与 Rust 按字段比较。
2. **P1-2**：分离 Java 的 Model/Tool/Frame Adapter 与 CPARP；ReAct 真实调用应只读，不能让模型直接创建子 Agent 绕开 Planner。
3. **P1/P2-3**：补 `BudgetLedger` 原子租约与撤销/超时/实际 ChatUsage；对重复 settle 和超额拒绝加测试。
4. **P2-4**：实现 `JoinVerifier` 和可信验证回执；无证据返回 partial/blocked，不能根据文本内容判断完成。
5. **P2-5**：完成 garr/0.1 DTO/JSON Schema 严格校验；Maven CI 接入协议兼容用例。

对应的 OpenSpec/仓库任务台账应记录源码定位、真实测试命令、错误输出与行为证据。统一规范只作为共同语义合同，语言实施的 Done 标准独立于文档存在。

## 6. 最终验收与边界

要宣称 `full_engine`，必须证明：相同 `policy_id` 和相同 Task/GraphSnapshot/Budget，规划与 Frame 区分在共享 Fixture 中一致；能使用真实 BudgetLedger/Model Usage；Frame DAG 取消、超时、证据访问/租户隔离、JoinVerifier 均通过。要宣称 `frame_executor`，必须证明 Frame scope 和执行权限不越权；`client_lite` 必须有统一协议与失败路径。

**不在当前范围内**：TinyClaw UI/微信渠道/设备管理、Agent Fabric 的多租户控制面、学习型 Planner 的已验证性能收益，以及把调用关系推断为因果事实。
