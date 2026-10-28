# GARR 跨语言统一规范 v0.1

> **Normative ID:** `partme.agentscope.garr/0.1`  
> **状态:** 统一设计契约（Draft / Implementation in progress；不是七语言已完成声明）  
> **日期:** 2026-10-28  
> **唯一规范源:** `partme-docs/docs/standards/garr/GARR-UNIFIED-CONTRACT-v0.1.md`  
> **受控镜像:** 七个 `agentscope-*` 仓库的 `docs/garr/GARR-UNIFIED-CONTRACT-v0.1.md`，**内容必须字节级一致**。  
> **规则等级:** MUST / SHALL 为强制合同，SHOULD 为推荐实践，MAY 为可选扩展。所有语言 MUST 使用同一决策、状态与错误语义。

## 0. 唯一事实源与变更治理

1. **本文是唯一跨语言语义规范**，不允许 Rust/Java/Kotlin/Swift/TypeScript/Zig/C++ 分别制定一套 GARR 的算法、决策枚举、预算口径或执行状态机。
2. `partme-docs` 是规范内容唯一编辑入口；七个仓库中的镜像**只读同步**，镜像文件绝不单独编辑。仓库内的语言适配说明只能记录本地 API 映射、平台资源限制、代码位置、真实测试状态及缺口，不得重定义本规范。
3. 各实现仓库的 **OpenSpec** 管实施任务、验收进度和独立代码事实；不代表可以覆盖此跨语言契约。发现真实冲突时，先提交统一规范的增量修订和 Fixture 更新，再同步镜像，最后分别修改语言适配代码。
4. 每次修改统一规范须提升规范修订号、记录变更与兼容性评估、更新共享合规测试向量、同步七份镜像、运行一致性检查。不允许以“某语言更方便”为理由静默改变行为。
5. `agentscope-runtime-*` 属于退役历史路径，不纳入 GARR 新实施计划。**AgentScope 是框架，TinyClaw 是 OpenClaw 类应用产品，Agent Fabric 是可选管理/托管基础设施。**
6. 当前 Rust P0 和部分只读 P1 已实现并有 Mock 测试；其余语言的文档不代表实现已经存在。**合同一致 ≠ 功能完成 ≠ 性能一致 ≠ 生产就绪。**

## 1. 唯一架构：框架能力与产品适配

~~~mermaid
flowchart TB
  U[用户 / TinyClaw Session & Task] --> RT[TinyClaw Reasoning Router: normal / auto / deep]
  RT -->|normal| OLD[既有产品执行链]
  RT -->|garr| O[GARR Orchestrator / ReasoningRun]
  CG[产品 Capability Graph / Memory / Workspace / Auth] -->|GraphContextProvider + trusted scope| O
  O --> P[统一 Graph-Aware Planner]
  P -->|direct| EX[FrameExecutor]
  P -->|probe / verify| PV[可信查询 / 验证器]
  P -->|split| DAG[ReasoningFrame DAG + BudgetLease]
  DAG --> EX
  EX --> A[现有 AgentScope Agent / ReAct / Model / Toolkit]
  A --> J[JoinVerifier / Proof Obligations]
  PV --> J
  J --> RE[Typed Artifacts / Evidence / DecisionTrace]
  RE --> RT
  RT --> ACT[产品任务状态与权限 / HITL / Sandbox Action Dispatcher]
~~~

**AgentScope 框架 MUST 提供通用契约**：GraphContextProvider、ReasoningPlanner、ReasoningFrame/FrameDAG、BudgetLedger、FrameExecutor、JoinVerifier、DecisionTrace、事件输出。AgentScope **不得**写死微信、企微、GitHub 仓库名、特定设备、产品 UI 或 TinyClaw 的业务任务模型。

**TinyClaw 产品 MUST 提供实际资源与策略**：用户 Task/Session、Capability Graph（Tool/Skill/MCP/Device/Channel）、记忆和工作区引用、认证主体、权限与审批、可用 Agent/模型、用户可见的任务进度。TinyClaw **不得**重写框架层的任务分区/预算算法。产品正常 Turn、现有 Sandbox/HITL 不得被 GARR 旁路。

