# Quality 事务窗口直接测试补证

- 日期：2026-10-15；性质：正式来源集成夹具中的事务边界测试，不是 Owner 验收。
- 依据：[封存边界测试](2026-10-15-quality-sealing-boundary-tests-engineering.md)、[Task 5/6 计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)和 [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)。只在真实行为失败后修复；不修改窗口语义或验收状态。

## 观察位置与断言

扩展既有 [QualityEvaluationEvidenceIntegrationTest](../../../backend/src/test/kotlin/com/ricezhou/vsrqg/quality/QualityEvaluationEvidenceIntegrationTest.kt) 的同一条正式来源 HTTP→Worker→持久化查询测试，不复制准备代码。首个正常评估期间，测试使用真实 Bean 的 spy 观察调用，所有观察点继续 callRealMethod，不替换来源内容、Payload bytes、SQL 或事务管理器。

| 观察位置 | 直接断言 |
| --- | --- |
| 正式来源读取中的 Evidence pin | 活动事务与同步均存在，初次读取为只读 REPEATABLE_READ；绑定的真实 JDBC Connection 关闭 autoCommit，隔离级别为 REPEATABLE_READ。提交完成事件必须出现在 bytes 校验之前。 |
| 两项 PayloadStore.verify | 两个已上传 Session 都调用真实文件摘要校验；活动事务、同步、隔离级别与绑定的 ConnectionHolder 均不存在。恰好两次校验，封存期间不再读 bytes。 |
| 封存复核与 Snapshot INSERT update | 复核中的 pin 和真正执行 INSERT 的 StatementSpec.update 均在可写 REPEATABLE_READ 事务内，关闭 autoCommit；二者共享同一个 ConnectionHolder，与初读的资源不同。第二次提交完成必须在 INSERT 之后。 |

事件序列精确要求初读、初读提交、两次 bytes 校验、元数据复核、INSERT、封存提交。测试在 Worker 返回后检查活动事务及资源已清理；既有查询继续确认同一 Evaluation 的 Snapshot 和 Quality Result 已持久化。只匹配 Snapshot INSERT 以定位实际数据库写入，不以源码文本或 SQL 字符串存在代替执行断言。真实 StatementSpec 的 param 链保持，update 探针继续执行真实 SQL。

观察仅覆盖该正常评估，finally 重置 spy；原有跨 Release 拒绝、三个新 JVM 重放、校验后外部文件变化刻画及删除 Payload 后的可查询 ERROR 路径继续执行。后续窗口刻画仍允许元数据未变时 seal 成功，恢复文件后由夹具主动 fail，不宣称 Worker 自动发现校验后的外部变化。

## 验证与证据边界

双语 compileTestKotlin 通过；本机目标测试在 Testcontainers 初始化时因 Could not find a valid Docker environment 失败，原始 XML 为一个初始化失败节点，尚未进入行为断言。日志与 XML 保存在本机证据目录 `task5-transaction-window-20261015`，不将其计为行为红灯或成功。独立只读复审未发现 Critical、Important 或 Minor 问题。

准确提交 CI 必须运行该已扩展用例并保留 M1 full-test-results XML，核对测试名、零失败、零错误和零跳过，同时检查既有封存边界类和 M1/M2/M3。首次实际执行若直接通过，即为既有行为补证，不虚构红绿修复。若有真实业务失败，保留输出后诊断并最小修复。本记录的远端提交、Pair Gate 和 CI 在提交后另行核实，不提前登记成功。

本补证不测量事务时长，不给出 Company SLO，不提供 WORM、管理员防篡改或文件与数据库原子性保证。TDR-026 保持 Proposed / REVIEW_REQUIRED；Task 5/6 复合条款、Owner 验收状态及生产代码不变。没有规则发布、真实 Provider、Company 资源或设备操作。

当前结果：已在现有正式来源集成夹具中加入事务状态、资源身份、提交顺序和实际 INSERT 的直接断言，运行结论等待准确提交 CI。Git 状态：以包含本记录的中英文提交及远端核对为准。下一步动作：只读归并 Task 5/6 复合条款与 A1–A8 的证据覆盖和剩余缺口。前置条件：本轮准确提交 CI 与 XML 核验完成；已有仓库和 CI 只读权限。验收目标：每项对应固定 Subject、代码/测试和 CI 证据，未覆盖项明确列出，不修改计划勾选或 Owner 验收状态。
