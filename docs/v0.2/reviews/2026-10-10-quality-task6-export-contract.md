# Task 6 导出契约首段工程记录

- 日期：2026-10-10；性质：Schema、只读输入校验与正反测试，不是 Task 6 完成或 Owner 验收。
- 依据：[实施计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[技术复核](2026-10-10-quality-task6-report-technical-review.md)、TDR-026/TDR-023 的待评审补充。

## 已实现边界

`quality-report-export.schema.json` 只接受终态 `COMPLETED` 或 `ERROR` Evaluation，以及可选的正式 Traceability/Test 查询响应。`ERROR` 在输入固定前不得携带来源响应，保留 `NOT_EVALUATED`，不制造 Quality Result。顶层与来源字段按现有 Quality Schema/OpenAPI 校验；`quality-report-export.mjs` 另核对固定 Evaluation ID、Release/Project、Snapshot/Manifest/Issue Snapshot 引用、所选 Run/Case/Attempt/Result 摘要与状态。Issue Snapshot 摘要没有第二个来源，仍只保留在固定输入中。

导出字节上限独立定为 16 MiB，恰好上限可读，超一字节拒绝；此限额是当前 4 MiB 质量输入与最多 2000 条追溯边的受控预算，后续须用实际留存 CI 响应检验余量。来源标记仅支持 `UNKNOWN` 或附受控 fixture ID 的 `SYNTHETIC_FIXTURE`；当前校验器不证明 fixture ID 的外部出处，后续导出入口负责限定其来源。未增加报告 CLI、HTML、API 调用、规则求值或真实演示。

## 验证与未完成项

正向覆盖 PASS/WARNING/BLOCK、固定前与固定后 ERROR；反向覆盖未知字段、凭据字段、排队态、来源冲突、选择冲突、非法 UTF-8 与大小边界。`node scripts/contract-validator.mjs` 和 `node --test scripts/tests/demo-report.test.mjs` 通过。样例以现有机器契约构造，尚不是从 Task 5 CI 留存的完整导出；因此不能据此宣称真实来源采集、分页精确 ID、HTML 安全、Evidence 导航或 A1–A8 验收已完成。下一段须先保存受控 CI 响应，再实现精确 ID 的导出与报告投影。