**Agent Fabric MAY** 提供集中托管、模型网关、长期任务调度、租约和观测，但本地 GARR 不能强制依赖云端控制面。

## 2. 唯一数据模型（语言实现等价）

| 规范对象 | 必需字段 / 不变量 | 责任 |
| --- | --- | --- |
| `GraphSnapshotRef` | `scope`, `revision` 均非空；同一 Run 内固定 | 图谱事实版本 |
| `GraphNode` | `node_id`, `node_kind`, `domain`, `evidence_refs` | 对象及归属 |
| `GraphEdge` | `from`, `to`, `edge_kind`, `evidence_state`, `evidence_refs` | 带类型有向关系 |
| `GraphConstraint` | `constraint_id`, `scope_nodes[]`, `coupling`, `phases[]`, `severity`, `validator_ref?` | 跨节点约束超边 |
| `ContextRef` / `EvidenceRef` | `scope`, `revision`, `source_ref` 或 `object_ref` | 外部上下文/证据引用，**不是权限票据** |
| `ReasoningRun` | `run_id`, `task_id`, `graph_snapshot`, `policy_id`, `status` | 一次完整推理运行 |
| `ReasoningFrame` | `frame_id`, `parent_frame_id?`, `goal`, `phase`, `anchors[]`, `owned_nodes[]`, `shared_read_only_nodes[]`, `constraint_refs[]`, `obligation_refs[]`, `budget_lease_id`, `depth` | 可独立求解的有界单元；**不等于 Agent** |
| `BoundaryContract` | `constraint_id`, `producer_frame`, `consumer_frame`, `validator_ref` | 跨帧契约与验证 |
| `SplitPlan` | `mode=and`（v0.1）、`children[]`, `dependencies[]`, `join_obligations[]`, `required_budget` | 可验证的推理 DAG |
| `FrameArtifact` | `frame_id`, `scope`, `graph_revision`, `artifact_ref`, `evidence_refs[]`, `obligation_status`, `usage` | 推理产物而不是事实声明 |
| `VerificationReceipt` | `obligation_id`, `validator_id`, `evidence_ref`, `verified_revision` | 由可信验证器签发的检查结果 |
| `DecisionTrace` | `policy_id`, `graph_revision`, `considered_nodes[]`, `constraint_ids[]`, `candidates[]`, `chosen_action`, `reason_code` | 记录实际影响决策的依据 |

**所有对象的语义、字段名称和默认错误处理由本规范锁定。** Rust 的 struct、Java record/class、Kotlin data/sealed、Swift Sendable、TS union、Zig/C++ 固定容量类型只是表现形式，不允许改变这些字段的含义。P0 原型字段覆盖不足之处应标为 **待补齐**，不能反向缩减规范。

### 2.1 固定枚举

**关系类型（v0.1）：** `implements`、`calls`、`reads`、`writes`、`depends_on`、`constrained_by`、`verified_by`、`precedes`、`conflicts_with`、`hypothesized_affects`。

**证据状态：** `observed`、`verified`、`proposed`、`disputed`、`stale`。Observed 表示观察来源存在，不等于已经独立验证；`proposed/disputed/stale` 不得直接提升为可靠事实。

**约束耦合：** `inherited`、`contractible`、`atomic`。硬约束的要求不可与软评分进行价格交换。

**任务阶段：** `analyze`、`design`、`implement`、`verify`。

**规划动作：** `direct`、`probe`、`verify`、`split`、`blocked`。

**拆分模式：** **v0.1 仅 `and`**；OR 方案比较、独立 VERIFY 递归作为后续版本扩展，不得由某语言提前赋予同名不同语义。`verify` 动作已单独存在。

## 3. 唯一 Planner 行为（CPARP-0.1）

所有 Full Engine 实现 MUST 对**相同快照 + 相同任务 + 相同策略版本**遵循下列确定性决策顺序，不允许把某语言的策略改写成自由 SubAgent Prompt。

