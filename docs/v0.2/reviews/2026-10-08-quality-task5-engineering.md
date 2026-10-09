# 最小质量判定 Task 5：正式输入与异步评估工程记录

日期：2026-10-08。依据：[实施计划](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md)、[TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md)、[Task 4 工程记录](2026-09-30-quality-task4-engineering.md)。本记录只报告工程证据，不代替 Owner 验收。

## 实施结果

V17 增加 Evaluation、Input Snapshot、Rule Result 和最终 Quality Result 的增量表与不可变约束。`quality:evaluate` 使用 Owner 明确的 Engineer、Quality Owner、Administrator 项目角色，并叠加 JWT scope；`quality:read` 覆盖项目成员。请求只接受 Rule Set、Traceability Snapshot 和一个 Test Run 的正式引用。已发布规则集的 required Issue 与 selected Case、锁定 Manifest、Issue/Traceability 固定快照、终态 Test Run/Result 和 Evidence 模块的元数据及 Payload 校验共同形成固定输入；客户端不能提交 facts。

Job 沿用 PostgreSQL claim、attempt count、lease 与 fencing。Worker 在只读源获取和 Evidence bytes 校验后，于短事务复读源并封闭输入；纯求值后，Rule Results、Quality Result、Audit、Outbox 与 Job 终态原子写入。源失败保留可查询的 ERROR，不生成虚假的最终 Result。查询只读持久化历史，重放由固定输入、规则/目录/引擎/编码版本确定；未知引擎版本拒绝重放。调度默认关闭，需要受控环境显式启用；本轮没有发布真实规则、启用 Company 资源、部署或操作真机。

当前规则发布门禁仍只接受 Task 4 的两条内置演示 YAML。数据库恢复演练使用隔离的 DB+Payload 副本；正式来源读取的正向测试使用模拟的各模块仓储，没有冒充真实设备端到端验收。

## 工程验证

- 单元与契约：正式来源绑定、项目权限、Evidence 跨 Run 拒绝、Worker 失败、三次新 JVM 固定输入重放、未知引擎拒绝；`node scripts/contract-validator.mjs` 为 7/7，schemas=7、positive=22、negative=9、operations=36。
- PostgreSQL CI：V17 迁移/回退恢复、幂等重复请求、唯一最终结果、事务失败回滚、重领和旧 lease 晚写、可查询 ERROR。独立 DB+Payload 副本恢复验证历史结果摘要与当前 Payload 损坏诊断分离。本机无 Docker，数据库结论以固定提交的 CI 为准。
- 固定核心修复提交 `8140fee072aa62200f5334650a73e26f4d44d2d8`（中文）/ `01c5861d79726d9a106b1f32c8e74da1caf4b991`（英文）的 M1/M2/M3 均成功。正式来源事实测试提交 `644b0ca7b16aa7d5b8e4a825bae4e5399647db5b` / `21f7d13fe4b41834b74bc361a51bed2bfe843ec6` 的 M1/M2/M3 也均成功。

| 最终测试样本修正 | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| 中文 `c889cf0` | [37722834666](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722834666) | [37722834847](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722834847) | [37722834965](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722834965) |
| 英文 `b016358` | [37722840061](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722840061) | [37722839968](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722839968) | [37722839961](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722839961) |

最终样本修正提交的中英文六项固定 CI 均为 success；非 Markdown 内容经 Pair Gate 校验一致。

## 正式来源矩阵与 API 边界复核

随后补齐正式来源反例：同 Manifest 摘要的跨 Release Run、错误 Release/摘要的锁定 Manifest、Test Result 绑定其他 Manifest、空 selectedCaseRefs、多 Run、缺 Result、缺 required Issue 与缺 Traceability 引用。既有跨项目 Traceability、跨 Run Evidence 和 appliesWhen=false 测试保留；新增 Evidence 元数据固定后 Payload 摘要不一致的拒绝测试。正向读取所得 Input Snapshot 由仓库使用的 2020-12 JSON Schema 校验器对实际产物验证。相关目标单测和 Kotlin 测试编译在本机通过。

