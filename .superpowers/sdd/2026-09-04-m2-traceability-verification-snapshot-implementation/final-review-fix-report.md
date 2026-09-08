# Final Review Fix Report

## Owner Decision Receipt

Project Owner 已给出 `APPROVE M2-5-OWNER-GATE-001`，验收记录现为 `APPROVE`，决定记录时间 `2026-09-07T05:26:44Z`。原始指令固定在中文 receipt `93a518254978c7130fed9b6e1a5233bde18eb896` 与英文 receipt `5d209d82f596081d80ae18d965a331a23d637435`。实施 Subjects、性能与 canonical 限制、Evidence 归档义务保持不变；下文 PENDING/UNKNOWN 为实施及复审时的历史状态，不代表当前 Owner 状态。

## Status


最终范围化复审为 `APPROVE_FINAL`，0 Critical / 0 Important / 0 Minor；当前实现技术状态为 `COMPLETE`。双语实施 Subject 的四条 exact-head M1/M2 CI 已全部成功，两份 Artifact 均为 12/12 PASS。Owner record 现固定新 Subject 与 Evidence，但 Owner Authorization 仍为 `UNKNOWN`，状态为 `PENDING`；技术完成不代替 Owner 验收。

## Root Causes and Changes

- 查询只读取 Header、Issue Result、主路径与 Gap，遗漏已经持久化的非主路径 Edge。现在一次按 `ordinal` 读取全部 `traceability_snapshot_edge`，经 Application、DTO、Controller 返回 `edges`，保留主路径。公开字段仅含身份、准确修订、端点、Confidence、状态、authority 与 fact digest，禁止 source reference、原始 payload 和 credential。
- 原恢复演练从另一个 `RUNNING` Run 的固定输入重新运行图验证器，再比较已存 hash 列与部分 ID；没有验证恢复后的实际结果内容。现在使用完成 Snapshot 的 Header、Issue Result、全边、Path、Gap 与准确 producer identity，只从其固定 Issue Snapshot 读取不可变 digest 元数据，重建既有 canonical projection 并分别重算 Gap、Issue Result、overall digest。恢复路径不加载执行 ledger、不重新运行图验证器、不读取 source revision 或 Manifest view。用于 reclaim 的 Run 刻意采用不同输入。
- 复用唯一的 canonical projection factory、JCS renderer 和数据库 Gap token mapping；只将相应内部方法开放给模块内测试，不改变 canonical bytes/version、V0.1、Migration 或生产不可变性保护。
- 常数读取预算更新为 release/header/issues/edges/paths/gaps 各一次，共六次 Repository read，加一次 membership read，共七次授权数据库往返。Performance 仍使用 20 Issues、2,000 Edges、3 samples，同时断言返回 2,000 条 Edge；Gate 与 Evidence allowlist 同步更新。
- 真实 dump/restore、restart、fresh connection、process identity、reclaim、Dead Letter 和 manual retry 均保留。七种损坏只注入独立恢复数据库内的回滚事务：Fixed/Included、Included、Confidence、Gap reason、Gap break entity、主路径 Confidence、非主路径 numeric revision；hash 列不变，每种都要求校验拒绝，回滚后再次通过。生产 trigger/constraint 未改变。

## RED and GREEN

- 查询 RED：`TraceabilityVerificationReadQueryShapeTest` 预期全边读取，原实现缺少该读取，1 test failed。
- 恢复 RED：`RestoredTraceabilitySnapshotTest` 注入内容变化而保留摘要；信任原摘要的实现未抛错，准确失败。接入逐层重算后 GREEN。
- 聚焦 GREEN：`RestoredTraceabilitySnapshotTest`、`TraceabilityVerificationReadQueryShapeTest`、`TraceabilityVerificationDtoTest`、`TraceabilityVerificationQueryHttpTest`、`TraceabilityCanonicalizerTest`、`TraceabilityVerifierTest`，47 tests passed。
- 新增 HTTP 全边断言、OpenAPI 严格字段断言与非主路径 mutation 后，再运行 `RestoredTraceabilitySnapshotTest`、`TraceabilityVerificationQueryHttpTest`、`M2ApiContractTest`、`*ArchitectureTest`，exit 0。
- `node scripts/contract-validator.mjs`：`schemas=4 positive=12 negative=5 operations=34`。
- `scripts/tests/m2-5-verify-gates.tests.ps1` 验证六项常数读取与额外 Edge read 的失败关闭。公开 Replay 断言准确非主路径 revision，并逐字节比较追加新权威事实前后的完整历史响应。
- PostgreSQL 聚焦执行：`TraceabilityReplayTest`、`TraceabilityVerificationRecoveryTest`、`TraceabilityVerificationPerformanceTest` 均编译完成，但执行停在 `DockerClientProviderStrategy`。3 tests failed at initialization，未运行数据库语义断言，不计为 PASS。该本机限制仍存在；下述新候选 CI 已实际完成数据库语义验证。

## Final Review and CI Receipt

中文 Subject `3b010726941c26f0b4096cea34ea4b4dd80c5283`：M1 Run `34076975289` / Job `101604919812`，M2 Run `34076975284` / Job `101604919950`，Artifact `10002515016`。英文 Subject `de49b2af6ddf1e5f529453e2714366064c873e15`：M1 Run `34077129957` / Job `101605362686`，M2 Run `34077129961` / Job `101605362731`，Artifact `10002554126`。全部成功，summary/sidecar 匹配且 unsafe false；准确 locator、digest 与到期时间见 [Owner Receipt](../../../docs/governance/acceptance/records/2026-09-04-m2-5-owner-gate-001.md)。前次 receipt 被本轮 Evidence 替代并保留历史。

双方均实际完成 20 Issues / 2,000 Edges / 3 samples，全边查询六类各一次，加 membership 共七次；四项 recovery 均 PASS，七种损坏在恢复测试内执行。中文 start/worker/query P95 为 `1467/4078/24 ms`，英文为 `1477/4137/21 ms`。创建 Run 的两项 P95 均未达到 `1000 ms` 参考目标，但通过 `30000 ms` 硬上限；不得声明参考目标已达成。

## Existing Digest Boundary

本轮技术裁定保持已版本化 canonical 格式：非主路径 Edge 的 type、ID、numeric revision、revision ID 与 fact digest 已纳入 overall digest；其 from/to、Confidence 等展开字段不在既有 overall projection 中。M2.4 fact digest 还需要 Snapshot 未保存的 proof reference/proof digest，因此不能仅凭 Snapshot 重算该上游摘要，也不能宣称可检测所有字段篡改。非主路径损坏测试使用已覆盖的 numeric revision。若未来需要覆盖额外字段，应另行设计版本化投影及迁移，不能静默改变历史摘要语义。

## Handoff

当前结果：Owner APPROVE 与原实施证据保持有效，原 ZIP 已纳入[Git 保存](../../../ops/evidence-archive/m2-5-preparation/README.md)。Company 归档按[阶段决定](../../../docs/v0.2/reviews/2026-09-08-demonstrable-product-priority.md)延期。Git 状态：本次双语说明提交不改变实施 Subject。下一步动作：核对可展示成品的最小缺口。前置条件：无（只读核查）。验收目标：每项缺口有现有文件或可复现检查依据，不直接实施下一里程碑。