~~~mermaid
flowchart TD
  A[Trusted Task + Policy + GraphSnapshot] --> B[校验 scope/revision/节点与证据引用]
  B --> C[关系类型+阶段+方向的有界图扩展]
  C --> D[硬约束闭包 + Proof Obligations]
  D --> E{证据状态}
  E -->|disputed| V[verify]
  E -->|proposed / stale| P[probe]
  E -->|可信且充分| F[领域划分候选 + Atomic 收缩]
  F --> G[跨分区 Contractible verifier 与硬约束门禁]
  G --> H[Direct/Probe/Verify/Split 资源和风险评估]
  H --> I{Split 可行且净收益足够?}
  I -->|否| R[direct / probe / verify / blocked]
  I -->|是| S[预留子帧与 Join/Verify 预算; split]
  V --> T[DecisionTrace]
  P --> T
  R --> T
  S --> T
~~~

### 3.1 关系传播与约束闭包

- `calls` 是**代码调用**，绝不自动等同 `causes`；调用边的方向、逆向影响需要依据任务阶段明确配置。
- `constrained_by` MUST 触发当前作用域内的必要硬约束闭包；`verified_by` SHOULD 生成验证义务。`hypothesized_affects` 只能产生候选探测。
- 相关性分数仅用于**排序**，不能裁剪已知硬约束。范围上限不足以完成强制闭包时 MUST 返回 `blocked` 或可明确补证的 `probe`，不得输出不完整却被标记为成功的 Split。
- `TaskConditionedImportance` 由任务相关度、潜在影响、桥接性、约束关键性与通用 hub 惩罚组成；高重要节点可能是多个子 Frame 的共享只读节点，不意味着必定拆为 Agent。
- 图谱 MAY 有环；执行 Frame DAG MUST 无环。不能使用任意深度的关系遍历替代类型感知搜索。

### 3.2 约束保持型划分

- `inherited`：相关子 Frame 必须继承对应不可放宽条件及验证义务。
- `contractible`：仅在有**可识别、可执行的 `validator_ref` 和 BoundaryContract** 时允许越过该分区边界。缺失 verifier → `probe` 或 `blocked`。
- `atomic`：在 `implement` 阶段，所有相互冲突的写入责任必须收缩到同一单元，或阻断拆分；`analyze` 阶段可以对相关只读子问题分别研究。
- 子任务 AND 覆盖要求：`union(child_obligations, join_obligations)` 覆盖全部父义务，并明确责任者；不得由 LLM 自行删除必需义务。
- 划分候选在 v0.1 以 2–3 个 Frame 为起点，若共享边界成本过高或只有一个领域则选 `direct`；不应人为制造新业务模块/新服务。
- 分区计算的节点/关系权重与预算参数属于 `policy_id`，跨语言 Full Engine 在相同 policy_id 下 MUST 使用**同一版本的算法实现或运行同一套 Golden Fixture**。不能只让各自模型“尽量模仿”。

### 3.3 证据、不确定性与决策价值

- 关键 `disputed` → `verify`（冲突优先）；关键 `proposed/stale` → `probe`。
- Evidence、Conflict、Structural、Behavioral、Decomposition 五类不确定性是**内部特征**，不能将 LLM confidence 直接当成已校准成功率。
- 候选动作顺序：先过滤硬约束/权限/图版本不合格者，再比较预计残余风险、模型/工具成本、关键路径延迟、跨分区 Cut 与 Join 成本；候选 `split` 只有在对最优非拆分动作存在**大于安全裕量**的估计净收益时才执行。
- v0.1 为确定性启发式，不声称具备经过实证校准的 EVC。阈值/代价权重是共享策略版本的一部分；未配置不能各语言自行采用含义不同的默认值。
- 触发深度、祖先等价 fingerprint、Frame 数量、上下文/节点上限是硬边界；预算不足时不假装任务完成。

### 3.4 `garr-cparp-0.1`：统一确定性参考策略

仅有统一流程图还不够：**同一个 `policy_id` 不允许由各语言自行决定阈值、方向与评分**。v0.1 以下数值为可重复的启发式参考策略，不是已校准概率或最优 EVC。引入新算法、学习式权重或不同拆分策略必须使用新的 `policy_id` 并更新 Golden Fixture。

