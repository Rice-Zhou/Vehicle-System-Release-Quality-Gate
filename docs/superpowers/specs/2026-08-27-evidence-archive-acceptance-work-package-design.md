# Evidence Archive 验收工作包设计

## 1. 目标

建立独立验收工作包 `V0-2-EVIDENCE-ARCHIVE-001`，用于复核 `V0-2-PILOT-COMPANY-002` 的条件 A、B 所需公司级 Evidence 归档事实。工作包初始只能是 `PENDING`；它收集、校验和呈现证据，但不能自行改变既有验收状态。

本设计面向六个月业余时间可落地的课题：现在固化可执行边界和验收矩阵，取得公司资源后复用既有 Archive 实现完成真实验收，不提前引入新服务或伪造外部能力。

## 2. 治理边界

- 不修改 V0.1 Core Contract、Release-centric architecture、Manifest authority、Evidence、Traceability、Quality Engine、Adapter、Plugin 或 ADR 治理。
- 不新增归档实现或第二套 Capability 数据源；执行必须遵循 `TDR-004`、`TDR-011` 与 Pilot / Company 双模式设计。
- 不改写 `V0-2-PILOT-COMPANY-002` 或 `M1-OWNER-GATE-001`；关闭条件只由后续明确的 Owner 决定应用。
- 不授权 merge、Tag、release 或 production deployment。
- 本设计提交不是验收记录，也不是归档完成证明。

## 3. 验收对象

未来记录的固定对象是原始 Subject Artifact 及其 Pilot 保全清单，而不是承载验收记录的 commit：

| 对象 | 固定标识 | 大小 | SHA-256 |
|---|---|---:|---|
| 中文 Subject Artifact | `m1-evidence-892fb23ce75e7f74a05c1b5e304fccace70ee8d3` / Artifact ID `9631253528` | `55065` bytes | `1f087ef27cfabbb2152d06fc002eb0772c2efbbb63964d6b13ec5f0d7a73ed7a` |
| 英文 Subject Artifact | `m1-evidence-8687d49c9566030bb0829752dbe5dda45af02f4b` / Artifact ID `9631250285` | `55099` bytes | `e7602924fe67fd6eff75ebfe5d48122240639d883edc58dc164c419893d979ca` |
| Pilot 保全清单 | `pilot-preservation-manifest.json` | 由执行时清单复核 | `7bcb4d9df5ce0e28fe6150e0593c9824ea2533a2f7885f17d61d3ae813aa4a32` |

未来中文记录的 `subjectCommit` 固定为 `e3576582b08c154189eb9e7f2796f39280cdb8a5`，`pairedSubjectCommit` 固定为 `6ef2cd2fb234737fad78e96cff4172ef8f92fc45`；英文记录交换二者。它们固定 Pilot 保全事实与原始摘要，不表示 Company 归档已经完成。

本地目录只作为传输源，不进入可发布 Evidence locator。清单分类 `LOCAL_PILOT_NOT_IMMUTABLE` 与 `conditionBClosed=false` 必须保持原样，直到新的 Company 归档证据独立形成。

## 4. 记录生命周期

```text
Approved Design
    -> Implementation Plan
    -> Corporate Resource Readiness
    -> Controlled Archive Execution
    -> Independent Verification
    -> PENDING Acceptance Record
    -> Owner Review
    -> APPROVE | REJECT | CONDITIONAL
```

1. 书面设计批准后，先编写实施计划；不得把设计批准解释为执行授权。
2. 只有真实公司 Provider、运行身份、访问责任人和保留策略可用时才执行归档。
3. 归档和独立验证完成后，创建 `V0-2-EVIDENCE-ARCHIVE-001`，初始 metadata 必须为 `status: PENDING`、`owner: PENDING`、`decisionAt: PENDING`。
4. 记录提交后由 Owner 单独复核。仅 Owner 的明确指令可以产生终结态或 `CONDITIONAL` 状态提交。
5. 即使归档记录获批，`V0-2-PILOT-COMPANY-002` 和 `M1-OWNER-GATE-001` 也不会自动转换；每个状态转换都需要独立授权和 Git 记录。

## 5. 执行数据流

```text
Pinned Pilot ZIPs + Manifest
    -> Source Digest Verification
    -> Fresh Provider Capability Probe
    -> Provider-attested Runtime Identity
    -> Create-only Payload Archive
    -> Exact-version Read-back
    -> Immutability and Retention Verification
    -> Create-only Archive Receipt
    -> Exact-version Receipt Read-back
    -> ArchiveReceiptReference
    -> Independent Recovery Test
    -> Acceptance Evidence
```

