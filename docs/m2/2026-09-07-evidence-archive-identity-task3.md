# Evidence Archive 身份扩展任务 3 验证记录

## 授权与范围

Owner 本轮原始指令为“执行下一步”，对应任务 2 交付中的任务 3。下列 Unicode 转义保留原文；授权技术实施、测试和双语交付，不代表整套工具的 Owner 验收或 Company 归档。

```text
\u6267\u884c\u4e0b\u4e00\u6b65
```

依据：[TDR-019](../v0.2/tdr/TDR-019-versioned-evidence-archive-work-package-identity.md)及[实施计划](../superpowers/plans/2026-09-07-m2-5-evidence-archive-identity-implementation.md)。
基线：中文 b2cbda513bdae96aa6c99a799988a64c5dfc828a；英文 62bcbe4d073a0df7a8a88f79fad62655f0e5c861。

## 实施与固定输入

正式 descriptor 由原准备清单 create-only 创建，并通过 Schema 和整个投影对象逐项相等检查；两份 Artifact、原 Subject、run、commit、size 和摘要均保持原值。原清单 SHA-256 为 `c5f3b1e7ffa11a1627de70cf9b9f4853d50af5e6ad3aa40608113327fdc87300`，正式 descriptor SHA-256 为 `04979626ff995a2ab5892b0ffac9ac54ef98a464309f385892c82161b7a7c0df`。

集成测试使用临时测试 ZIP、清单和受控目录，实际执行 SourceVerifier → ArchiveEvidence facade/Runner → RecoveryVerifier → Node CLI；M1/v1 与 M2.5/v2 均执行此链。测试替身保存并回读实际 payload/receipt body、精确版本和保护引用。M2.5 样本标记为 `TEST_FIXTURE`，与引用原 ZIP 的正式 descriptor 分离，不能作为 Company 报告。原 M1 canonical 文件和准备清单的七项 Git blob 基线不变；本轮未改动或重新打包原 ZIP。

正常测试只比较重新生成的字节；仅显式 `VSRQG_EXPORT_IDENTITY_M25_FIXTURES=create` 导出模式逐文件使用 `CREATE_NEW`。Node 子进程限时 20 秒，后端集成类限时 60 秒。最终样本均使用 LF：

| 文件 | 原始 SHA-256 |
|---|---|
| descriptor.json | `0cae7323326b57385b52a2823179e5c517a9b9e76d0c9e8ccb3bfe30b63228ad` |
| archive-report.json | `022be9244abd8d16f69f6ca2e0a194fb82080206b35623a7c3e03bb27c36dd43` |
| recovery-report.json | `02ac9c91a4d64fa0e618ed6cc08bd78882d183d022737e58902c03a6e1dc0d52` |
| 零字节 completion marker | `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855` |

marker 文件名末段等于 recovery 原始摘要；两份报告均绑定 descriptor 的原始字节摘要。

## 验证与修复

| 检查 | 本地结果 |
|---|---|
| 实际 JVM→Node 集成 | 2 项通过 |
| Node 离线验证回归 | 85 项通过，0 跳过 |
| 全部归档后端测试 | 226 项：221 通过，5 环境跳过，0 失败/错误 |
| assemble | 通过 |
| acceptance-record-validator | 通过 |
| contract-validator | schemas=4，positive=12，negative=5，operations=34，通过 |
| diff 空白检查与原固定输入 | 通过 |

5 项跳过涉及既有 POSIX 权限、符号链接权限和本地文件系统身份限制；不记为通过，不作为 Company ACL 证据。

TDD 首次有效 RED 在真实 JVM 链已成功后因固定样本尚不存在而失败。父任务复查发现 Windows Jackson pretty printer 输出 CRLF，而 Git 按 LF 规范化，会破坏 Linux 检出后的原始摘要绑定。新增零 CR 断言先 RED，再在生成源头固定 LF；仅备份并替换本轮新增的四份未提交样本，保留原 M1 输入。修复后 create-only 导出成功，正常集成、Node、全归档回归及 assemble 全部重新执行。新描述符的 Git filtered/unfiltered blob 相同；两份报告的原始摘要绑定均有持续断言。

本地审计日志保留于忽略的 backend/build 目录：`task3-meaningful-red.log`、`task3-line-ending-red.log`、`task3-lf-export-success.log`、`task3-lf-focused-green.log`、`task3-lf-node-green.log`、`task3-lf-full-evidence.log`、`task3-lf-assemble.log`。早期导出日志曾被重复执行覆盖，不用其作为最终生成证据；最终 LF 成功日志独立保留。

## 失败矩阵与独立复审

| 场景 | 证据与拒绝语义 |
|---|---|
| 单个文档 ID/version、跨包配对 | Node 各文档突变矩阵及 JVM parser/Runner/Recovery 回归；SCHEMA_INVALID 或 EVIDENCE_MISMATCH，错误配对不进入外部写入 |
| ID 不变但 descriptor 原始字节改变 | 实际 JVM 样本追加字节后离线拒绝 EVIDENCE_MISMATCH |
| receipt acceptanceId | 修改实际下载的 receipt body 及其引用摘要，恢复拒绝 RECEIPT_MISMATCH |
| 解析前/后失败 | 解析前 v2/null，解析后保留验证配对；FAIL 与非零退出，不借用未验证身份 |
| 恶意 ZIP、ACL/身份、清理/发布故障 | 既有 Source/Runner/Recovery/StableFileReader 回归保留无外部写入、外来文件保护与 fail-closed 断言 |
| 有效 M1 与 M2.5 | 两个 profile 的实际 JVM→Node 链通过，原 M1 canonical 字节保持 |