- 默认上限：`max_graph_depth=2`、`max_nodes=120`、`max_recursion_depth=1`、`max_children=3`、`minimum_split_gain=10`。
- `precedes` 只在 `implement` 阶段允许正向遍历；其他关系允许正向遍历。允许反向遍历的类型仅 `implements`、`depends_on`、`constrained_by`、`conflicts_with`；`writes` 仅在 `implement` 允许反向；其余禁止反向。
- 以锚点执行有界搜索后进行**独立的强制硬约束闭包**。证据冲突 `disputed` 优先 `verify`，`proposed/stale` 优先 `probe`。硬约束闭包越界返回 `blocked`，不能截断后声称成功。
- 以领域 `domain` 为候选分区，`test/rule` 只作为共享只读信息；`implement` 阶段对 `atomic` 相连的领域作确定性收缩。`contractible` 跨分区必须有非空验证器。分区按 UTF-8 字节序排序，大小写敏感，禁止本地化排序改变输出。
- 分组数量 `k<2` 或 `k>3` 不拆分。子任务必须覆盖全部 Proof Obligations，Join 承担跨帧契约。

~~~text
n = visited_node_count           // 包含约束闭包中的节点
k = partition_count              // 合并 Atomic 后的领域数
x = cross_partition_edge_count   // 每条跨分区边计一次
c = cross_partition_contractible_constraints_with_validator

direct_score = 25 + 6*n + 85*k + 4*x
split_score  = 25 + 6*n + 30*k + 26*x + 18*c + 30
split_gain   = max(0, direct_score - split_score)

required_tokens = 300 + 1800*k + 900 + 700
required_model_calls = 0 + 2*k + 1 + 1
required_graph_reads = 2 + 2*k + 1 + 1

if k < 2 or k > 3: direct
else if invalid hard constraint or missing boundary verifier: probe / blocked
else if any budget dimension insufficient: direct (or blocked when unsafe)
else if split_gain < 10: direct
else: split (and)
~~~

所有计算采用有检查的无符号整数算术，不允许溢出回绕。跨语言 `full_engine` 宣称相同 `policy_id` 时必须在同一 Task/GraphSnapshot/Budget 输入下对齐统一 Golden Fixture。Rust 当前 P0 仅实现部分预算维度；尚未实现费用/时限等硬门禁的实现不得宣称完整 `full_engine`。端侧 `client_lite` 可不计算该策略，但 MUST 通过同一协议转交完整 Planner，不能自行发明替代算法。

## 4. 统一预算、权限与完成判据

**预算向量** `BudgetLimits` 包含 `max_tokens`、`max_model_calls`、`max_graph_reads`、`max_cost_microusd`、`deadline_ms`、`max_depth`、`max_children`、`max_parallel_frames`。未实现的维度必须显式上报 `unsupported` 或由可信调度器管控，**不能默认为无限额**。

**租约规则**：框架 MUST 原子 reserve/settle，子 Frame 不得复制父预算；Split 在执行前为 Join/Verify 保留额度；错误结算/重复结算拒绝；真实 Usage 不可得时 MUST 明示 `usage_kind=upper_bound` 并按上限保守计费（不冒充真实 Token 消耗）。并行壁钟耗时按关键路径估计，而不是简单累加。

**权限规则**：`scope`、tenant、身份与预算上限由可信运行时上下文派生；模型返回的 JSON、ContextRef、GraphSnapshotRef 和工具描述都不是权限令牌。子 Frame 只能继承或**收窄**父 Frame 权限。任何写文件、Git 修改、发送消息、远程设备动作必须通过既有 Tool Permission/HITL/Sandbox；GARR v0.1 首先仅开放只读分析。

**完成判据**：模型输出“通过”只是一段文本。每个强制 `obligation_id` 必须有可信 `VerificationReceipt`；还必须验 scope/revision、Frame 身份与 Join BoundaryContract。缺项只可 `partial` 或 `blocked`；子任务失败/取消不得使 Root Run `completed`。

## 5. 统一协议：garr/0.1

> **唯一机器可验证 Wire Schema**：[garr-wire-v0.1.schema.json](schema/garr-wire-v0.1.schema.json)。其 PlanRequest / PlanResponse / Event 形状与本节一致。所有语言必须从同一 Schema 生成或校验消息，不能凭语言私有 DTO 改写字段、决策动作或错误语义；Schema 的存在不表示已通过七语言运行时兼容测试。

