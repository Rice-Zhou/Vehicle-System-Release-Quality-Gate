# G2 未知 catalog/encoding 版本拒绝回归工程记录

- 日期：2026-10-15；范围：补齐版本拒绝的独立回归证据，不是 Owner 或里程碑验收。
- 依据：[证据矩阵 G2](2026-10-15-quality-task5-task6-evidence-matrix-review.md)、[Task 5 计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)。起始 Subject：中文 `52c0b801d7b6422807f2e6154aff49e1399ecc4c`、英文 `c3d2863dcf9baacfa50f7d411f55293192bfcb20`；这些是修改前基线，不是新增测试的执行 Subject。

## 独立场景与合法前提

仅在 `QualityReplayTest` 新增两个具名参数化场景：

| 字段 | 未知值 | 预期 |
| --- | --- | --- |
| `catalogVersion` | `999` | `QualityInputFailure.code = QUALITY_VERSION_UNSUPPORTED` |
| `canonicalizationVersion` | `UNAVAILABLE-ENCODING` | `QualityInputFailure.code = QUALITY_VERSION_UNSUPPORTED` |

每个场景先以受支持版本的 Input Snapshot 与非空规则列表运行真实 `QualityDecisionRunner`，断言无 errorCode、Quality Result 为 BLOCK，且唯一 Rule Result 为 BLOCK。沿用的结构夹具包含 Issue，规则条件为 exists issues[]，因此 BLOCK 是合法求值结果，不是实际规则发布或真实 Issue 验证。

随后深拷贝该输入，只改本场景版本字段；其他版本、facts、引用及规则保持不变。从摘要材料排除 snapshotId/inputDigest，并用既有规范编码重算 inputDigest，再以同一非空合法规则列表断言确切异常码。这样不能由空规则、旧 Engine、坏 AST 或陈旧摘要产生的其他异常误判成功。重算摘要仅用于隔离版本检查，不实现未知编码器或历史解释器。

若当前解释器忽略未知版本继续求值，assertThrows 将失败；若报错为输入摘要或规则错误，精确 code 断言将失败。现有未知 Engine、无适用规则和三次新 JVM 重放场景未改写；本次没有扩大它们的证据结论。

## 验证与交付边界

新增场景直接通过，没有观察到业务失败，因此未修改生产代码。中文本地目标结果：QualityReplayTest 6、QualityRuleGoldenTest 3、RuleOperatorMatrixTest 17，合计 26/26 通过，无 failure/error/skipped；assemble、acceptance-record 与 contract 检查通过。契约统计 schemas=7、positive=22、negative=9、operations=36。英文工作区复用同一非 Markdown 测试内容，执行相同目标检查。

本地 XML 与日志保存于 `D:/development/Vehicle-System-Release-Quality-Gate-evidence/task5-version-rejection-20261015`。双语分别提交推送后，核查 Pair Gate、远端提交和 exact-head M1/M2/M3；M1 完整测试 XML 须出现两个具名拒绝用例，不能只凭 workflow 总状态确认执行。最终 Subject、run/artifact、XML 摘要与 CI 结论在交付回执固定；不提前把待完成 CI 表述为通过。

本轮不修改规则、Catalog/encoding 定义、生产解释器、API、数据库、报告入口、计划勾选或验收状态。TDR-026 仍为 Proposed / REVIEW_REQUIRED；没有实际规则发布、设备执行、真实 Provider、merge、Tag 或部署。

## 下一步执行计划

当前结果：G2 独立拒绝回归已补齐并通过本地目标检查，没有生产修复。Git 状态：双语提交推送及固定提交 CI 以交付回执核实。下一步动作：按矩阵 G3 衔接同一正式来源 Evaluation 的独立 DB+Payload 恢复补证。前置条件：后续实施授权与可用 PostgreSQL/CI 环境。验收目标：从独立恢复库读取该 Evaluation 的固定 Snapshot 和规则 AST，在三个新 JVM 与其已保存结果摘要比较；当前 Payload 完整性与历史决定重放分开验证，原演示数据库不受影响；不等于 Owner 验收。
