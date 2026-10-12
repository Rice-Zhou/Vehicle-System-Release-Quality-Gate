# Task 6 受控上传到质量报告串联工程记录

- 日期：2026-10-12；性质：隔离环境工程验证，不是 Owner 或里程碑验收。
- 依据：[TDR-026 Task 6 补充提议](../tdr/TDR-026-minimal-quality-evaluation.md)、[同一 Evaluation 的 Evidence 定位记录](2026-10-12-quality-task6-evidence-linkage-engineering.md)。

## 验证链路

沿用现有 PostgreSQL `QualityEvaluationEvidenceIntegrationTest`：测试 Agent 将受控 LOG 与 SCREENSHOT Payload 实际上传并完成，正式 Test Result 固定两份 Evidence，Traceability Job 和 Quality Job 生成正式快照与 `COMPLETED` Evaluation。新增的检查以同一项目身份调用固定 ID 的 Traceability、Test Result 和两份 Evidence 元数据 GET，逐项核对质量输入中的 Evidence ID、Run/Attempt、摘要及 `integrity=VERIFIED`，并检查两条完整性观察。随后删除测试专属 LOG Payload，保留原有第二次 Evaluation 的 `QUALITY_EVIDENCE_INTEGRITY_ERROR` 断言。

M1 CI 保存上述同一链路的受控 HTTP 响应证据包。现有报告脚本以相同的身份、来源和冲突检查处理该证据包，生成 `COMPLETED` 与 `ERROR` 的中英文静态报告及导出 JSON。测试覆盖摘要冲突拒绝、报告保留 Evidence 定位符且不含元数据响应、`grantId` 或 `/payload` 链接。没有请求下载授权、读取 Payload 到报告、启用真实 Provider 或设备。

本机目标 Node 测试、全契约校验、Kotlin 测试编译及 `git diff --check` 通过。本机无 Docker，PostgreSQL 执行以固定提交 M1 CI 为准。本段使用真实受控上传路径与文件存储，但仍是合成测试环境和合成规则集；不能替代真机 Evidence、实际规则发布或 Owner 验收。

## 固定提交 CI

中文实施 `c04596edd43bdd747e356ee76e97f4d2aa2e8c94`：[M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38198677996)、[M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38198677773)、[M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38198678202) 均成功。英文实施 `ca00dda62e6f82178c8c75867dee62d9809c72fa`：[M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38198677591)、[M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38198677615)、[M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38198677598) 均成功。两条 M1 的 Backend gate、受控报告渲染及 Demo Artifact 上传步骤均成功。

TDR-026 与 TDR-023 的 Task 6 补充仍为待评审提议；四类实际质量决定、真机 Evidence、A1–A8、真实规则发布及 Owner 验收仍未完成。