所有对外 Wire 字段统一 **snake_case UTF-8 JSON**；语言 SDK 可以按惯用语法使用不同属性名，但序列化层必须映射到完全相同的字段与枚举值。协议结构与内部 Rust P0 类型**不要求字段一一同形**，必须有可验证转换器。任何非预期枚举值、未知执行动作、超过资源上限都必须拒绝，不能默默退化为 Direct 或放宽授权。

### 5.1 PlanRequest 示例

~~~json
{
  "schema_version": "garr/0.1",
  "request_id": "request-01",
  "run_id": "run-01",
  "task": {
    "task_id": "task-01",
    "goal": "分析订单取消的跨模块影响",
    "phase": "analyze",
    "anchors": ["order.cancel"],
    "obligation_refs": ["state-validity", "refund-consistency"]
  },
  "graph_snapshot": {"scope": "project/demo", "revision": "git:abc123"},
  "policy_id": "garr-cparp-0.1",
  "budget_limits": {
    "max_tokens": 12000,
    "max_model_calls": 8,
    "max_graph_reads": 30,
    "max_cost_microusd": 50000,
    "deadline_ms": 30000,
    "max_depth": 1,
    "max_children": 3,
    "max_parallel_frames": 2
  },
  "context_refs": ["ctx://project/demo/git:abc123/cancel"]
}
~~~

此请求是**结构示例**，不等于调用者可以自行决定上述授权额度；服务端必须依据真实主体和租户规则重算额度。

### 5.2 PlanResponse 示例

~~~json
{
  "schema_version": "garr/0.1",
  "request_id": "request-01",
  "run_id": "run-01",
  "decision": "split",
  "policy_id": "garr-cparp-0.1",
  "graph_snapshot": {"scope": "project/demo", "revision": "git:abc123"},
  "split_plan": {
    "mode": "and",
    "children": [
      {"frame_id": "frame-order", "domains": ["order"], "obligation_refs": ["state-validity"]},
      {"frame_id": "frame-payment", "domains": ["payment"], "obligation_refs": ["refund-consistency"]}
    ],
    "dependencies": [],
    "join_obligation_refs": ["state-validity", "refund-consistency"],
    "boundary_contract_refs": ["contract:refund-policy"]
  },
  "trace": {
    "reason_code": "split_positive_net_value",
    "considered_nodes": ["order.cancel", "payment.refund"],
    "constraint_ids": ["refund-policy"]
  }
}
~~~

`split_plan` 在非 split 决策下 MUST 为 null 或省略。真实 SplitPlan 的 FrameScope、预算租约、validator_ref 等字段仍须由可信运行时填充，不能仅凭上例的摘要直接执行子任务。

### 5.3 统一事件与状态机

**事件名称固定：** `run_started`、`frame_planned`、`frame_started`、`frame_completed`、`frame_failed`、`awaiting_approval`、`join_verified`、`run_completed`、`run_blocked`、`run_cancelled`。每个事件至少包含 `schema_version`、`run_id`、`sequence`、`event_type`、`timestamp`、`frame_id?` 与 `graph_revision`，要求单 Run 单调序号及可重放去重语义；未实现持久化则明确标为非 durable。

~~~mermaid
stateDiagram-v2
  [*] --> Created
  Created --> Planning
  Planning --> AwaitingEvidence
  AwaitingEvidence --> Planning
  Planning --> Ready
  Planning --> Blocked
  Planning --> Partial
  Ready --> Running
  Running --> Joining
  Running --> Failed
  Running --> Cancelled
  Joining --> Completed
  Joining --> Partial
  Joining --> Blocked
  Completed --> [*]
  Partial --> [*]
  Blocked --> [*]
  Failed --> [*]
  Cancelled --> [*]
~~~

**终态值统一：** `completed`、`partial`、`blocked`、`failed`、`cancelled`。统一错误代码至少包含：
`unsupported_capability`、`invalid_schema_version`、`snapshot_mismatch`、`scope_denied`、`constraint_unresolved`、`evidence_missing`、`budget_exhausted`、`frame_cycle`、`invalid_transition`、`verification_incomplete`、`execution_failed`、`cancelled`。