隔离 PostgreSQL 夹具增加正式 HTTP POST → Evaluation QUEUED → GET 历史查询检查；规则版本在临时测试库由夹具插入，不调用真实发布 API。该测试覆盖 API、鉴权、项目作用域和持久化查询的前半段，不覆盖正式来源加 Worker 决策后的完整 HTTP 串联；本机无 Docker，数据库运行结果以固定提交 CI 为准。

| 反例矩阵与 API 夹具提交 | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| 中文 `dc9700a` | [37739784435](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739784435) | [37739784342](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739784342) | [37739784331](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739784331) |
| 英文 `4de90ac` | [37739790841](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739790841) | [37739790868](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739790868) | [37739790826](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739790826) |

反例矩阵提交的中英文六项固定 CI 均为 success；非 Markdown 测试文件经 Pair Gate 校验一致。

## 隔离正式来源与 Worker 串联

新增 PostgreSQL 集成夹具从正式 HTTP POST 提交出发，由真实 `FormalQualitySourceReader` 读取隔离的 Manifest、Issue、Traceability、终态 Run 仓储端口，经真实 Worker 固定快照、执行演示规则并写入最终结果，再由 HTTP GET 查询。同一测试提交缺失 Traceability 的请求，验证 Worker 将其记录为可查询 ERROR，且不生成 Quality Result。测试库按 V16 约束先插入 DRAFT 规则和规则版本，再转换为 PUBLISHED；这是一次性测试数据，不调用规则发布 API，也不修改真实规则。来源仓储和 Evidence 端口由夹具模拟，完成路径没有 Evidence bytes，因此此测试证明应用串联与持久化决策，不代表实际来源、Payload 或真机端到端验证。演示规则在该空 Issue 输入上确定性返回 BLOCK。

| 隔离决策串联提交 | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| 中文 `925187c` | [37748672488](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37748672488) | [37748672512](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37748672512) | [37748672621](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37748672621) |
| 英文 `1eee337` | [37748723866](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37748723866) | [37748723958](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37748723958) | [37748723978](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37748723978) |

上述六项固定提交 CI 均为 success，本机 Kotlin 测试编译和双语 Pair Gate 通过。本机无 Docker，PostgreSQL 运行结论取自 CI。

## 持久化 Run 与 Evidence Payload 串联

在前述隔离 API 测试基础上，新增夹具使用真实仓储生成 Release、锁定 Manifest、终态 Test Run/Result，并通过 Agent Evidence API 上传 LOG 与 SCREENSHOT bytes。正式 HTTP 请求经真实来源读取器、Evidence 元数据固定及 Payload 校验、Worker 决策后，从 HTTP 查询返回 COMPLETED/BLOCK，固定输入包含 Manifest、Issue、Traceability、Run 与两项 Evidence 引用。随后删除临时 LOG Payload，第二次评估返回可查询的 `QUALITY_EVIDENCE_INTEGRITY_ERROR`，不产生 Quality Result；历史结果仍保留。Issue 与 Traceability 仓储端口在本测试中使用固定模拟数据，不能将本测试称为这两类真实快照的端到端验证。测试夹具还补齐了锁定 Manifest 的完整验证报告，并在异常后清理本项目未完成 Job，避免影响共用测试数据库。

| 持久化来源与 Payload 夹具修正 | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| 中文 `7a84319` | [37756802003](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37756802003) | [37756801929](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37756801929) | [37756801933](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37756801933) |
| 英文 `ac7ff8d` | [37756836672](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37756836672) | [37756836730](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37756836730) | [37756836796](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37756836796) |

上述六项固定提交 CI 均为 success；本机 Kotlin 测试编译与目标来源单测成功，双语 Pair Gate 通过。实际 Issue/Traceability 仓储快照、已授权规则发布、正式来源导航与真机仍未验证。

## 实际 Issue Snapshot 来源串联

在上述隔离夹具中，用成功且完整的空 Issue Sync Run，经正式 `CreateIssueSnapshot` 服务生成 Release Issue Snapshot。质量评估的来源读取现在调用实际 `JdbcIssueSnapshotRepository.read`，由仓储复算快照摘要；不再给该端口注入固定返回值。HTTP→Worker→查询及 Evidence Payload 损坏路径保持原测试覆盖。空 Issue 集合的演示规则仍确定性返回 BLOCK。这证明 Issue 快照的持久化读取和摘要校验进入串联，不证明有 Issue 的 Verified 决策。Traceability Snapshot 的仓储端口仍由夹具模拟。

