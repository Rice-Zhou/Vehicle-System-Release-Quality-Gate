# M2.5 Evidence 归档准备工作包

- Preparation ID：`M2-5-EVIDENCE-ARCHIVE-PREP-001`
- 状态：准备材料和正式 descriptor 已固定；本地技术验证通过，Company 执行仍阻断。
- 关联验收：`M2-5-OWNER-GATE-001`，Owner 决定为 `APPROVE`。
- 范围：只准备原实施 Evidence 的本地保全、固定输入、执行前置条件与验收清单。

## 固定输入与核验

[保全清单](pilot-preservation-manifest.json) 是准备阶段的数据记录，不是可直接传入归档执行器的 work-package descriptor。清单 SHA-256 为 `c5f3b1e7ffa11a1627de70cf9b9f4853d50af5e6ad3aa40608113327fdc87300`；使用 UTF-8 无 BOM、LF。两条分支持有相同字节。

| 对象 | 原实施 Subject | Run / Artifact | ZIP 大小 |
|---|---|---|---:|
| 中文 | `3b010726941c26f0b4096cea34ea4b4dd80c5283` | `34076975284` / `10002515016` | 1756 bytes |
| 英文 | `de49b2af6ddf1e5f529453e2714366064c873e15` | `34077129961` / `10002554126` | 1753 bytes |

- ZIP SHA-256 与实时 GitHub Artifact 元数据逐一相符；具体摘要、稳定 locator、原文件名和到期时间见清单。
- 两份 ZIP 均只含四个预期文件；summary 原始字节摘要同时匹配 sidecar 和 Owner record，exactCommit 匹配对应实施 Subject。
- 两份 summary 均为 12/12 PASS；20 Issues / 2000 Edges / 3 samples；四项恢复结果 PASS，性能子报告与 summary 的 P95 一致。
- 清单记录每个 ZIP member 的原始字节大小及 SHA-256，未重新打包、改写原 ZIP 或替换为后续 CI Artifact。
- 最早在线到期时间为 `2026-10-07T02:45:31Z`。本地 ZIP 存放在仓库外，位置在本任务交接中提供；Git 不包含 ZIP、临时下载 URL、凭据或本地绝对路径。

本地副本和清单保持 `LOCAL_PILOT_NOT_IMMUTABLE`、`conditionBClosed=false`、`companyArchiveCompleted=false`。本次未执行恢复数据库测试、Company Provider attestation、源目录 ACL 验证或独立归档恢复；内容核验不能代替这些检查。

准备阶段验证：既有归档离线校验测试 47/47 PASS，验收记录校验 PASS；本地与双语清单字节一致，两份本地 ZIP 摘要匹配清单。使用既有 Schema 实测：原 M1 descriptor 通过，仅将工作包 ID 改为拟议 M2.5 ID 即因 const 约束被拒绝，确认下述执行阻断。以上不是 Company 归档验收结果。

## 执行阻断与推荐处理

准备核查时，[工作包 Schema](../schemas/work-package.schema.json)、Kotlin parser、运维摘要及离线校验器固定使用 `V0-2-EVIDENCE-ARCHIVE-001`。任务 1、2 已扩展输入、离线契约和运行时身份贯穿；任务 3 已创建[正式 descriptor](../m2-5-evidence-archive-001.json)并完成本地 JVM→Node 集成验证，独立复审与 CI 通过。既有 M1 ID 不得借给 M2.5，亦不得改写它的固定输入。

正式 descriptor 与本目录保全清单分别保存；不能将清单直接交给 `evidenceArchiveOperation`，不能绕过 Archive facade。新 JVM 测试样本标有 `TEST_FIXTURE`，不是原 ZIP 的 Company archive/recovery report，也不是归档 acceptance record。

[TDR-019](../../../docs/v0.2/tdr/TDR-019-versioned-evidence-archive-work-package-identity.md)已批准两个显式版本/ID profile，任务 2 已完成运行时身份贯穿。任务 3 的正式 descriptor 和真实 JVM→Node 本地证据已具备，复审与 CI 均通过，等待工具 Owner 验收。保留唯一 parser/validator、Archive facade、Provider attestation、create-only、exact-version、独立身份与 fail-closed；技术支持不等于 Company 执行获批。

## 执行前置条件

| 条件 | 当前状态 | 责任角色 | 关闭证据 |
|---|---|---|---|
| 两份源 ZIP 的身份、大小、摘要与 summary sidecar | PASS | Implementation Owner | 本清单、原 Artifact、Owner record |
| 执行器支持独立 M2.5 工作包且不影响 M1 | 本地测试/复审/CI PASS；Owner 待确认 | Implementation Owner / Project Owner | 获批技术方案、实现提交与回归 CI |
| Provider 与受控目标、私有访问、加密、版本化、Object Lock | UNKNOWN | Platform / Security | 无凭据配置与实际 capability 报告 |
| retention policy 与 accessOwner | UNKNOWN | Project Owner / Release Engineer | 明确保留期限、责任人及批准 locator |
| 上传者及独立验证者的仓库外身份 | UNKNOWN | Security / Independent Verifier | Provider attestation、不同 fingerprint 与见证记录 |
| 源、报告、恢复目录的单写者权限 | UNKNOWN | Release Engineer | 按既有手册完成实际 ACL/Owner 检查 |
| Company 外部写入及独立恢复授权 | NOT_AUTHORIZED | Project Owner | 对固定对象、目标及范围的明确原始授权 |

只提供无秘密的配置与批准 locator；凭据通过既有仓库外身份链注入，不在聊天或 Git 中提交。上述资源未就绪时保留本地副本，不缩短保留期、不将 UNKNOWN 改为 PASS、不启用 Company。

## 后续执行与验收

取得技术支持、资源与明确授权后，遵循 [Evidence Archive 手册](../../../docs/m1/evidence-archive-runbook.md)：

1. 在受控源目录重新核对两份 ZIP 和清单的字节、摘要、身份及权限，并验证已固定的正式 descriptor；不以最新 Artifact 代替原实施 Evidence。
2. 归档身份通过既有 facade 执行 create-only 上传，固定 payload/receipt 的 locator、versionId、size、SHA-256、保护模式及 retain-until。
3. 独立身份按精确版本恢复，验证摘要、实际保护和 retention，生成恢复报告及绑定摘要的零字节 completion marker。
4. 使用既有离线交叉校验权威验证报告；实际执行与独立恢复完成后，才创建初始 PENDING 的独立归档验收记录，提交 Owner 决定。

M2.5 已有 APPROVE 不等于归档批准；原创建 P95 `1467/1477 ms` 未达 `1000 ms` 参考目标，canonical 不覆盖非主路径全部字段，这些限制继续保留。本准备工作包不授权 merge、Tag、发布、部署、Company 启用或下一里程碑。

## 下一步执行计划

当前结果：任务 3 实施和本地验证完成，见[任务 3 记录](../../../docs/m2/2026-09-07-evidence-archive-identity-task3.md)。Git 状态：双语实施提交已推送；实施 Subject 与对应 CI 见任务 3 记录，本文所在提交仅补充交付记录。下一步动作：由 Owner 评审并决定 TDR-019 工具实施验收。前置条件：Owner 明确给出针对固定实施 Subject 的决定；Company 仍须独立资源与执行授权。验收目标：以三任务实施提交、APPROVE_FINAL、固定输入/失败矩阵、Pair Gate 及对应 CI 为依据留存 Owner 决定；不代表 Company 已归档。
