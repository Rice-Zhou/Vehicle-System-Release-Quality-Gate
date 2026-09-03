# TDR-017 — Build Provenance Envelope 与 PostgreSQL Typed Edge Revision

- 状态：Accepted
- 日期：2026-09-03
- 决策依据：Project Owner 于 2026-09-03 通过 `APPROVE M2-KD-2026-09-03-01 WRITTEN SPEC REVIEW` 批准书面规范
- 范围：M2.4 CI/Build Fact ingestion、Build Attempt authority、typed Edge identity/Revision、proof validation 与 GitHub Actions Pilot Smoke
- 相关决定：[TDR-002](TDR-002-kotlin-spring-boot.md)、[TDR-003](TDR-003-postgresql.md)、[TDR-005](TDR-005-rest-openapi.md)、[TDR-007](TDR-007-postgresql-job-outbox.md)、[TDR-009](TDR-009-oidc-and-service-identities.md)、[TDR-016](TDR-016-materialized-release-issue-snapshot.md)

## 1. 为什么选择该技术

V0.2 选择一个 Build Attempt 对应一个 `BuildProvenanceEnvelope schemaVersion: 2`，由 Kotlin/Spring Boot Application 在一个 PostgreSQL 事务中解析 Snapshot、Commit、Build 和 Artifact，创建服务端 Edge Header，并保存三类 append-only typed Edge Revision、Receipt、Idempotency、Audit 与 Outbox。

这些实体存在强结构化关系，需要 FK、事务、并发唯一性、历史查询与确定重放；MVP 只有一个 GitHub Actions Provider、每 Envelope 最多 20 个 Issue 与 20 个 Artifact。沿用 Modular Monolith 和 PostgreSQL 能以最低运维成本满足当前规模和公司级审计要求。

## 2. 解决什么问题

预建 Edge 表还没有稳定逻辑 Edge identity、Build Attempt authority、Producer-safe Contract、两层幂等、proof validator 或冲突保留。原 `TraceabilityFactBatch schemaVersion: 1` 要求调用方提交内部 Entity ID，无法安全创建完整 Commit→Build→Artifact 链，也没有绑定 M2.3 Release Issue Snapshot。

Envelope v2 让调用方只提交来源事实。服务端按 Snapshot、repository/source revision、provider/build attempt 和 Artifact checksum 解析 authority，并拒绝调用方 Fixed/Included/Verified 或 validation conclusion。CI 只提供 provenance，不成为 Release Gate authority。

## 3. 为什么没有选择其他方案

- 通用独立 Edge Batch：要求 CI 理解内部 ID，产生部分链与顺序耦合。
- 原始 Attestation/Event Store + 异步 Worker：当前无签名、吞吐或 Broker 需求，增加第二存储表达和恢复成本。
- CI 直写数据库：绕过 Service Identity、Project Scope、Validation、Audit 和 Domain transaction。
- 每种 CI Provider 单独 Endpoint/Schema：把供应商模型扩散到 Application/Core，增加重复实现。
- Kafka、图数据库或独立 ingestion service：当前数据量与同步事务没有真实瓶颈。

## 4. 对 V0.2 的影响

保留 `POST /api/v1/traceability/facts:ingest`、`traceability:ingest` 与 `Idempotency-Key`，显式以 `schemaVersion: 2` supersede 未实施且无消费者的 v1 pre-release 草案。OpenAPI 和 compatibility baseline 必须在同一实现 Subject 中有意更新；不声称旧 body schema compatible。

PostgreSQL 增加 `traceability_edge_identity`、Build Attempt success/rejected receipt、Build Record attempt/repository authority 和复合约束。Application 新增 normalized Envelope、Validator Port 与一个同步事务用例。GitHub Actions Adapter 只读取 allowlisted CI context，Pilot 成功 proof 最高为 `MEDIUM`。M2.4 不创建 `ARTIFACT_RELEASE`，不判断 Fixed/Included/Verified，也不实现 M2.5 Snapshot。

## 5. 对未来 V0.3 的影响

Jenkins、GitLab 或公司 CI 可以增加 Adapter，把其字段映射到同一 normalized Envelope；签名 Attestation 可以增加 Validator Version 并插入新 Revision，不改写历史。Measured throughput 需要异步化时，Receipt 可以作为 durable inbox，但 Edge authority、事务原子性、digest 和幂等语义保持不变。

未来 schema 通过新版本扩展；不得让 Provider DTO、raw event 或 credential 进入 Core。旧 Envelope、Receipt 和 Revision 必须继续可解释。

## 6. 如何迁移

使用 forward-only Expand Migration 创建 Edge Header/Receipt/约束/索引，为 Build Record 增加 repository 与 attempt，并运行数据 precondition。发现新 authority tuple 冲突时 Migration fail-closed，不自动删除、合并或猜测。

API 的 Path、Method、Permission 和 Idempotency 保持不变；body 使用明确 v2。因为 v1 从未实现或发布，本次以受治理的 pre-release supersession 处理，而不是维护一套不安全的平行实现。若发现任何真实 v1 consumer，必须停止并重新评估兼容迁移。

## 7. 如何测试

Contract Test 覆盖 v2 strict schema、v1 superseded、限制与禁止结论字段。PostgreSQL Test 覆盖 Edge Header/typed FK、Revision chain、Build Attempt uniqueness、跨 Project、并发和不可变性。Application/transaction Test 覆盖 Snapshot-only Issue resolution、Artifact checksum、两层幂等、冲突、Audit/Outbox failure 与完整回滚。

GitHub Actions exact-head Smoke 在临时 Backend/PostgreSQL 中提交真实 CI metadata 与合成 Release/Issue 数据，证明 HTTP、Service Identity、事务和 replay。Security scan 证明 event payload、Token、环境变量、路径、人员信息和原始错误不进入日志或 Artifact。

## 8. 如何部署

不新增服务、Broker、数据库、对象存储、图数据库或 UI。Migration 与 Traceability 模块随现有 Backend 部署；Pilot Feature 默认关闭。启用顺序为 Migration、兼容应用、完整 Gate、显式 Pilot 配置。

GitHub Smoke 使用 Runner 内临时测试 identity，不使用 PAT，也不暴露公网 Backend。Company OIDC、正式 CI credential、retention 和 operator responsibility 必须单独配置并验收。

## 9. 失败时如何恢复

正常 ingestion 的 Domain、Receipt、Idempotency、Audit 与 Outbox 任何写入失败时 PostgreSQL 整体回滚。Build Attempt 同 identity 不同 digest 使用独立短事务保存脱敏 rejected receipt，不覆盖成功事实。Provider 不可用产生明确失败或 `ERROR/UNKNOWN` 新 Revision，不复用旧 VALID。

应用回滚只关闭入口并保留 Migration 和历史 Revision。数据库恢复后对账 Header/Revision chain、Envelope/fact digest、Receipt、Audit、Outbox 与 Idempotency response；不一致时保持 fail-closed 并 roll-forward，不使用 JSON/file/cache fallback。

## 重新评估条件

当出现真实 v1 consumer、单 Build 超过 20 Issue/20 Artifact 或 100 facts、同步事务达到测量瓶颈、Provider 要求签名 Raw Attestation 保留、Company 要求独立 ingestion service、Build authority tuple 无法统一，或新增方案需要写 `ARTIFACT_RELEASE`、改变 Fixed/Included/Verified 或引入第二权威来源时重新评估。触及冻结语义必须转为 ADR Proposal。