**向前兼容**：未知可选字段可忽略并保留 trace；未知强制枚举/动作/状态/权限字段必须拒绝。兼容升级需提供旧/新双方 Golden Fixture，禁止仅因代码编译通过就宣告跨语言协议兼容。

## 6. 三种**统一能力配置**，不是三套语言架构

**Capability Profile 是部署环境选择，不是编程语言专属的另一个算法。**

| Profile | 能力 | 同一合同下的边界 |
| --- | --- | --- |
| `full_engine` | 本地 GraphContextProvider + CPARP Planner + 预算/Frame/Join | 必须满足全部共享 Planner Fixture，尤其约束保持、成本和状态语义 |
| `frame_executor` | 本地通过已有 AgentScope Agent/Model 执行受限 Frame，Planner 可位于其他进程 | 同样的 Frame/Artifact/Verifier 与预算合同；不得自己偷偷重划分 |
| `client_lite` | 跨进程请求、消费计划与事件、暴露受控本地能力 | 可用于 Zig/C++ 嵌入式，也可用于其他语言；远端离线时显式 blocked/unavailable，不得冒充已完成 |

`agentscope-rust` 可优先实现 full_engine；Java/TS/Kotlin/Swift 既可完整等价实现，也可作为 frame_executor/client_lite；Zig/C++ 资源有限时推荐 client_lite。**差别只在部署与能力可用性，不能有不同的决策定义或完成判据。** `full_engine` 不能宣称已实现，除非通过统一 conformance suite。

## 7. 七语言映射表（仅代码适配，不产生独立语义）

| 仓库 | 优先复用的已存在框架对象 | GARR 接入形式 | 当前 GARR 代码状态（2026-10-28） |
| --- | --- | --- | --- |
| agentscope-rust | Agent / ReActAgent / Model / Toolkit / Harness | 可选 `agentscope-extensions-garr` + Mock/ReAct 只读适配 | **P0 + 部分 P1**，未生产就绪 |
| agentscope-java | ReActAgent / AgentTool / RuntimeContext / Harness | 同一 Wire Contract 与有界 Planner/Frame Adapter | **未实现，仅有草案** |
| agentscope-kotlin | ReActAgent / SuspendAgentTool / Flow / Harness | 同一 Wire Contract；协程/Android 适配 | **未实现，仅有草案** |
| agentscope-swift | Agent / ReAct 扩展 / Swift concurrency | 同一 Wire Contract；Sendable/actor 适配 | **未实现，仅有草案** |
| agentscope-typescript | Agent / Toolkit / 事件 | 同一 Wire Contract；Promise/AbortSignal 适配 | **未实现，仅有草案** |
| agentscope-zig | ReActAgent / Toolkit / 嵌入式运行接口 | 同一 Wire Contract 的有界 ClientLite 或等价完整引擎 | **未实现，仅有草案** |
| agentscope-cpp | ReActAgent / Toolkit / ESP32 C++17 | 同一 Wire Contract 的有界 ClientLite；设备侧安全独立执行 | **未实现，仅有草案** |

语言适配说明允许指定 `async/await`、Reactor、Flow、Actor、Promise、固定容量缓冲区、构建命令与源代码路径；**不允许**定义自己的 Planner 决策顺序、约束降级路径、事件枚举或默认完成状态。

## 8. 跨语言必过的单一 Conformance Suite

所有实现 MUST 使用共享编号与同一输入/期望的 Golden Fixture，报告 `pass/fail/unsupported/not_run`，不得以测试文件存在代表通过。对使用同一 `policy_id` 的两个 `full_engine`，相同输入的 `decision`、子 Frame 分区与 hard constraints MUST 一致；`client_lite` 只能报告受控的 `unsupported_capability`，不能返回不同规划结论。

