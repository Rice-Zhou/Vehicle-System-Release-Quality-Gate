# TDR-019 — 版本化 Evidence Archive 工作包身份

- 状态：Accepted；任务 3 实施与本地验证完成，最终复审与 CI 通过，Owner 验收待定。
- 日期：2026-09-07
- 范围：在 TDR-012 的同一窄 JVM operation 中支持独立 M2.5 工作包。
- 依据：[TDR-012](TDR-012-evidence-archive-acceptance-operations.md)、[TDR-013](TDR-013-controlled-local-file-identity.md)、[已提交准备包](../../../ops/evidence-archive/m2-5-preparation/README.md)。
- 当前授权：[书面评审记录](../../governance/acceptance/records/2026-09-07-tdr-019-written-review-001.md)保留方案批准；任务 1、2 已完成，见[任务 1 记录](../../m2/2026-09-07-evidence-archive-identity-task1.md)与[任务 2 记录](../../m2/2026-09-07-evidence-archive-identity-task2.md)。Owner 本轮授权任务 3，原始指令见[任务 3 记录](../../m2/2026-09-07-evidence-archive-identity-task3.md)。Company 归档和新的 Owner 验收决定不在本轮授权内。

## 问题与可验证现状

原 M2.5 实施已获 `M2-5-OWNER-GATE-001` 的 APPROVE。两份原 ZIP 已本地保全；准备清单仍是本地材料，不是可执行 descriptor。最早在线 Artifact 到期为 `2026-10-07T02:45:31Z`。

方案提交时的代码核查发现，三个 JSON Schema、单一 Kotlin descriptor parser、恢复报告及 provisional/failure 输出、operation 安全摘要、Node 离线校验器均存在固定 M1 ID。任务 1 已解决输入与离线契约部分；任务 2 已完成运行时报告和摘要贯穿及独立复审，任务 3 已固定正式输入与真实 JVM→Node 样本，本地验证通过，最终复审与 CI 均通过。仅放宽 descriptor 而未完成后续任务，不代表 M2.5 工具可交付。

SourceVerifier 已按 descriptor 校验 ZIP size/SHA-256、ZIP32 结构、清单原始摘要及两个 Pilot 分类字段，并未按 M1 文件名解释 ZIP 内部业务 summary。本扩展复用这些检查；不新增第二个 ZIP 读取器、业务 Evidence parser 或质量验收权威。准备阶段的 summary/sidecar 核验继续作为固定输入来源证据。

## 备选方案与推荐

| 方案 | 收益 | 代价与结论 |
|---|---|---|
| 重用 M1 ID 或复制一套 M2.5 执行器 | 改动表面较少 | 混淆历史工作包或复制安全判断；拒绝 |
| 任意 ID、任意数量的通用工作包框架 | 可用于未知未来工作包 | 扩大输入域、配置和测试面；当前只有两个工作包，不采用 |
| 两个显式版本/ID 配对，贯穿既有执行链 | 保持单一执行路径、有限测试矩阵和历史兼容 | 需要同步 parser、report、Schema 与离线校验；推荐 |

推荐只增加已知 M2.5 profile，不建立注册服务、数据库、动态插件、Provider 实现或新管理 API。属于操作层技术决定，不修改冻结 Core Contract、Archive facade/Port/Receipt、Capability、Manifest authority 或 ADR 治理。

## 输入与版本决定

| 用途 | schemaVersion | workPackageId |
|---|---:|---|
| 既有 M1 descriptor 与绑定报告 | 1 | `V0-2-EVIDENCE-ARCHIVE-001` |
| 新 M2.5 descriptor 与绑定报告 | 2 | `M2-5-EVIDENCE-ARCHIVE-001` |

只接受上述精确配对；拒绝未知 ID、未知版本、版本/ID 对调。两个 profile 均保留两份 Artifact、既有字段、边界限制、禁止字段检查和 `LOCAL_PILOT_NOT_IMMUTABLE`、`conditionBClosed=false`。不为新 ID 放宽 size、摘要、文件名、路径、重复键或身份检查。

