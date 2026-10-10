# Task 6 固定 Evaluation 导出与只读报告工程记录

- 日期：2026-10-10；性质：Task 6 后续工程切片，不是里程碑或 Owner 验收。
- 基线：[导出契约](2026-10-10-quality-task6-export-contract.md)、[实施计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、TDR-026/TDR-023 待评审补充。

## 本段实现

`buildQualityReportExport` 遍历同 Release 历史全部分页，只选择指定 `evaluationId`；缺失、重复、跨 Release、游标循环和来源绑定冲突均失败。固定输入前 `ERROR` 保留 `NOT_EVALUATED` 与原错误，不查询不存在的来源。固定输入后可注入精确 Traceability/Test 查询响应，仍由上一段 Schema 与绑定校验审查；没有响应时导出明确保留缺口，不伪称导航已验证。来源分类默认 `UNKNOWN`，普通调用者不能自行标记合成证明。

`render-report.mjs --scope quality` 只读 `quality-report-export.json`，双语纯 HTML 展示决定或错误、固定 ID/版本/摘要、规则结果、未覆盖项和 Evidence 定位。动态文本统一转义，CSP 禁止脚本和网络；不下载 Evidence、不执行规则、不选择最新记录。`m1|m2` 沿用原输入与上限，quality 校验器只在显式选择该 scope 时加载。输出仍使用独占创建。

现有 M1 隔离集成测试将 HTTP 查询的 `COMPLETED/BLOCK` 和固定前 `ERROR` 原响应写入带提交 ID 的合成夹具。现有 M1 workflow 随后验证夹具身份，生成两种语言的离线报告并上传到原有 30 天 Artifact；不增加服务或真实 Provider。该测试的 Traceability/Test 端口使用隔离夹具，当前 Artifact 不含这两个正式 GET 响应，页面须显示未查询。此切片也不证明 PASS/WARNING、真实设备 Evidence 或规则发布。

## 验证与边界

本机 `node scripts/contract-validator.mjs`、`node --test scripts/tests/demo-report.test.mjs`、`node scripts/acceptance-record-validator.mjs`、workflow YAML 解析和 `backend` 的 `compileTestKotlin` 通过。Windows 无法创建符号链接，因此该拒绝案例由 Linux CI 执行。本机无 Docker；持久化 HTTP 夹具产出、CI Artifact 和浏览器外观检查须以固定提交的后续证据核对。TDR 仍待正式评审，A1–A8 与 Owner 验收未关闭。
