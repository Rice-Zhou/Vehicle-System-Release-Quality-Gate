# A1 同 APK 跨 Release 输入绑定工程记录

- 日期：2026-10-12；性质：隔离 PostgreSQL 工程验证，不是 A1 的 Owner 验收。
- 依据：[A1 范围](../../superpowers/specs/2026-09-15-minimal-quality-evaluation-scope-draft.md)、[Task 5 实施计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)与[四场景工程记录](2026-10-12-quality-task6-four-scenarios-engineering.md)。

## 精确反例

先前 `QualityFormalSourceTest` 仅将模拟 Run 的 `releaseId` 换成另一个值，未建立两个真实 Release 或共享 APK。新的 `QualityEvaluationEvidenceIntegrationTest` 在现有受控链路中建立同项目的 Release A/B、各自锁定的 Manifest 与实际共享的 APK `artifact_id`。Release B 的正式 Run、PASS Result、LOG 和 SCREENSHOT 均独立完成；Release A 的 Traceability、规则集和历史 Evaluation 保留。

以 Release A 的正式 `POST /quality-evaluations` 请求引用 Release B 的 Run，Worker 通过正式来源读取后将该 Evaluation 置为 `ERROR`，错误码为 `QUALITY_TEST_RUN_INVALID`，且没有 Input Snapshot 或 Quality Result。测试同时核对两个 Release 的锁定 Manifest/摘要/状态、共享 APK 关联、Release B 的 Test Result 和 Release A 原有质量决定在拒绝前后保持一致。错误 Evaluation 本身作为可查询历史保留。

## 证据边界

本机 Kotlin 测试编译与 `git diff --check` 通过；本机无 Docker，PostgreSQL 执行以固定提交 M1 CI 为准。中文实施 Subject `f060ec2f8a2b4ebda68639dbbbf503addf8d69d4`，英文实施 Subject `802773653042e6f1015ba838f0fcf04c80082301`。CI：[中文 M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38211141412)、[M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38211141471)、[M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38211141368)；[英文 M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38211154316)、[M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38211154417)、[M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38211154317)，六项均成功。两条 M1 的 Backend gate 与受控报告渲染步骤均成功。

本测试补齐“同 APK、不同 Release”反例的工程证据，不把其他 A1 输入组合、真实设备、实际规则发布或 Owner 验收标为通过。TDR-026 保持 Proposed / REVIEW_REQUIRED。