源摘要不一致时立即停止，不得更新期望摘要。执行必须使用同一可信 facade 和新鲜 authorization；payload 与 receipt 都按精确 `versionId` 复核。只有成功的 `ArchiveReceiptReference`、独立恢复结果及实际控制证明可以支持长期归档结论。

## 6. 验收矩阵

| 检查 | 创建 `PENDING` 记录前的要求 | 可接受 Evidence | 缺失或失败时 |
|---|---|---|---|
| 固定输入完整性 | 两个 ZIP 与固定大小、摘要一致，清单摘要一致 | 本地复算报告和源清单摘要 | `FAIL`，停止传输 |
| Provider 配置 | Provider、endpoint、bucket、region、prefix 可验证且配置不含凭据 | Secret-free 配置指纹和检查报告 | `UNKNOWN` 或 `FAIL` |
| 运行身份 | 实际主体由 Provider 证明并绑定 `RuntimeIdentityRef` | Attestation 摘要和 `principalFingerprint` | `UNKNOWN`，fail closed |
| 私有访问与传输加密 | 无公开读取，传输与静态加密满足策略 | Capability Report 和控制检查 | `FAIL` |
| 精确版本归档 | payload 与 receipt 都有稳定 locator、`versionId`、size、SHA-256 | 两个 `StoredObjectRef` 和回读报告 | `FAIL` |
| 不可变性与保留 | 实际对象模式、retain-until 和运行身份限制均满足策略 | Head-style 控制证明和负向权限测试结果 | `UNKNOWN` 或 `FAIL` |
| 访问责任 | 明确访问责任角色和复核路径 | `accessOwner` 与受控访问记录 | `UNKNOWN` |
| 恢复能力 | 从精确版本下载到独立临时位置并复算原摘要 | 恢复日志、摘要与 UTC 时间 | `FAIL` |
| Archive Receipt | Receipt 内容与本次新鲜 Capability、对象引用一致 | `ArchiveReceiptReference` | `UNKNOWN`，不得声明完成 |
| 双语与仓库治理 | 中英记录语义配对、非 Markdown 字节一致、validator 通过 | Pair Gate、validator、CI Run | `FAIL` |
| Owner 决定 | 独立 Owner 授权尚未发生 | `N/A` | 保持 `PENDING` |

普通技术检查不能使用 `PENDING` 掩盖缺失事实；必须按证据写 `PASS`、`FAIL` 或 `UNKNOWN`。创建记录不要求所有检查先为 `PASS`，但任何非 `PASS` 项都必须明确阻止“条件已关闭”的表述。

## 7. Evidence 最小字段

每项外部 Evidence 至少包含：

- acceptance ID、原始 Artifact ID、源 commit 和配对 commit；
- stable locator、bucket、key、精确 `versionId`、size 和 SHA-256；
- Provider、`policyFingerprint`、`capabilityCheckedAt`、archivedAt 和 verifiedAt；
- `RuntimeIdentityRef` 的 Provider 与 `principalFingerprint`，不得保存原始主体标识；
- 实际 immutability mode、retain-until、retention policy 和 `accessOwner`；
- verifier、恢复测试结果、Availability 和保留期限；
- Owner authorization locator；决定前固定为 `UNKNOWN`。

不得记录 credential、token、presigned URL、endpoint user-info/query、原始 ARN/account/subject/user ID/session name 或本地绝对路径。临时 Bearer URL 不是稳定 locator。

## 8. 失败与恢复

- 源 ZIP 缺失、不可读或摘要不一致：停止，保留原文件和错误证据，不上传。
- Capability、身份、权限、加密、retention 或 immutability 无法证明：结果为 `UNKNOWN` 或 `FAIL`，不得降级到 Pilot 成功。
- 上传部分成功：保留已提交对象引用，清理安全可识别的 partial，不覆盖、不删除源文件、不伪造 receipt。
- 回读或摘要失败：按精确版本记录失败，不读取 latest 继续。
- Receipt 上传或回读失败：归档未完成；不得生成成功的 `ArchiveReceiptReference`。
- 恢复测试失败：保留失败日志和对象引用，记录保持 `PENDING`；重新执行必须产生新的执行 ID 和 Evidence。
- 网络或超时错误：不得归类为预期拒绝或成功；保持 `INDETERMINATE` 语义并 fail closed。
- 凭据疑似泄露：立即停止执行，按安全流程撤销并替代凭据；仓库只记录不含 secret 的处置证明。

