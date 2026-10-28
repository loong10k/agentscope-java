# AgentScope JAVA：GARR 统一规范适配入口

> **language-adapter-only** · 2026-10-28。**本文件不得独立定义 Planner 算法、协议或运行状态。**
>
> **唯一跨语言规范**：[GARR Unified Contract v0.1](garr/GARR-UNIFIED-CONTRACT-v0.1.md)。唯一编辑源：`partme-docs/docs/standards/garr/GARR-UNIFIED-CONTRACT-v0.1.md`。本仓规范、Wire Schema 和 Golden Fixture 均是只读镜像。

## 当前状态

仅完成设计文档；Java GARR 完整引擎、Adapter 和统一测试向量尚未实现。**Rust P0 的实现事实不能当作 Java 完成证据。**

## 语言特定 API 映射

复用现有 `agentscope-core/src/main/java/io/agentscope/core/ReActAgent.java`、`agent/RuntimeContext.java`、`tool/AgentTool.java`、`tool/subagent/SubAgentTool.java` 和 Harness。Reactor `Mono/Flux` 是对统一 Frame/Planner/事件合同的语言形式，不改变决策顺序、预算、约束或错误。选择 `full_engine`、`frame_executor`、`client_lite` 只改变能力部署位置，不允许自行更换 `policy_id` 的算法。

## 验收与历史

正式实现前，按本仓任务管理流程接入 [统一 Golden Fixture](garr/fixtures/garr-v0.1-conformance.json) 与 [统一 Wire Schema](garr/schema/garr-wire-v0.1.schema.json)，未运行必须报告 `not_run`，不能以 API 名称相似宣称一致。旧独立草案已保存于 [本地历史归档](garr/archive/local-draft-2026-10-28.md)，**不再有规范效力**。
