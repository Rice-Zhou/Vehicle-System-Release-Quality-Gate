# TDR-022 — 最小 M2 合成串联演示

- 日期：2026-09-08；状态：Accepted（任务 1/2 实施范围）。2026-09-09 Owner 在方案与任务 1 下一步动作交付后指示“执行下一步”，授权任务 1；任务 1 验证交付后再次指示“执行下一步”，授权任务 2 实施，不表示 Owner 验收。
- 授权所指方案提交：中文 b859e4981270fbfdd27eb1c3e67fec2389b54604；英文 7d5d50f04c941ae42ca4b0fb5e82a104ffc2df2b。
- 任务 2 授权绑定的交接提交：中文 77b8a841322779d8db48f493d2f88529c8e2165b；英文 f22243ceac1d20fdf7589851ff2a27f8bf01bf5d。
- 基线：中文 b7dd99a61cdb7e6434e0b5e0def9968e18a11fc0；英文 8e1b4321285cd183b9fb3499af68952869db870a。
- 依据：[阶段目标](../reviews/2026-09-08-demonstrable-product-priority.md)、[P1 缺口](../reviews/2026-09-08-demonstrable-product-gap-inventory.md)、[已验收 M1](../../governance/acceptance/records/2026-09-08-tdr-021-m1-demo-review-001.md)。

## 目标与选择

在已验收 M1 演示上增加两条合成 Issue 的 FULL Sync、Issue Snapshot、Build Facts、异步 Traceability 与历史查询。输出能说明某条 Issue 为什么 Included、另一条为什么缺边；全部 Verified=false。它展示既有能力的机械闭环，不是 Quality Engine PASS/BLOCK，也不证明真实构建或车辆测试。

| 方案 | 取舍 |
|---|---|
| 扩展既有隔离 demo，合成输入经过真实 HTTP、应用事务和 Worker | 选择；复用数据库、身份、文件校验与现有接口，业务结果由现有模块产生 |
| 使用真实 Jira/GitHub Build context | 需要外部输入与授权，影响本地重放；当前无需 |
| 直接写 Snapshot/Edge 或展示测试 seeder 的结果 | 跳过本次需要展示的流程，不采用 |

V0.1 冻结语义、Schema、Migration、生产 API、权限规则、canonicalization 和 Quality Engine 均不修改。不增服务、软件安装、Company/AWS 资源、归档操作或新前端。材料沿用 Git 保存；不承诺管理员不可修改或任意字段篡改检测。

## 输入与权威

Issue 使用现有 FIXTURE source type、FixtureIssueSourceAdapter、DefaultIssueSourceRuntimeRegistry 和 Mapping Profile codec。demo source set 内新增 factory，descriptor 固定 adapterId=m2-demo-fixture、adapterVersion=m2-demo-fixture/v1、schema=jira-mapping-profile/v1、FULL、filterReference=all-relevant-issues/v1。仅在 demo 进程显式注入对应 descriptor registry；默认生产 registry 不变。factory 使用实际已编译 mappingVersion，为 DEMO-1、DEMO-2 生成同一终止页；observedAt 使用本次运行时间，不能沿用过期静态时间。

初始化只增加本次 Project、Principal、assignment 和 FIXTURE issue_source 配置。Source 暂未激活的 adapter/mapping 标记不得成为可同步输入；通过既有 mapping-profiles:activate 写入真实版本后才启动 Sync。禁止预写 Issue Revision、Observation、Sync 状态、Snapshot、Commit、Build、Edge 或最终结论。

Build HTTP 请求仍遵循现有 v2 和 provider=GITHUB_ACTIONS 枚举；当前 canonicalizer 同时限制 workflow/proof 为 GitHub 形状。为避免扩大契约，演示使用明确的合成 repository=vsrqg-synthetic/demo、pipeline=synthetic-m2、buildId=1/2、attempt=1，40 位合成 revision 分别为重复的 a/b。workflowReference 为 vsrqg-synthetic/demo/.github/workflows/demo.yml@synthetic，proofReference 为 https://github.com/vsrqg-synthetic/demo/actions/runs/1/attempts/1 或对应 2。这些是格式兼容的样例字符串，不是有效构建定位，不访问、不提供可点击证据链接，也不声称该仓库不存在。

仅在 demo 启动器注入 M2DemoProvenanceValidator，复用 BuildProvenanceValidatorPort；精确匹配上述两组常量、对应单一 sourceIssueId、当前样例实测 Artifact SHA-256，并检查 submitted/recomputed proofDigest 一致。返回 VALID/LOW、validatorVersion=m2-demo-fixture-provenance/v1、reasonCode=SYNTHETIC_FIXTURE_MATCHED，只表示合成输入符合本演示，不表示外部证明有效。其他输入固定 INVALID/LOW、SYNTHETIC_FIXTURE_MISMATCH。不使用恒定成功 validator；普通 Backend 继续使用原 GithubActionsBuildProvenanceValidator。应用层原有 Snapshot membership、Artifact、SERVICE assignment、事务与冲突检查全部保留。

## 隔离与复用

继续使用 TDR-021 的 demo source set、loopback、专用 vsrqg_demo 数据库、现有 Compose project/volume、内存 RSA JWT、实际 JwtDecoder/RBAC、显式仓库外演示口令。M2 模式通过 run-m1.ps1 -IncludeM2 显式选择；默认命令及六项 M1 场景不变，不复制一套 Compose 生命周期。M2 必须先完成同次 M1 场景，再复用其 Release、Locked Manifest 与文件摘要。