任务 3 独立审查：Spec APPROVE、Quality APPROVE，无阻断发现；指出本地实施报告文件清单仍列旧 marker 后缀，现已修正。审查核对实际日志和 Git blob，未将测试替身或未重验的原 ZIP 解释为 Company 证据。整份三任务计划终审：APPROVE_FINAL，Spec/Quality 均通过，无阻断或重要发现；独立核对固定输入、双语文件、实际日志及 Node 样本。提交后的 Pair Gate 与远端 CI 见下表，均已实际验证。

## 版本与远端证据

| 任务 | 中文实施提交 | 英文实施提交 |
|---|---|---|
| [任务 1](2026-09-07-evidence-archive-identity-task1.md) | 4d71742de325fcebfb416b07c135939dd0774afb | 71bac31f5c1ab4ccc4e52779abe4f699bd689b4a |
| [任务 2](2026-09-07-evidence-archive-identity-task2.md) | b2cbda513bdae96aa6c99a799988a64c5dfc828a | 62bcbe4d073a0df7a8a88f79fad62655f0e5c861 |
| 任务 3 | 1460912eeb1e0f88cce0752253e000ca7fa12cd0 | 59ae93f0010147db6a1bd038f1424656003a72d3 |

下表逐项通过 GitHub API 核对 status=completed、conclusion=success 与上表相应 head_sha。任务 1、2 历史证据在本轮重新核对，任务 3 使用本次新运行，不以历史 CI 替代。

| Task | Branch | Gate | Run | Result |
|---|---|---|---|---|
| Task 1 | ZH | M1 | [34099570087](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34099570087) | SUCCESS |
| Task 1 | ZH | M2 | [34099569801](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34099569801) | SUCCESS |
| Task 1 | EN | M1 | [34099569581](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34099569581) | SUCCESS |
| Task 1 | EN | M2 | [34099569586](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34099569586) | SUCCESS |
| Task 2 | ZH | M1 | [34106832943](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34106832943) | SUCCESS |
| Task 2 | ZH | M2 | [34106832929](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34106832929) | SUCCESS |
| Task 2 | EN | M1 | [34106841483](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34106841483) | SUCCESS |
| Task 2 | EN | M2 | [34106841463](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34106841463) | SUCCESS |
| Task 3 | ZH | M1 | [34123106090](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34123106090) | SUCCESS |
| Task 3 | ZH | M2 | [34123106351](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34123106351) | SUCCESS |
| Task 3 | EN | M1 | [34123114049](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34123114049) | SUCCESS |
| Task 3 | EN | M2 | [34123114086](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34123114086) | SUCCESS |

提交级 Pair Gate 通过，包含双语路径/结构/技术标识、英文正文检查及全部非 Markdown blob 一致性。上表任务 3 配对提交是工具实施验收 Subject；本文所在后续文档提交只补充已发生的复审和 CI 记录，不替换实施 Subject 或原 M2.5 Evidence。后续文档提交自身 CI 另在交付回执核验。

## 执行裁定

- 分任务授权优先于技能一次执行全计划的默认行为；越界会导致未经授权的改动需要回退。
- 任务 1 允许显式版本所必需的构造器迁移；若遗漏会造成编译失败。
- 任务 1 是中间状态，不能宣称整套工具交付；错误宣称会掩盖运行时未贯穿。
- 未绑定 v2 失败使用字面 JSON null，与绑定对象分开校验；误判会破坏 Schema/消费者兼容。

## 限制与下一步执行计划

2026-09-08：Owner 已明确批准固定 Subject，决定与原始指令见[工具实施验收记录](../governance/acceptance/records/2026-09-08-tdr-019-implementation-owner-gate-001.md)；本记录原始 Task 3 授权边界保留为历史事实。

工具 Owner 验收已为 APPROVE。Provider、retention/accessOwner、独立身份及 ACL 仍待实际证据；Company 外部执行仍未授权。原创建 P95 为 1467/1477 ms，未达到 1000 ms 参考值；canonical 不覆盖非主路径全部字段；原 Artifact 最早于 2026-10-07 到期，本地保全不是不可变归档。原 `LOCAL_PILOT_NOT_IMMUTABLE`、`conditionBClosed=false`、`companyArchiveCompleted=false` 保持。

当前结果：任务 3 本地实施、验证和完整计划终审完成。Git 状态：原实施 Subject 不变，本次双语治理提交记录 Owner APPROVE，见[验收记录](../governance/acceptance/records/2026-09-08-tdr-019-implementation-owner-gate-001.md)。下一步动作：补齐既有 Company 归档准备包的无凭据前置输入。前置条件：Project Owner / Platform / Security 提供 Provider 与受控目标、retention/accessOwner、独立身份及目录 ACL 的证据定位；实际执行仍须另行明确授权。验收目标：逐项保留可核实证据，缺失项继续 UNKNOWN，不启用 Company，不以工具验收替代实际归档验收。