首轮测试提交中文 `7aae2ea`、英文 `a11ab27`；英文 M1 [37873347998](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37873347998) 的注解指出夹具请求摘要格式无效。修正提交以 Release 与 Source 引用计算 SHA-256；本机 `compileTestKotlin` 与双语 Pair Gate 成功，Docker 不可用，PostgreSQL 运行结果以修正提交的 CI 为准。

| 请求摘要修正提交 | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| 中文 `42bc900` | [37874237641](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37874237641) | [37874237688](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37874237688) | [37874237731](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37874237731) |
| 英文 `76b9d64` | [37874224562](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37874224562) | [37874224652](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37874224652) | [37874224638](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37874224638) |

修正提交的中英文 M1/M2/M3 六项固定 CI 均为 success；该结果只证明隔离夹具中的空 Issue Snapshot 串联。

测试数据只在临时数据库生成，未调用规则发布 API。

## 有成员 Issue 与实际 Traceability Snapshot 串联

隔离 PostgreSQL 夹具现在于同一 Release 创建带一条有效观察记录的 Issue Snapshot。`CreateIssueSnapshot` 通过正式仓储校验观察项和事实摘要；`StartTraceabilityVerification` 固定该快照与锁定 Manifest，正式 Worker 生成未 Verified 的 Traceability Snapshot，并记录 `ISSUE_COMMIT_MISSING`。质量 HTTP→来源读取器→Worker→HTTP 查询返回 COMPLETED/BLOCK，固定输入的 Issue/Traceability ID 与摘要对应实际仓储记录；Issue facts 包含 required=true、verified=false 和缺口代码。BLOCK 来自当前演示规则对非空 Issue 集合的判断，本测试不把它解释为“未 Verified”专用规则的验收。真实 Run、Evidence bytes 与 Payload 损坏后可查询 ERROR 的覆盖保留。共享 Run 夹具在锁定 Manifest 前写入其声明的 APK/CONFIG Artifact 关系。

首轮测试提交中文 `b55c2f4`、英文 `e012271` 的 M1 因夹具向终态 Issue Sync Run 追加观察项而失败（[中文](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37877251836)、[英文](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37877298101)）。修正后按 RUNNING→观察项→SUCCEEDED 写入，双语 Pair Gate 与本机 `compileTestKotlin` 成功；本机无 Docker，数据库结论以下列固定提交 CI 为准。

| Sync 状态修正提交 | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| 中文 `205a9a6` | [37878166648](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37878166648) | [37878166574](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37878166574) | [37878166594](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37878166594) |
| 英文 `28b4628` | [37878120577](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37878120577) | [37878120528](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37878120528) | [37878120523](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37878120523) |

六项修正提交 CI 均为 success。规则版本只在临时测试库构造为 PUBLISHED；未调用发布 API，也没有发布真实规则或启用外部资源。

## 剩余边界与下一步

实际 Issue/Traceability 到完成/错误决策的隔离 API 串联已完成；正式来源导航界面和 Task 6 只读展示仍未实现。发布规则受当前项目约束禁止，本轮未调用发布端点或变更真实规则状态；实际发布须等待单独授权和隔离环境。真机相关证据和 Owner 验收均保持独立。TDR-026 仍为 Proposed / REVIEW_REQUIRED。

当前结果：Task 5 隔离 API 已串联实际 Manifest、Issue、Traceability、Run 和 Evidence，并固定来源引用与未 Verified 缺口；不代替规则发布或 Owner 验收。Git 状态：以对应中英文分支和固定 CI 为准。下一步动作：只读核查 Task 6 报告对 Quality Result、来源引用与 Evidence 导航的映射，形成可执行实施顺序。前置条件：核查无需额外权限；真实规则发布与真机执行仍需单独授权。验收目标：逐字段确认只读报告能从正式查询定位同 Release 的决定和证据，不重新计算质量结果。
