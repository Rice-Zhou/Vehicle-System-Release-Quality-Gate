# G3 正式来源 Evaluation 独立恢复补证工程记录

- 日期：2026-10-15；范围：同一正式来源 Evaluation 的恢复与重放补证，不是 Owner 或里程碑验收。
- 依据：[证据矩阵 G3](2026-10-15-quality-task5-task6-evidence-matrix-review.md)、[Task 5 计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)。起始 Subject：中文 `a54c43912061ef25311ff21a007bd3c820765b90`、英文 `40e516228e6cc0faaf334ac67de6b2c704654e0f`；它们是修改前基线，不是新增断言的执行 Subject。

## 同一 Evaluation 的恢复链

`QualityEvaluationEvidenceIntegrationTest` 继续使用真实应用读取端口与正式 API/持久化链：Locked Manifest、Issue Snapshot、Traceability Snapshot、终态 Test Run/Attempt/Result、LOG 与 SCREENSHOT 上传，再提交并完成固定 Evaluation。来源 reader 不 mock。来源类型仍是隔离 SYNTHETIC_FIXTURE，不是真机、真实 Provider 或实际规则发布。

将原来从原库直接执行的三个新 JVM 重放，接到该条 Evaluation 的独立恢复上。新增测试辅助 `QualityRecoveryProbe` 提取已有 `QualityEvaluationRecoveryIntegrationTest` 的恢复步骤，两夹具复用 `pg_dump`、`createdb`、`pg_restore` 和普通文件复制；在既有 Testcontainers PostgreSQL 内创建不同名称的数据库，Payload 使用另一个测试独占目录，不使用链接或原目录。断言实际数据库名称与原库不同；结束时删除新库、dump 与复制文件。

恢复库按同一 evaluationId 查询 Evaluation、固定 Input Snapshot 与已保存 Quality Result，并与原库/API 内容精确比较。规则从恢复 Evaluation 的 rule_set_version_id 读取，全部记录与原库选中记录比较；本夹具全部选中规则只有 REQUIRED_ISSUE_VERIFIED v1，使用 single() 显式限定，不声称覆盖任意多规则恢复。三个新 JVM 的 Snapshot 与规则 AST 文件均来自恢复库查询结果，三个摘要逐一匹配恢复库保存的 resultDigest，该保存值又与原条 Quality Result 相同；同进程真实 QualityDecisionRunner 再以完整规则记录核对。

## 当前完整性与历史决定分离

恢复后先要求两个 Evidence 均为 VERIFIED。随后仅删除恢复副本中的一个 Payload，精确断言该 Evidence 为 INTEGRITY_ERROR，另一项仍为 VERIFIED。文件缺失之后再运行三个新 JVM；固定 Snapshot/AST 的历史重放摘要仍与原已保存结果相同，恢复库的 Quality Result 与 COMPLETED Evaluation 不变。

恢复验证只在恢复库写完整性观察。结束后复核原库 Evaluation、Input Snapshot、Quality Result、全部选中规则、两个 Evidence Session 与完整性观察数量未变，原目录两个 Payload 的实际 bytes 仍通过既有 PayloadStore 验证；随后原 HTTP 查询内容也保持一致。原演示数据库不参与此测试。

TDR-026 的窗口语义保持不变：历史 inputDigest/resultDigest 不证明当前 Payload 完整。此测试没有把 bytes 验证移入 seal 事务，没有增强为文件系统与 DB 原子提交、WORM 或管理员防篡改。DB dump 覆盖隔离库，Payload inventory 只覆盖该 Evaluation 固定的两个引用；不将它表述为整库所有 Evidence 的完整恢复。

## 验证与交付边界

本地中英文均通过 compileTestKotlin、QualityReplayTest 6、QualityRuleGoldenTest 3、RuleOperatorMatrixTest 17，合计 26/26，无 failure/error/skipped；assemble 与 acceptance-record/contract 检查通过，契约统计 schemas=7、positive=22、negative=9、operations=36。本机无 Docker，未运行 PostgreSQL 集成测试；编译和纯测试通过不代表恢复断言已执行。未观察到真实产品失败，未修改生产代码。

提交推送后，以 exact-head M1/M2/M3 和 M1 完整测试 XML 核实两个集成测试实际执行，检查 `QUALITY_FORMAL_RECOVERY` 输出包含同一 evaluationId、snapshotId、inputDigest/resultDigest、restoredRuleCount=1、newJvmCount=3、verifiedPayloadCount=2、missingCopyIntegrity=INTEGRITY_ERROR 与 originalUnchanged=true。该输出只在全部恢复与原库保护断言成功后产生，不扩展演示报告导出入口。双语最终 Subject、run/artifact、XML、夹具固定 ID 及摘要核对保存在交付回执 `D:/development/Vehicle-System-Release-Quality-Gate-evidence/task5-formal-recovery-20261015`；不提前把待完成 CI 写成通过。

独立只读复审未发现必须修复的问题。本轮只改测试与本记录；不改计划勾选、验收记录、规则、API、Schema、报告入口或 TDR 状态。V0.2 保持 Draft，TDR-026 保持 Proposed / REVIEW_REQUIRED；无实际规则发布、设备执行、真实 Provider、Company 资源、merge、Tag 或部署。

## 下一步执行计划

当前结果：G3 同一正式来源 Evaluation 的恢复断言已补齐，本地目标检查通过；恢复实跑及双语固定提交 CI 以交付回执为准。Git 状态：双语提交推送、Pair Gate 与远端 Subject 以交付回执核实。下一步动作：按矩阵 G4，在另次授权后补受控配置演示入口与运行手册。前置条件：另次实施授权、经确认的受控配置与可用隔离 PostgreSQL/CI 环境；真机阶段另需设备授权。验收目标：复用现有 API、导出器、renderer 与 M1，以新 Run 和固定 Evaluation ID 留存四场景及逐字段报告、双语完整页面/窄屏与授权 Evidence 定位访问证据；未执行的真实环境项目保持 UNKNOWN，不替 Owner 验收。
