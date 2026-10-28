# GARR P1 统一执行与验证契约 v0.1

> 状态：**跨语言 P1 实现合同**（2026-10-28）。适用于 AgentScope Rust / Java / Kotlin / Swift / TypeScript / Zig / C++ 的**同一架构**；本文件在七个 P1 worktree 中按完全相同内容镜像。
>
> 全局唯一语义源仍为 Partme 的 `GARR-UNIFIED-CONTRACT-v0.1.md`。本文只细化 P1 的执行与验证边界，不能变更 CPARP-0.1 的 Planner/Constraint/Budget/Scope 规则。SDK 类型系统、异步模型、内存布局不同，不构成行为差异。
>
> **Frame ≠ Agent**。宿主可以用现有 AgentScope ReAct、一次 Model 调用或设备上的受限回调执行；GARR 负责数据和生命周期契约，不能发明第二个无授权工具循环。

## 1. 统一 P1 模块

~~~mermaid
flowchart TD
  Plan[Validated SplitPlan / Direct] --> Gate[Scope/Revision/Evidence/Effect/Budget Gate]
  Gate --> Lease[BudgetLedger reserve]
  Lease --> Port[ReadOnlyFrameExecutor / Existing AgentScope Agent]
  Port --> Artifact[FrameArtifact with measured Usage and claimed evidence refs]
  Artifact --> Verify[Independent ObligationVerifier]
  Verify -->|all receipts verified| Completed[Completed]
  Verify -->|missing proof| Partial[Partial]
  Verify -->|identity/contract violation| Blocked[Blocked]
  Port -->|error/timeout| Failed[Failed]
  Gate -->|cancel| Cancelled[Cancelled]
~~~

1. **`FrameRequest`**：`frame_id`, `scope`, `graph_revision`, `goal`, `owned_nodes[]`, `shared_read_only_nodes[]`, `evidence[]`, `obligation_ids[]`, `budget`, `effect=read_only`。由可信宿主从 GraphSnapshotRef、审批与 BudgetLease 派生，而不是由模型请求中的 JSON 决定权限。
2. **`FrameEvidence`**：`node_id`, `source_ref`, `revision`, `excerpt`；`node_id` 必须属于 owned 或共享只读节点；`source_ref` 非空、作用域已鉴权。模型生成的证据引用不享有权限。
3. **`ReadOnlyFrameExecutor`**：输入经过校验的 FrameRequest，输出 `FrameArtifact` 或结构化错误；不得让它执行写文件、提交 Git、发送消息或设备命令。复用现有 Model/Tool/Harness 机制；尚不能确认只读时 MUST fail closed。
4. **`FrameArtifact`**：`frame_id`, `scope`, `graph_revision`, `output`, `cited_evidence_refs[]`, `usage`。`output` 是**模型声明**，不是已经验证的事实。实际引用只能是已授权的 source refs 子集，不能把“提供给模型的证据”直接冒充模型引用。
5. **`ObligationVerifier`**：可信检查器通过独立代码/测试/规则校验并签发 `VerificationReceipt(obligation_id, validator_id, evidence_ref, graph_revision)`；验证器必须注册在宿主，不能从 LLM 输出自由创建。没有回执时仍是 `partial`。
6. **`JoinVerifier`**：首先核验子产物身份、count、scope/revision、无重复和证据引用白名单；任何严重身份/边界错误是 `blocked`；随后检查全部父义务与 `contract:<constraint_id>` 边界验证义务。只有全部有真实回执才是 `completed`。
7. **`BudgetLedger`**：执行前原子 reserve，实际 Usage 已知且不超额度时 settle(actual)；无法取得真实 Usage、取消/timeout 后应保守 settle(upper_bound)，不得以猜测用量宣称已知账单；重复 settle 拒绝。
8. **`Cancellation`**：执行前取消不启动 Port；执行中取消/timeout 必须停止等待且不能启动下一个 Frame；尚不能证明子进程/远程操作实际停止时标记 `cancelled` 或 `failed`，绝不标记完成。
9. **`Event`**：`run_started`, `frame_started`, `frame_completed`, `frame_failed`, `join_verified`, `run_completed`, `run_blocked`, `run_cancelled`；每个 Run 单调递增 sequence，不存储时明确非 durable。
10. **运行终态**：`completed`, `partial`, `blocked`, `failed`, `cancelled`。语言不支持某能力时显式 `unsupported_capability`，不能悄悄回退为 direct 或 completed。