M2 身份增加 ENGINEER 和 SERVICE；Manager 配置 Mapping/Sync/Snapshot，Engineer 发起 Traceability，Service 携带 principal_type=SERVICE、project、traceability:ingest 并具有同项目 assignment。Manager 不能仅靠 scope 获得 TRACEABILITY_VERIFY；不改变 Permission.allowedRoles。不得输出 Token 或原始身份。

M2 进程显式开启 Issue Sync Worker、Issue Snapshot、Build ingestion、Traceability verification/Worker。普通 M1 保持关闭 Worker；生产默认配置不变。使用现有两个 Worker 的调度，不另建任务处理循环。HTTP 查询每 250 ms 轮询，总上限 30 秒；单请求上限 5 秒，失败状态、超时和异常均非零退出。旧 volume 中残留任务可能被既有 Worker 消费，运行前说明专用演示库用途；本次验收只绑定当前 runId，不将其他任务算作成功。

## 流程与预期

| 顺序 | 实际入口 | 断言 |
|---|---|---|
| 1 | 同次 M1 HTTP 流程 | 六项 M1 场景成功，取得 Locked Manifest 与实际文件摘要 |
| 2 | POST /api/v1/issue-sources/{sourceId}/mapping-profiles:activate | 201，版本由 codec/服务器确定 |
| 3 | POST /api/v1/issue-sources/{sourceId}/sync；GET /api/v1/issue-sync-runs/{syncRunId} | 202 后 SUCCEEDED，FULL、两条 Issue |
| 4 | POST /api/v1/releases/{releaseId}/issue-snapshots，body 仅 sourceId | 201，selectedCount=2 |
| 5 | POST /api/v1/traceability/facts:ingest，Build 1 只包含 DEMO-1 | 200；三类 Edge 由 ingestion 产生，ARTIFACT_RELEASE 仅来自 Locked Manifest |
| 6 | POST /api/v1/releases/{releaseId}/traceability:verify，body sourceId；轮询返回的 statusUrl | 202 后 SUCCEEDED，取得 Snapshot A |
| 7 | GET /api/v1/releases/{releaseId}/traceability?snapshotId={A} | DEMO-1：Fixed=true、Included=true、Verified=false、四边路径、TEST_RESULT_EVIDENCE_MISSING；DEMO-2：false/false/false、空路径、ISSUE_COMMIT_MISSING |
| 8 | 新 key ingest Build 2（DEMO-2），再创建 Run，取得 Snapshot B | 两条 Issue Included=true，仍均 Verified=false，仍缺测试 Evidence |
| 9 | 重新 GET Snapshot A，并查询 latest | A 的完整响应 bytes 和 contentDigest 不变；latest 指向 B |

Build 2 使用不同 Build Attempt authority，不能修改 Build 1 或靠变更同一请求来“补齐”历史。对首次 ingestion 和 verify 执行 same-key replay，响应必须相同；USER 即使带 ingestion scope 仍被拒绝。读取路径不修改状态。Fixed、Included、Gap 全部来自 HTTP 返回值，demo 只断言与呈现，不重算图。

## 输出与验证

M1 的 summary.json、manifest.json 保留；M2 同目录新增 m2-summary.json。仅保存 classification=SYNTHETIC_DEMO、proofKind=SYNTHETIC_FIXTURE、codeCommit、workingTreeDirty、runId、实际 HTTP 状态、场景状态、Release/Manifest/Issue Snapshot/Run/Traceability Snapshot ID、摘要及两条 Issue 的 Fixed/Included/Verified、typed path 和 Gap code。禁止 locator、Token、连接信息、原始请求/响应、异常正文。最终控制台结果必须综合 M1 与 M2，任一失败为 FAILED。合成结果 PASS 仅指场景断言成功。

验收证据必须包含：实际 PostgreSQL 17.11/HTTP 全流程、A/B 历史比较、same-key replay、USER ingestion 拒绝、错误 fixture/digest 返回 INVALID，错误事实进入校验时被拒绝、权限/身份隔离、普通 M1 回归、生产 bootJar 不含新增 demo 类。复用现有 GitHub CI 和 Artifact 上传；不新建环境或独立治理 Gate。后端单测默认 60 秒超时；无本地 Docker 时仅声明未运行集成测试，由 exact-commit CI 补足。双语非 Markdown 一致，Pair Gate 和既有契约/验收记录校验必须通过。

## 实施与下一步

[实施计划](../../superpowers/plans/2026-09-08-synthetic-m2-demonstration.md)分两项：隔离合成输入接入；HTTP 串联、入口与结果交付。方案自检已覆盖 P1 输入和展示缺口，Source/Build 权威没有改由 demo 持久化；自检不等同实施通过或 Owner 验收。

当前结果：任务 2 已实现，本地可执行验证通过，见[工程记录](../../m2/2026-09-08-synthetic-demo-walkthrough.md)。Git 状态：双语修改尚未提交。下一步动作：完成独立评审、配对提交和 exact-commit CI。前置条件：既有 GitHub CI。验收目标：真实 HTTP 串联、A/B 历史稳定、重放、权限拒绝、Verified=false、失败非零退出及保留 volume 复跑均有实际证据；完成后提交 Owner 审阅。
