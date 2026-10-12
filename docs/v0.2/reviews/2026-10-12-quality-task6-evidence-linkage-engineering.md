# Task 6 同一 Evaluation 的 Evidence 定位工程记录

- 日期：2026-10-12；性质：隔离夹具工程验证，不是 Owner 或里程碑验收。
- 依据：[TDR-026 Task 6 补充提议](../tdr/TDR-026-minimal-quality-evaluation.md)、[Evidence 元数据定位记录](2026-10-10-quality-task6-evidence-metadata-engineering.md)。

## 实施与证据

固定质量决策的 PostgreSQL 集成测试现在使用一条非空 Evidence 引用。受控 `EvidenceSession` 经正式 `ReadQualityEvidence` 固定到同一 Evaluation 的 `inputSnapshot.evidenceRefs`；测试再以相同项目身份调用正式 `GET /api/v1/evidence/{evidenceId}`，核对 Evidence ID、Run/Attempt、Payload 摘要和 `integrity=VERIFIED`。仓库和 Payload 校验由本测试的合成夹具提供，不代表实际上传或真机采集。

CI 夹具包单独保存该 GET 的元数据响应。报告生成前，夹具脚本核对质量输入、Test Result 和 Evidence 元数据的 ID、Run/Attempt、类型、摘要、大小、可用状态及完整性；缺失或冲突时失败。元数据不进入 `quality-report-export.json`，双语静态报告仅展示固定 Evidence 定位信息，不请求下载授权或 Payload，也不生成 `grantId`、`/payload` 链接。Node 测试覆盖四项身份/摘要冲突、缺失元数据与双语输出。

本机目标 Node 测试、契约校验、`backend/gradlew.bat compileTestKotlin --no-daemon` 和 `git diff --check` 通过。本机无 Docker；固定提交 M1 的 Backend gate 在 CI 运行 PostgreSQL 集成测试，随后执行受控报告渲染。已有 [Evidence 元数据定位记录](2026-10-10-quality-task6-evidence-metadata-engineering.md) 分别验证了实际受控 Payload 的 GET 完整性观察、权限及损坏路径；本段验证同一 Evaluation 的定位关联，尚未把实际上传串进质量决策。

## 固定提交 CI

中文实施 `bea0b7abcaf892f1e105840657990f31409df27c`：[M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38196858830)、[M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38196858806)、[M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38196858807) 均成功。英文实施 `6bc57b7fb3f64f7ced1e3078e2015b69bb780134`：[M1](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38196859090)、[M2](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38196859076)、[M3](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38196859181) 均成功。两条 M1 的 Backend gate 与受控报告渲染步骤均成功。

TDR-026 与 TDR-023 的 Task 6 补充仍待评审。真实上传到同一 Evaluation 的链路、四类质量决定、真实规则发布、真机 Evidence、A1–A8 和 Owner 验收仍未完成。