| Fixture ID | 统一断言 |
| --- | --- |
| GARR-001 | 单领域/simple lookup → direct（零子 Frame） |
| GARR-002 | 关键 Proposed/Stale → probe；关键 Disputed → verify |
| GARR-003 | Implement 阶段 Contractible→Atomic 导致合法的分区变化、收缩或阻断 |
| GARR-004 | **Graph intervention**：保持检索文本不变，改变已确认结构关系会影响计算控制而非仅 Prompt |
| GARR-005 | Inherited/Contractible/Atomic 约束、BoundaryContract validator、Join 责任完备 |
| GARR-006 | graph scope/revision/端点不匹配 → blocked；不得越租户 |
| GARR-007 | Token/调用/成本/时间预算不足拒绝 Split；保留 Join/Verify 额度 |
| GARR-008 | 并发预算预留原子，重复结算拒绝；未知 Usage 明确 upper_bound |
| GARR-009 | Frame 失败/取消/超时或验证义务缺失时不得 completed |
| GARR-010 | 模型文字自称“已验证”不得产生 VerificationReceipt |
| GARR-011 | 正常 AgentScope ReAct/Tool/Session 路径不回归；无 GARR 时不强制递归 |
| GARR-012 | Wire JSON v0.1 RoundTrip；未知执行枚举/非法权限值拒绝 |
| GARR-013 | 同一 policy 的 full_engine 在七语言中决策一致或清楚声明未支持 |
| GARR-014 | 嵌入式客户端限额、断网回退与设备端独立授权拒绝 |

**验收必须区分三类证据**：规范镜像哈希一致（文档）、同一 Golden Fixture 行为一致（实现）、真实平台/设备/模型运行通过（生产能力）。三者不可互相替代。

## 9. 唯一实施阶段

1. **P0 / Rust 参考最小核**：Typed Graph、约束闭包、ReasoningFrame、确定性 Planner、预算账本与 DecisionTrace，离线单测。
2. **P1 / 框架受控执行闭环**：ReAct FrameExecutor、JoinVerifier、真正 Model Usage、取消/超时/状态隔离、受限查询、真实验证器。
3. **P2 / 统一 Wire + Golden Fixture**：`garr/0.1` DTO/版本兼容/错误/事件；各语言只按统一合同实现，不引入地方算法。
4. **P3 / TinyClaw 真实使用**：CapabilityGraphProvider、ReasoningRouter、Memory/Workspace、真实运行态、审批/沙箱；先只读复杂任务。
5. **P4 / 端侧和托管**：嵌入式 ClientLite、Agent Fabric 集中调度/恢复、跨设备可靠性与审计。
6. **P5 / 研究验证**：GraphRAG + RLM 对照实验、图干预/消融、准确性、成本、P95、无效拆分、冲突与权限违例率。

每个阶段以**仓库实际代码和测试结果**为唯一完成事实。统一规范不能以文档写出就宣布七种语言完成；任何一端的功能缺失应该写在适配附录和 OpenSpec，而不是创建另一套规范。

## 10. 规范同步与变更操作

- 唯一源文件：`partme-docs/docs/standards/garr/GARR-UNIFIED-CONTRACT-v0.1.md`。
- 七个镜像：`agentscope-{rust,java,kotlin,swift,typescript,zig,cpp}/docs/garr/GARR-UNIFIED-CONTRACT-v0.1.md`。
- 本地校验：在 Windows workspace 根目录运行 `powershell -File partme-docs/scripts/check-garr-spec-sync.ps1`（或 `pwsh`）；逐份比较 SHA-256 与源文件，任何偏差返回非零。
- 原来的 `docs/garr-implementation-v0.1.md` 或 Rust `docs/architecture/garr-runtime-implementation-v0.1.md` **仅为语言适配说明或历史草案入口**；必须明确指向统一规范，禁止保留第二份独立的算法合同。
- 统一规范的破坏性变更应升级 `garr/0.2`（或后续版本）并更新 Fixture；不得在 `garr/0.1` 无记录改变语义。

## 11. 研究来源（不构成已验证实现结论）

- Recursive Language Models: https://arxiv.org/abs/2512.24601
- Graph of Thoughts: https://arxiv.org/abs/2308.09687
- Microsoft GraphRAG: https://github.com/microsoft/graphrag

**特别声明：GARR/CPARP 是 Partme 提出的架构与启发式实现方案，研究价值和性能优势需要独立对照实验确认；本文不宣称它已作为学术标准或被七种语言完全实现。**