任何失败都不得通过修改固定 SHA-256、放宽策略、缩短 retention 或切换到 `FILESYSTEM_STAGING` 来关闭。

## 9. 角色与前置条件

| 角色 | 职责 |
|---|---|
| Project Owner | 定义 Acceptance，作出独立最终决定 |
| Release Engineer | 固定源输入、执行归档、生成候选记录 |
| Platform | 提供受控 Provider、网络、版本化和 Object Lock / 等价能力 |
| Security | 审核运行身份、最小权限、私有访问、加密与 retention control |
| Independent Verifier | 使用精确版本执行回读和恢复复核，不使用上传者的结论代替验证 |

执行前必须具备：受控 Provider 配置、仓库外凭据或 Workload Identity、明确 `accessOwner`、经批准的 retention policy、可执行恢复测试的位置，以及不会把 secret 写入日志的运行环境。任一前置条件缺失时只保留工作包为待执行状态。

## 10. 测试与验证

书面设计提交至少执行：

1. Markdown 双语 Pair Gate 和非 Markdown 字节一致性检查。
2. 英文分支 Han 字符检查。
3. credential / token / presigned URL 高可信模式扫描。
4. `git diff --check` 与设计占位符扫描。
5. 受影响的 Acceptance validator 与完整 M1 文档 Gate。

未来执行与记录至少验证：

1. 两个源 ZIP 的实际 size 和 SHA-256。
2. 新鲜 Capability、Provider-attested identity 和策略指纹绑定。
3. payload / receipt 的 create-only、精确版本回读、摘要和保护状态。
4. 不短于策略的实际保留期以及运行身份不能 overwrite/delete/bypass。
5. 独立恢复结果与原始摘要一致。
6. Acceptance record metadata、状态枚举、Decision History 和 Evidence locator 可复核。
7. 中英文固定对象交叉引用正确，CI 与 Pair Gate 均通过。

## 11. 部署、迁移与成本边界

本工作包不部署新数据库、消息队列、Kubernetes 或独立归档服务。它复用现有应用、Archive Adapter、公司对象存储和 GitHub Actions；新增内容限于受控执行参数、Evidence 输出与验收记录。

Pilot 到 Company 的迁移是 source-preserving copy：先验证源摘要，再 create-only 上传，按精确版本回读并验证，最后生成 receipt。归档成功后仍不自动删除 Pilot 源；其清理需要独立保留策略和授权。

Provider 失败时保留源与已提交版本，修复同一 Provider 后重试。更换 Provider 必须生成新 locator、version、digest 和 receipt，不能覆盖旧 Evidence，也不能把旧失败记录改写为成功。

## 12. 状态转换与关闭规则

`V0-2-EVIDENCE-ARCHIVE-001` 只能按验收治理允许的转换从 `PENDING` 进入 `APPROVE`、`REJECT` 或 `CONDITIONAL`。机器检查无权改变 metadata 状态。

只有同时具备以下内容时，记录才可以向 Owner 推荐 `APPROVE`：

- 两个原始 Artifact 均有成功且可复核的 `ArchiveReceiptReference`；
- payload 与 receipt 的精确版本、摘要、私有访问、加密、不可变性和保留期均为 `PASS`；
- Provider-attested identity、`accessOwner` 和 retention policy 可复核；
- 独立恢复测试为 `PASS`；
- 双语 Pair Gate、Acceptance validator、CI 和安全扫描为 `PASS`；
- 不存在未处置的 `FAIL` 或 `UNKNOWN`。

该记录获 `APPROVE` 只证明本工作包的归档验收通过。是否关闭 `V0-2-PILOT-COMPANY-002` 的条件 A/B、是否更新 `M1-OWNER-GATE-001`，必须由 Owner 在各自记录中另行明确决定。

## 13. 设计验收标准

- 工作包与既有条件记录相互引用但状态独立。
- 固定输入、摘要、责任人、截止风险和证据字段明确。
- Pilot 本地保全不能被解释为长期不可变归档。
- 缺失外部资源时保持真实 `UNKNOWN`，不阻断课题继续设计与实现，也不伪造公司就绪。
- 失败路径保留源、保留真实错误并 fail closed。
- 验收记录只能在真实执行与独立验证后创建，初始状态只能是 `PENDING`。
- 任何 Owner 状态转换、merge、Tag、release 和 production deployment 都需要独立授权。
- 实施范围复用既有 Archive 架构，满足六个月业余时间可落地且具备公司级审计边界。
