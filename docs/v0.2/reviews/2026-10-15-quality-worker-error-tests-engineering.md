# G1 Worker 自动 ERROR 落库与 Payload 损坏工程记录

- 日期：2026-10-15；范围：G1 测试补证及真实失败的最小修复，不是 Owner 或里程碑验收。
- 依据：[证据矩阵 G1](2026-10-15-quality-task5-task6-evidence-matrix-review.md)、[Task 5/6 计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)。起始 Subject：中文 `471f91a7a1ed7902a140d8c325b0d200a09c3c93`、英文 `8514ce5577c352d49824045553f99127a36fb3e2`；它们是补证前基线，不能当作新增测试的执行 Subject。

## 测试与实际边界

`QualityEvaluationIntegrationTest` 新增两个具名参数化场景：input digest 和 Evidence metadata 变化。reader 端口依次返回初始与变更输入；真实 Worker、封存 repository 与 PostgreSQL 负责拒绝和自动 ERROR 落库，测试不直接调用 `fail`。断言原始 `QUALITY_SOURCE_CHANGED`、`NOT_EVALUATED`、无 Input Snapshot/Rule Result/Quality Result、Job FAILED/attempt=1/终结时间，以及 Audit/Outbox 各一条对应错误。请求中的来源引用保持不变，两次 read 与 bytes 校验调用均受检查。

这里仍是明确的 reader 故障注入，不是正常 API 能改写终态 Evidence 的证明；没有关闭不可变触发器，也没有改写正式来源历史。共用断言位于测试专用 `QualityEvaluationAssertions.kt`，只查询持久化结果，不模拟失败落库。

`QualityEvaluationEvidenceIntegrationTest` 复用正式上传/Result/Traceability/Quality HTTP 夹具，在 Worker 首次验证前改动 SCREENSHOT 文件的一个 byte。经过真实 PayloadStore、Evidence reader 与 Worker 查询 ERROR，并检查同样的数据库不变量及既有决定未变；原 bytes 在 finally 恢复。之后原有“验证后外部变化、metadata 不变、seal 可成功”的窗口刻画保留；没有扩展报告入口或把新错误场景导出为演示场景。

## 真实 RED 与根因修复

新增 `QualityEvidenceReaderTest` 使用真实 `ControlledPayloadStore`、受控临时目录和已知 hello SHA-256；只有 Evidence repository/provider 为端口夹具。同长替换 HELLO、截短 bad、超长 hello! 三例先验证合法文件，再改写 bytes。生产修复前，三例均出现断言失败：期望 `QUALITY_EVIDENCE_INTEGRITY_ERROR`，实际 `PAYLOAD_INTEGRITY_ERROR`；不是 Docker 初始化错误。

根因：真实 Store.verify 通过 EvidenceConflict 抛出损坏错误；原 `ReadQualityEvidence.verify` 仅转换 IOException 或不匹配的返回值。修复只包围 `store.verify`，将它抛出的 EvidenceConflict 和 IOException 转成既有 Quality 完整性错误并保留原 cause；返回值不匹配继续拒绝。metadata/binding 校验及存储禁用检查仍在转换范围外，其他 Evidence 路由继续保留自身错误语义。测试另断言 cause 的原始存储错误码，防止诊断丢失。

未改 Worker、seal、规则求值、API Schema、数据库迁移或存储校验算法；没有新读一次 bytes，也未将验证后文件变化提升为封存自动检出。Evaluation 仍为 ERROR，只统一 Quality 边界的损坏诊断。

## 验证与证据

本地 `compileTestKotlin` 通过。数据库目标执行的三个场景均止于 Testcontainers 初始化：没有可用 Docker，不能算业务 RED 或运行通过。修复后的本地目标 XML：Reader 5/5、Worker 1/1、Store 9/10 通过；Store 剩余一项仅 Linux 适用，在 Windows 跳过。acceptance-record 与 contract 验证通过，契约统计 schemas=7、positive=22、negative=9、operations=36。独立只读复核检查了真实 Store 异常形态与转换范围，无未解决问题。

本地 RED/GREEN XML 保存于 `D:/development/Vehicle-System-Release-Quality-Gate-evidence/task5-worker-error-20261015`。数据库新用例、整个 Backend suite/build、既有报告回归仍须核实新增提交的 M1；M2/M3 为附加回归。双语分别提交推送，检查 Pair Gate、远端 exact head、六条 CI 与 M1 完整测试 XML；最终 Subject、run/artifact 和结论在本次交付回执固定，不提前将基线或待完成 CI 表述为通过。

TDR-026 仍为 Proposed / REVIEW_REQUIRED。Task 5/6 勾选、A1–A8 与验收状态不改；没有实际规则发布、设备执行、真实 Provider、merge、Tag 或部署。

## 下一步执行计划

当前结果：G1 测试已补充，真实文件 RED 已复现并作边界修复，本地目标验证通过；数据库完整路径以固定提交 CI 核实。Git 状态：双语提交、推送及 CI 以交付回执为准。下一步动作：按矩阵 G2 补未知 catalog/encoding 版本的独立拒绝回归。前置条件：后续测试实施授权及可用验证环境。验收目标：其余输入/规则合法时精确返回 `QUALITY_VERSION_UNSUPPORTED`，排除空规则或 digest 错误导致的假阳性，双语固定提交 CI 通过；不等于 Owner 验收。