新增 descriptor 已由任务 3 在 `ops/evidence-archive/m2-5-evidence-archive-001.json` create-only 创建。subjectCommit 和 pairedSubjectCommit 绑定原实施 `3b010726941c26f0b4096cea34ea4b4dd80c5283`、`de49b2af6ddf1e5f529453e2714366064c873e15`；两个 Artifact 的 ID/run/commit/fileName/size/SHA-256 从准备清单固定字段逐项转录，不能替换成最新 CI。清单文件名保留 `pilot-preservation-manifest.json`，原始字节 SHA-256 为 `c5f3b1e7ffa11a1627de70cf9b9f4853d50af5e6ad3aa40608113327fdc87300`。准备清单中的 companyArchiveCompleted 保持 false。

descriptor 的 subjectCommit 是本工作包引用的实施 Subject；artifacts.sourceCommit 是各自来源提交。两者仍按既有字段职责使用，不给 M1 增加二者必须相等的新约束。descriptor 原始字节摘要继续绑定全部固定字段；已批准输入仍由版本化文件及授权 locator 指定，ID 白名单本身不代表运行获批。清单和 ZIP 不重新格式化或打包。

## 单一身份链与失败处理

Kotlin 在既有 parser 附近定义唯一的内部版本/ID profile 校验，parse 后随 Parsed/Verified 工作包传递版本。Runner、RecoveryVerifier 与 OperationMain 消费已验证 profile；不在每个类复制允许列表。Schema 以 work-package Schema 的共享 identity 定义约束三个文档，离线加载器先注册依赖；Node 使用 Schema 校验及跨文档相等检查，不再维护独立固定 ID 常量。

archive 与 recovery 报告必须同时匹配 descriptor 的版本、ID、原始字节摘要、清单摘要；保持原有 executionId、Artifact 来源元组、payload/receipt 精确版本、摘要、保护、保留期和双身份交叉检查。Runner 的 receipt acceptanceId 使用工作包 ID，恢复阶段继续校验该绑定。离线成功摘要返回验证后的 ID，不能返回固定 M1 值。

恢复入口在读取不可信输入前写入 provisional 诊断的顺序保持不变。此时没有可靠身份：新 provisional 使用版本 2、workPackageId 为 null、status 为 IN_PROGRESS；它不是完成报告，不能通过离线验收。输入尚未通过完整 descriptor parser 时生成的最终失败报告使用版本 2、null ID，executionId、descriptorSha256、pilotManifestSha256 和身份字段均为 null，artifacts 为空，status 为 FAIL，并保留实际 errorCode 与 cleanup 结果。禁止从未验证 JSON、文件名或命令参数猜测 ID，禁止默认为 M1。

完整 descriptor 验证成功后，绑定报告使用其 profile；后续 archive report 不匹配仍返回 FAIL，不能挪用不可信报告身份。现有安全摘要允许 null ID 的失败路径继续适用；PASS 必须具有已验证配对和完整两项结果。最终 recovery Schema 仅为上述未绑定 FAIL 允许 null ID，不允许 null ID 的 PASS；provisional 仍由独立阶段规则识别，不纳入成功报告 Schema。

## 兼容、迁移与恢复

保持既有 M1 descriptor、Pilot 清单、原 ZIP 和已发布报告字节不变。新 reader 接受有效 M1 v1 与 M2.5 v2；旧 reader 继续处理 M1 v1，拒绝 M2.5 v2，这是预期的 fail-closed。有效 M1 在固定时钟与固定依赖下生成的绑定报告保持既有 canonical 字节；唯一明确调整是无法识别输入时不再伪称 M1，改为未绑定 v2 失败诊断。

不批量升级历史报告，不把 v2 改写为 v1，不覆盖旧 Evidence。新增工作包需先完成本地回归，再由有明确 Company 授权的操作员使用新 reader/operation。回退代码时保留 v2 Evidence，使用匹配版本工具校验；回退不得删除源、重新归档覆盖或将失败降级为 Pilot 成功。

## 影响文件与验证目标

