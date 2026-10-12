# Task 5 持久化输入跨 JVM 重放工程记录

- 日期：2026-10-12；性质：隔离测试工程证据，不是 Owner 或里程碑验收。
- 依据：[Task 5 实施计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)、[原工程记录](2026-10-08-quality-task5-engineering.md)。

## 验证范围

`QualityEvaluationRecoveryIntegrationTest` 在临时 PostgreSQL 中保存同一固定输入的两条 Evaluation。两条记录的请求 ID 与创建时间不同，生成的 Input Snapshot ID 也不同；测试断言 `inputDigest` 与最终 `resultDigest` 相同，并确认两组不同的请求元数据确实写入数据库。随后以 `pg_dump`/`pg_restore` 恢复独立数据库，另复制受控 Evidence Payload 到独立目录，从恢复库读取已持久化的 Input Snapshot 和规则 AST，在三个新 JVM 中重放，逐一与原最终结果摘要比较。

恢复副本的 Evidence 完整性先为 `VERIFIED`。删除该副本的 Payload 后，当前完整性检查为 `INTEGRITY_ERROR`，而历史固定输入重放仍产生原摘要。这两种结论分开断言。未知历史 Engine 版本由既有 `QualityReplayTest` 明确拒绝。

本测试的 Input Snapshot 由合成样例构造并经仓库存储，正式来源读取器在该测试中使用 mock；它证明持久化、元数据隔离和跨进程重放，不证明实际 Provider、真机或完整正式来源选择。结果摘要随随机测试数据变化，CI 断言比较同次运行中的精确值，不发布一个固定字面摘要。Task 5 中多 Attempt 历史及其他未勾选项仍需独立核查。

## 验证

本机 Kotlin 测试编译成功；目标恢复测试因本机无 Docker 在 Testcontainers 初始化处停止。先前本机 `QualityReplayTest` 四项成功，含三新 JVM 和未知 Engine 拒绝。数据库恢复及本次新增断言以下列固定提交 M1 CI 为准；M1 的 `Run M1 candidate gate` 执行 `clean test bootJar`。

| 实施提交 | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| 中文 `3abd0c1` | [38230285276](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38230285276) | [38230285272](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38230285272) | [38230285363](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38230285363) |
| 英文 `fed761d` | [38229776816](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38229776816) | [38229776795](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38229776795) | [38229776843](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38229776843) |

上述六项固定提交 CI 均为 success。TDR-026 仍为 Proposed / REVIEW_REQUIRED；未发布真实规则、运行真实 Provider／设备、合并、打 Tag 或变更验收状态。
