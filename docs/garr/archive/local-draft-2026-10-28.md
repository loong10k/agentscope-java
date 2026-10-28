# AgentScope Java × GARR 实施设计 v0.1

> **拟议设计，尚未实现。** 2026-10-28。GARR 属于 AgentScope 通用框架；TinyClaw 是类似 OpenClaw 的产品。历史 agentscope-runtime-* 已不纳入新实施路径。跨语言核心约束以 agentscope-rust/docs/architecture/garr-runtime-implementation-v0.1.md 为研究基线；Java 版以本仓的可观察行为/API 为准。

## 1. 当前框架接入点

现有：agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java、agent/RuntimeContext.java、tool/AgentTool.java、tool/subagent/SubAgentTool.java；已有 Harness/Model/Session 等基础能力。不要把 Graph Planner 插入 ReActAgent 大循环，也不要为 GARR 复制 Tool 执行器。GARR 以**独立可选模块**包装现有 Agent 调用，普通 ReAct 默认行为不变。Java API 优先采用现有 Reactor Mono/Flux 风格与明确的取消、错误和上下文传播；如果实现风格调整，以模块现有契约为准。

~~~mermaid
flowchart LR
  T[应用任务] --> G[GarrOrchestrator]
  G --> P[GraphProvider + ConstraintPlanner]
  P -->|Direct| A[ReActAgent]
  P -->|Split| F[ReasoningFrame DAG]
  F --> A
  A --> V[JoinVerifier + Evidence]
  V --> R[Typed Result]
~~~

## 2. 建议模块与 Java API

建议先在 agentscope-extensions/ 新增 **agentscope-extensions-garr**，不要改变 agentscope-core 对 extensions 的依赖方向。拟新增接口（示意，不表示已存在）：

~~~java
public interface GraphContextProvider {
    Mono<GraphSnapshotRef> snapshot(GraphScope scope);
    Mono<GraphSlice> query(GraphSnapshotRef snapshot, GraphQuery query);
    Flux<GraphConstraint> constraints(GraphSnapshotRef snapshot, Set<NodeId> nodes);
}
public interface ReasoningPlanner {
    Mono<PlanDecision> plan(ReasoningFrame frame, GraphSlice relevantGraph);
}
public interface FrameExecutor {
    Mono<FrameArtifact> execute(ReasoningFrame frame, FrameContext context);
}
public interface JoinVerifier {
    Mono<VerificationReport> verify(SplitPlan plan, List<FrameArtifact> results);
}
~~~

模型对象至少包含：GraphSnapshotRef（scope/revision）、NodeId、EdgeKind、EvidenceRef/Status、ConstraintCoupling（Inherited/Contractible/Atomic）、ProofObligation、ReasoningFrame、BudgetLease、SplitPlan、BoundaryContract、FrameArtifact、DecisionTrace。图节点可查询不等于具备数据访问权限；ContextRef 必须通过实际用户/租户 scope 再校验。

## 3. Planner 必须遵守的行为

以 **关系类型感知查询 → 硬约束闭包 → 证据不确定性检查 → 候选 Direct/Probe/Verify/Split → 预算评估 → Frame 执行与 Join 验证** 为固定流程。读取 Calls 只能发现潜在影响路径，不能自动认定为业务因果；重要的共享节点可以只读共享而不是必拆；Contractible 跨域约束需要契约及验证器；Atomic 在写入阶段必须阻止冲突写入所有权。LLM 的候选事实为 Proposed，不能写入已验证事实图。缺少证据先 Probe；预算低于子任务+Join/Verify 预留时不拆。

业务应用创建 Session/Task，框架仅创建 ReasoningRun/Frame。GARR Frame 不应独立继承完整父预算；由 BudgetLedger 原子预留与结算。所有有副作用的 Tool 继续走现有权限/HITL/沙箱；GARR 不直接调用写工具。

## 4. Java 接入与验收

**PR-J1（设计与单测）**：DTO、GraphProvider、ConstraintClosure、确定性 Planner 与 Trace；内存图及 Mock 不要求厂商模型。
**PR-J2（执行）**：对现有 ReAct/Agent/Tool 的只读 FrameExecutor 适配、取消传递、预算结算、JoinVerifier；非空/取消/异常语义与 Reactor 一致。
**PR-J3（跨语言）**：读取 garr/0.1 JSON fixture，比较决策、错误、状态、硬约束、ProofObligation 和 Usage；不为 TinyClaw UI 或微信写框架代码。

验收用例：Direct 无子任务、缺证据 Probe、Atomic 与 Contractible 图干预导致正确分解差异、预算不足不拆、子失败不产生 Completed、租户与 snapshot 不可越权、普通 ReAct/Harness 回归正常。框架实现完成与否必须以实际测试和 OpenSpec/模块流程证据为准；本文件不宣称已实现。