| 文件或现有测试 | 拟议改动与必须证明的行为 |
|---|---|
| `EvidenceArchiveSourceVerifier.kt`、`EvidenceArchiveModels.kt` | 单一 profile 校验及版本传递；两个正确配对接受，未知或交叉配对拒绝；不弱化源检查 |
| `EvidenceArchiveRunner.kt`、`EvidenceArchiveRunnerTest.kt` | 绑定版本和 receipt acceptanceId；M1 canonical 基线不变，M2.5 正确传递 |
| `EvidenceArchiveRecoveryVerifier.kt`、`EvidenceArchiveRecoveryVerifierTest.kt` | 绑定报告、未绑定 provisional/FAIL、精确回读与完成标记；拒绝跨包/跨版本/摘要不符，失败不伪标 M1 |
| `EvidenceArchiveOperationMain.kt`、`EvidenceArchiveOperationMainTest.kt` | 安全摘要接受已验证 profile，未知输入只输出安全失败；非零退出且不泄露输入 |
| `ops/evidence-archive/schemas/`、`scripts/evidence-archive/verify-evidence.mjs` | 共享 identity 配对、v2 未绑定 FAIL 限制和跨文档绑定；保持禁止字段与 canonical 校验 |
| `scripts/tests/evidence-archive-evidence.test.mjs`、`EvidenceArchiveSourceVerifierTest.kt` | 两个 profile 的有效流程、交叉 ID/版本/摘要突变、原 M1 样本回归、重复字段及恶意源拒绝 |
| 新 descriptor、准备包与既有 runbook | 固定原输入，明确工具版本和授权；不生成实际归档报告或新 Owner 决定 |

Kotlin 文件位于既有 shared/adapter/archive/operations 目录，测试位于 shared/archive/operations。实施计划需按实际测试文件列出完整路径和执行命令，不扩大至归档核心重构。

最低验证矩阵：M1 v1 与 M2.5 v2 的 archive→独立 verify→Node 离线交叉校验；任何一份文档 ID 或版本对调、同 ID descriptor 单字节摘要变化、receipt acceptanceId 不符均拒绝；解析前失败不产生虚假身份或成功 marker；绑定后失败保留正确身份。使用既有测试 Provider/fixture，不启用 Company。保留既有 47 项离线校验回归，并新增针对实现行为的负例；后端目标单测按 60 秒超时运行，随后受影响 build、现有 CI 与双语 Pair Gate。

上述完整矩阵已由三个任务共同验证；实际 v1/v2 链路、失败矩阵、独立终审和提交对应 CI 见[任务 3 验证记录](../../m2/2026-09-07-evidence-archive-identity-task3.md)。真实归档仍需实际 Provider 能力、retention/accessOwner、独立身份、ACL 和独立执行授权；技术测试不能关闭这些条件。

## 评审与下一步执行计划

Owner 已确认两个显式 profile、未绑定失败诊断、M1 兼容边界及验证矩阵；决定与原始确认定位见书面评审记录。当时方案批准仅授权记录决定与编制详细 Implementation Plan；后续三个任务的实施授权分别保留在任务记录中，不扩展为工具验收或 Company 执行。

当前结果：任务 3 实施和本地验证完成，见[任务 3 记录](../../m2/2026-09-07-evidence-archive-identity-task3.md)。Git 状态：双语实施提交已推送；实施 Subject 与对应 CI 见任务 3 记录，本文所在提交仅补充交付记录。下一步动作：由 Owner 评审并决定 TDR-019 工具实施验收。前置条件：Owner 明确给出针对固定实施 Subject 的决定；Company 仍须独立资源与执行授权。验收目标：以三任务实施提交、APPROVE_FINAL、固定输入/失败矩阵、Pair Gate 及对应 CI 为依据留存 Owner 决定；不代表 Company 已归档。

## 重新评估条件

需要第三种工作包、可变 Artifact 数量、新 report 字段、修改 Receipt/Capability/Core Contract，或既有消费者无法接受上述版本迁移时重新评估。触及冻结语义须转 ADR Proposal。M2.5 的创建 P95 `1467/1477 ms` 未达 `1000 ms` 参考目标、canonical 不覆盖非主路径全部字段的限制继续保留。本方案不授权 merge、Tag、发布、部署或下一里程碑。
