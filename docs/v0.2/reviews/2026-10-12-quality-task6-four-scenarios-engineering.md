# Task 6 四场景受控质量决定工程记录

- 日期：2026-10-12；性质：隔离环境工程验证，不是 Owner 或里程碑验收。
- 依据：[Task 6 实施计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[TDR-026 提议](../tdr/TDR-026-minimal-quality-evaluation.md)、[受控上传串联记录](2026-10-12-quality-task6-upload-report-engineering.md)。

## 固定场景与来源

`QualityEvaluationEvidenceIntegrationTest` 在同一合成 Release 下使用不同的正式 Test Run。测试 Agent 经现有 HTTP 路由上传受控 LOG/SCREENSHOT，Test Result 与 Traceability Job 固定正式来源；Quality Job 返回精确 Evaluation ID，正式查询响应进入独立的报告导出。两条演示规则仅作为隔离测试数据库中的合成 `PUBLISHED` 夹具，不代表通过发布门禁发布了实际规则。

| 场景 | 固定输入 | 预期正式结果 |
| --- | --- | --- |
| 正常 Smoke | 显式空 `requiredIssueRefs`，PASS Result，LOG 和 SCREENSHOT | `COMPLETED`，限定 `PASS` |
| 确定 FAIL | 同 Release 的另一 Run，FAIL Result，LOG 和 SCREENSHOT | `COMPLETED`，`BLOCK` |
| 必需 Issue 未 Verified | 正式 Issue/Traceability Snapshot，PASS Result，LOG 和 SCREENSHOT | `COMPLETED`，`BLOCK`；固定输入中的 Issue 保持未 Verified |
| 缺必需 Evidence | ERROR Result 只带 LOG，SCREENSHOT 要求为 FAILED | `ERROR`，`QUALITY_REQUIRED_EVIDENCE_MISSING`，无 Quality Result 或已固定输入 |

CI 捕获这些 Evaluation、相应 Traceability/Test 查询及完成场景的 Evidence 元数据响应，验证来源 ID、Run/Attempt、摘要与完整性后，逐场景生成中英文只读报告。缺 Evidence 的预固定 ERROR 保留原错误码，不查询不存在的固定来源。先前的 Payload 损坏 `QUALITY_EVIDENCE_INTEGRITY_ERROR` 路径仍保留。Node 测试还拒绝四场景决定不符，并检查双语产物。

## 验证与边界

本机 `compileTestKotlin`、`QualityRuleGoldenTest`、`verify-contracts.tests.ps1`、目标报告测试和 `git diff --check` 通过。本机无 Docker；PostgreSQL 集成执行及报告产物以固定提交的 M1 CI 为准。中文实施 Subject `89c642bb668c730c7a0a71038c0aa46da0cbe52b`，英文实施 Subject `2604ba6c6b77535ebe2a58b89be9f61f7e0eb4cb`。CI：[中文 M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38208396168)、[M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38208396126)、[M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38208396266)；[英文 M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38208404011)、[M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38208403896)、[M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38208403885)，六条均成功。两条 M1 的 Backend gate、受控报告渲染和产物上传步骤均成功。

本记录补足计划中的四个受控样例，不关闭 A1–A8：A1 的同 APK 跨 Release 混绑反例、A5 的未采集 Crash/ANR，以及 A2/A4/A8 的真机与完整导航证据仍需单独核查。合成规则集、受控上传和静态报告不能替代实际规则发布、真实 Provider/设备 Evidence 或 Owner 验收。TDR-026 保持 Proposed / REVIEW_REQUIRED。
