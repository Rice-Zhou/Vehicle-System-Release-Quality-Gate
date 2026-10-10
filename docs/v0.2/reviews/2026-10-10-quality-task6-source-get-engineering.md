# Task 6 正式来源 GET 绑定工程记录

- 日期：2026-10-10；性质：隔离合成夹具的工程验证，不是 Owner 或里程碑验收。
- 依据：[Task 6 导出记录](2026-10-10-quality-task6-readonly-report-engineering.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md) 的只读报告补充提议。

## 本段结果

现有 PostgreSQL 隔离测试在固定 `COMPLETED/BLOCK` Evaluation 后，以该 `inputSnapshot` 中的精确 `snapshotId` 和 `runId`，分别调用正式 `GET /api/v1/releases/{releaseId}/traceability` 与 `GET /api/v1/test-runs/{id}/results`。夹具保留两条 HTTP 响应解析后的 JSON 对象；Traceability/Test Repository 仍使用受控 mock，Test Run 终态响应补齐了正式契约要求的字段。未调用真实 Provider、设备或实际发布规则。

原 M1 workflow 的报告步骤现在要求两条来源响应同时存在，再交给固定 Evaluation 导出器和现有 Schema/绑定校验器。`COMPLETED` 报告保存来源响应，Release、Snapshot、Issue Snapshot、Manifest、Run、Attempt、Case、Result 摘要冲突会导致导出失败；输入固定前 `ERROR` 不查询来源，也不伪造 Quality Result。Node 测试覆盖已绑定来源、关联冲突及缺少来源响应的拒绝。

本机 `node scripts/contract-validator.mjs`、`node --test scripts/tests/demo-report.test.mjs`、目标 Node 测试、`backend/gradlew.bat compileTestKotlin --no-daemon` 与 `git diff --check` 均通过。本机没有 Docker，持久化 HTTP 集成测试由下方 M1 CI 执行。

## 固定提交 CI

中文实施 `a2a870eb57a6474ac3858403e158f667d98b7215`：[M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38034078318)、[M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38034078243)、[M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38034078163) 均成功。英文实施 `f5037bfec26b39ae910d14949bd8447163bb65e6`：[M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38034069992)、[M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38034069956)、[M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38034069967) 均成功。两次 M1 的 `Render controlled quality fixture reports` 与原演示 Artifact 上传步骤成功。`m1-demo` Artifact ID 分别为 `11663299506`、`11663114897`，API 元数据显示未过期；ZIP 成员尚未独立读取。

上述结果证明隔离合成夹具的正式 GET 与导出绑定。TDR-026 和 TDR-023 的 Task 6 补充仍待评审；真实 Release、真实设备 Evidence、规则实际发布、A1–A8 和 Owner 验收仍未完成。浏览器对本地 `file://` 报告的视觉检查仍受既有安全策略阻止。
