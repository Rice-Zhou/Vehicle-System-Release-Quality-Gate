# Final Review Fix Report

## Status

本轮集中修复最终评审的两项 Important P2：完整 Snapshot Edge 查询，以及从恢复后的 Snapshot 内容重算摘要。当前技术状态为 `PENDING_CI_AND_REVIEW`，不是最终验收完成。旧 CI Evidence 只证明旧 Subject；Owner record 保留原 Subject 与 `PENDING`，未重写或代替 Owner 决策。

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
- PostgreSQL 聚焦执行：`TraceabilityReplayTest`、`TraceabilityVerificationRecoveryTest`、`TraceabilityVerificationPerformanceTest` 均编译完成，但执行停在 `DockerClientProviderStrategy`。3 tests failed at initialization，未运行数据库语义断言，不计为 PASS。新候选必须取得真实 PostgreSQL CI Evidence。

## Existing Digest Boundary

本轮技术裁定保持已版本化 canonical 格式：非主路径 Edge 的 type、ID、numeric revision、revision ID 与 fact digest 已纳入 overall digest；其 from/to、Confidence 等展开字段不在既有 overall projection 中。M2.4 fact digest 还需要 Snapshot 未保存的 proof reference/proof digest，因此不能仅凭 Snapshot 重算该上游摘要，也不能宣称可检测所有字段篡改。非主路径损坏测试使用已覆盖的 numeric revision。若未来需要覆盖额外字段，应另行设计版本化投影及迁移，不能静默改变历史摘要语义。

## Handoff

本轮不推送、不合并、不打 Tag、不部署、不调用外部 Provider/Jira，也不修改 Owner record。下一步唯一动作是在新双语提交上执行最终复审及 exact-head CI，取得完整 PostgreSQL、Replay、Recovery、Performance 与双语 Pair Gate 证据后再更新候选验收材料。