## 2. 状态机与验收表

| 条件 | 统一状态 | 必须保证 |
| --- | --- | --- |
| 所有 Frame 完成且全部 Obligation/BoundaryContract 有外部回执 | completed | 引用、身份、预算都已通过 |
| 模型完成但缺少一个或多个独立证明 | partial | 不生成“测试通过”声称 |
| scope/revision/Frame ID/证据引用不可信，或跨租户 | blocked | 不调用未经授权的 Tool |
| Model/Port 失败或超时 | failed | 不再调度后续 Frame |
| 取消信号早于或发生于执行期间 | cancelled | 不启动新的 Frame；账本保守结算 |
| 受限端超出固定节点/事件/消息容量 | unsupported_capability | 不另行制定本地 Planner |

## 3. 七语言等价映射

| 语言 | Frame 执行端口 | 独立验证器与取消 |
| --- | --- | --- |
| Rust | async trait + 现有 ReActAgent ReadOnly Adapter | Tokio cancellation/timeout + BudgetLedger + JoinVerifier |
| Java | `CompletableFuture` / Reactor Mono 适配既有 Agent | `CancellationToken`/CompletableFuture.cancel + receipts |
| Kotlin | suspend / Coroutine/Flow + 现有 ReAct | `CancellationException` 传播 / immutable receipts |
| Swift | `async throws` + existing AgentScope Agent | `Task.checkCancellation`, actor BudgetLedger, Sendable Receipt |
| TypeScript | `Promise` + `AbortSignal` + existing Agent | Rejection/error, runtime schema, strict citation whitelist |
| Zig | 固定缓冲/回调端口（不加载可写工具） | 结果枚举、外部可信验证器回调、容量不支持返回明确错误 |
| C++17 | 固定容量 `ReadOnlyPort`，无异常/RTTI/堆 | 已注册的 `Verifier`、独立授权、取消状态标志 |

**不能将这些端口的 Mock/Host 测试表述为完整生产 ReAct/Android/iOS/ESP32 真机验证。**

## 4. 统一合规向量（P1）

GARR-P1-01 valid frame + approved receipt → completed；
GARR-P1-02 valid artifact but no verifier receipt → partial；
GARR-P1-03 wrong scope/revision → blocked；
GARR-P1-04 missing/duplicate frame → blocked；
GARR-P1-05 unapproved citation/evidence node → blocked；
GARR-P1-06 cancelled before running → cancelled, zero Port calls；
GARR-P1-07 Port failure/timeout → failed, zero downstream Frames；
GARR-P1-08 missing boundary contract receipt → partial；
GARR-P1-09 repeated reserve/settle or insufficient budget → rejected；
GARR-P1-10 bounded client over capacity → unsupported_capability。

共享机器可读向量：`docs/garr/fixtures/garr-p1-contract-v0.1.json`，所有 SDK 应使用同一输入和状态预期；测试是否通过须来自各语言真实编译与运行证据。

## 5. 源码演进边界

P1 首先实现只读 Frame、可信收敛与验证端口；支持可写工具、多进程队列/恢复、完整 Usage 计费和实际设备授权需要更深层的 P2/P3 验证。所有 P1 模块应是 AgentScope 可选扩展，并仅单向依赖现有 core，不新增第七个 Agent Fabric 后端服务。

**如果某语言使用轻量 ClientLite，则它实现的是同一 Frame / Receipt / Event 合同的受限执行端口，而不是有不同含义的 P1 推理架构。**
