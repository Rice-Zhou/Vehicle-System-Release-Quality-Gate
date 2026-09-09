# 可展示成品的最小缺口清单

当前状态见[离线报告验收后的产品复核](2026-09-09-post-demo-product-gap-review.md)；下方章节保留 2026-09-08 基线及离线报告交付前的历史复核。

- 核查日期：2026-09-08。
- 固定核查基线：中文 ba2ff0046039437c63c1f264d7df955b77879fe2；英文 e6f2dafdd9b0102a098ec9c6229828ef71eca863。
- 范围依据：[当前阶段决定](2026-09-08-demonstrable-product-priority.md)。本记录为只读代码、配置和文档核查，不是演示验收，也不授权实施或部署。

## 结论

已具备 Release/Manifest 与 Issue/Traceability 的后端实现及自动化测试，但尚不能据此宣称存在从空环境可运行的成品演示。当前首要工作是补齐最小 M1 演示闭环：启动、合成项目身份、实际样例文件校验、Manifest Lock 与导出；随后串联已有 M2 能力。Company 归档资源不在关键路径上。

一个实质缺口是 Manifest 校验：当前生产评估路径只记录声明的 checksum，在结构检查通过时返回 `INCOMPLETE` 和 `ARTIFACT_CHECKSUM_NOT_VERIFIED`。Lock 要求有效持久化校验，M2.3 又依赖 Locked Manifest。因此只加启动说明或页面，不能让完整流程成立；也不能通过直接修改数据库结论或放宽 Lock 条件来获得演示成功。

## 缺口与最小完成条件

| 优先级 / 项目 | 可验证现状与依据 | 最小完成条件 |
|---|---|---|
| P0 / 启动与演示身份 | [开发 compose](../../../deploy/dev/compose.yml)只包含 PostgreSQL；[M1 手册](../../m1/runbook.md)仍需注入 DataSource、OIDC 和可信 validator 配置。[安全配置](../../../backend/src/main/kotlin/com/ricezhou/vsrqg/access/adapter/SecurityConfig.kt)要求 JWT，[项目授权](../../../backend/src/main/kotlin/com/ricezhou/vsrqg/access/adapter/JdbcProjectAuthorizer.kt)还要求已有 principal/project_assignment。未发现独立演示启动包。 | 一份明确的本地启动入口，说明所用 JDK、数据库连接、演示身份和最小初始化步骤；保留既有鉴权，无真实 Provider 或公司身份依赖。具体身份实现须在实施前记录技术选择。 |
| P0 / 可成立的 Manifest Lock | [ValidateManifest](../../../backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/application/ValidateManifest.kt)输出上述未校验状态；[LockManifest](../../../backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/application/LockManifest.kt)拒绝不满足持久化校验条件的候选。[现有 Smoke](../../../scripts/m1/acceptance-smoke.ps1)运行测试夹具，不能替代面向演示者的真实样例文件校验。 | 对入库的无敏感合成文件实际计算 checksum，匹配才能 Lock；单字节损坏明确失败；通过同一应用路径导出 Locked Manifest。该局部能力优先于页面建设。 |
| P1 / 合成 Issue 和 Build 输入串联 | [FixtureIssueSourceAdapter](../../../backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/FixtureIssueSourceAdapter.kt)可复用，但[运行时注册](../../../backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSourceRuntime.kt)及现有配置没有交付完整的独立 fixture 演示入口；[集成测试](../../../backend/src/test/kotlin/com/ricezhou/vsrqg/traceability/TraceabilityVerificationStartIntegrationTest.kt)依赖测试 seeder。[Issue Snapshot](../../m2/issue-snapshot.md)和[Build Provenance](../../m2/build-provenance.md)分别已有接口规范。 | 从标明合成身份的输入，沿既有应用/Adapter 路径产生 Issue Snapshot 和 Build Facts；提供一条完整链和一条缺边链；不直接预写最终 Snapshot 或伪称查询过真实 Jira/CI。 |
| P1 / 验证调用与结果讲解 | [M2.5 Controller](../../../backend/src/main/kotlin/com/ricezhou/vsrqg/traceability/adapter/TraceabilityVerificationController.kt)已有创建、轮询、历史查询；[运行规范](../../m2/traceability-verification-operations.md)要求显式开启入口和 Worker。源码/脚本扫描未发现串起 M1 至 M2.5 的独立演示命令。 | 顺序调用已有接口并展示 Release、Manifest、Issue、Fixed/Included、Gap 和历史查询；清楚显示 `Verified=false`，新事实不改变旧 Snapshot。先提供可读命令输出，无需先选前端框架。 |
| P2 / 展示页面与最终质量报告 | 受跟踪源码中未发现前端页面或独立前端 package；根 package 仅为契约校验工具。当前 Controller 扫描未发现 Quality Evaluation/Release Quality Report 运行接口。[M4 计划](../14-mvp-implementation-plan.md)才包含确定性 Gate 与报告。 | 先把已有 M1/M2 输出组织为可读演示结果；它只能叫追溯结果，不能冒充最终 PASS/BLOCK 质量报告。完整报告依赖后续真实 Engine 实施，再考虑薄页面。 |
| 后续产品闭环 / M3 与 M4 | 当前主源码目录有 access/issue/manifest/release/shared/traceability，未发现 Device/Agent/Test Orchestrator 或 Quality Engine 的对应运行实现；[Agent 示例](../../../contracts/examples/v0.2/agent/registration.json)和[规则示例](../../../contracts/examples/v0.2/quality-rule/critical-anr.yaml)是契约材料，不是已交付服务。[原 MVP 计划](../14-mvp-implementation-plan.md)规定真实设备和确定性报告出口。 | 后续按明确范围实现设备测试、Evidence 与规则评估。当前合成演示不能作为完整 MVP 或真实 Release 验收；本轮不启动 M3/M4。 |

优先级表示实施顺序建议，不新增 Owner Gate 或企业环境前置条件。P0 两项属于同一个最小 M1 演示工作包，随后才处理 P1；不重新实施已经接受的 M2.5 算法或数据库保护。

## 本次检查及限制

- 两条基线工作区在核查开始时干净；扫描范围为受跟踪主源码、资源、deploy、scripts 和 contracts/examples，并对照运行手册。未发现表示该范围内未交付，不推定其他机器或未提交材料不存在。
- 实际执行契约校验：`node scripts/contract-validator.mjs`，结果为 `PASS`，schemas=4、positive=12、negative=5、operations=34。这是契约检查，不是 34 个接口都已实现的证明。
- 当前命令环境未发现 docker/podman；PATH 上的 Java 为 17.0.12，而 [Backend 构建](../../../backend/build.gradle.kts)要求 toolchain 21。这不证明本机没有其他 JDK，也不等于 Gradle 无法解析 toolchain。本轮未安装运行时、启动数据库或应用，未执行 HTTP 演示，因此启动与端到端演示均为未验证。
- 两份原 M2.5 Evidence 已在 Git 保存，保全事实见[准备包](../../../ops/evidence-archive/m2-5-preparation/README.md)；不再将 AWS、Object Lock 或 Company 归档作为展示缺口。
- 本次仅新增清单并更新阶段导航；既有验收状态、Schema、代码和配置均不改变。双语门禁、验收记录校验和提交 CI 作为本次记录检查，不替代成品验收。

## 2026-09-08 原下一步执行计划

当前结果：最小缺口已定位，首要工作包为 M1 可运行合成演示。Git 状态：本记录随双语文档提交，推送状态以远端核对为准。下一步动作：为该工作包明确最小技术方案与实施步骤，集中解决启动/演示身份和实际样例文件 checksum→Lock→导出，不扩展到 M3/M4 或前端平台。前置条件：进入代码实施前完成必要的技术选择记录和范围确认；无需 Company 归档资源。验收目标：方案能逐项映射两项 P0 缺口，列出复用文件、最小改动、成功及损坏样例，并明确后续演示如何在保留现有鉴权和数据库权威的情况下运行。

## 2026-09-09 验收后复核

复核基线：中文 b765b7ef4ae3c42b3c1c6f11a125c260d0256a73；英文 f429c3ec4efbafaabe516e4087ebd6a7a508647e。两工作区开始时干净，远端分支与本地基线一致。以上 2026-09-08 内容保留为历史核查；当前缺口以下表为准，不再以旧“未发现入口”结论描述已交付演示。

### 已完成与剩余缺口

| 原项目 | 当前状态与可验证依据 | 剩余范围 |
|---|---|---|
| P0 / 启动、身份、文件校验与 Lock | 已完成并获 [M1 Owner APPROVE](../../governance/acceptance/records/2026-09-08-tdr-021-m1-demo-review-001.md)，实施 Subject 917f0c74b297cfb74e2e6dc73714de81057309cd / 4a05ce5b3f88df1db233610d486d4619730267ed。[单命令入口](../../../scripts/demo/run-m1.ps1)复用实际 HTTP、JWT/RBAC、文件 checksum 与数据库；验收覆盖正确文件、损坏拒绝、Lock/导出和复用。 | 此演示缺口关闭。普通 Backend 的默认 INCOMPLETE 行为仍按 TDR-021 保留；合成演示接受不等于真实 Provider 接入完成。 |
| P1 / Issue/Build 输入、验证与历史串联 | 已完成并获 [M2 Owner APPROVE](../../governance/acceptance/records/2026-09-09-tdr-022-m2-demo-review-001.md)，实施 Subject 8d5354dcf21ae7b506b27f56eae4d044b9beb895 / db98f89ab07e427beda63ac2e9616422f6f38f34。现有入口的 IncludeM2 串联 Sync、Issue Snapshot、Build Facts、Worker 和 A/B 历史；验收记录包含四份 10/10 PASS 报告。 | 此演示缺口关闭。完整链与缺边能解释，所有 Verified=false；不重做 M2.5。 |
| P2 / 已有结果的阅读与展示 | [M1 报告](../../../backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/DemoReport.kt)与 [M2 报告](../../../backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/M2DemoReport.kt)已输出同次运行标识、场景/HTTP 状态、Release/Manifest、A/B Snapshot、Issue 路径、Gap 及历史检查；[手册](../../m2/synthetic-demo-runbook.md)已有解释表。JSON 和文档阅读能力已交付。受跟踪文件中未发现 HTML 演示报告或独立前端 package；根 package 用于契约工具。 | 便于展示者直接打开、集中阅读同次结果的只读报告尚未交付。这是展示改善建议，不倒置已通过的 M1/M2 验收，也不构成新的硬性验收门槛。 |
| 后续产品闭环 / M3、M4 | 受跟踪主源码仍为 access/issue/manifest/release/shared/traceability，未发现 Device/Agent/Test Orchestrator 或 Quality Engine 对应运行实现；[MVP 计划](../14-mvp-implementation-plan.md)规定真实设备、Test Evidence、版本化 Rule 和确定性质量报告出口。 | 真实设备测试与最终 Quality Result 仍未交付。只读追溯报告不能填补这些功能缺口；本轮不启动 M3/M4。 |

### 建议的唯一下一工作包

名称：M1/M2 离线只读演示报告。目标是让展示者从同次已有输出直接解释“Release 包含什么、Issue 为什么 Included 或缺边、为什么尚未 Verified、新事实与旧 Snapshot 有何关系”。工作包只处理呈现，不新增业务事实或重新计算质量决定。

建议复用 summary.json、manifest.json 和 m2-summary.json，优先评估不依赖在线服务的单文件 HTML；具体生成方式、输入校验及双语交付方式在实施前以 TDR 比较并确定。本次只是可审查的工作包建议，尚未选择前端框架或批准报告代码实施。

| 完成条件 | 预期检查证据 |
|---|---|
| 同次运行可定位 | 报告显示 runId、codeCommit、workingTreeDirty、Release/Manifest 和 A/B Snapshot 标识；混合不同运行或缺失必要输入明确失败，不能拼出成功报告。 |
| 已有事实准确呈现 | 正常样例逐项对照原 JSON：两条 Issue 的 Fixed/Included/Verified、路径与 Gap、A/B 和历史状态一致；不重算图或修改源文件/数据库。 |
| 结果语义可辨 | 明示 SYNTHETIC_DEMO、SYNTHETIC_FIXTURE、Verified=false；场景 PASS 与 Release 质量判定分开。FAILED、NOT_RUN、缺失 M2 和输入错误可见，不能默认通过。 |
| 打开与分享简单 | 生成后的报告可在已有浏览器离线打开，无 CDN、在线 API、数据库连接或新服务；按既有 GitHub 治理保存适合入库的合成样例及来源定位。报告是派生展示，不成为第二数据权威。 |
| 最小且安全 | 不引入登录/管理平台、图表框架、实时查询、真实 Provider、云资源或归档系统；展示字段受控并安全转义，不把输入文本作为可执行内容。测试正常、失败、缺失/混合输入及显示转义，再检查实际渲染。 |

M3/M4 的正式出口仍按冻结架构与既有计划执行；以上建议不授权 merge、Tag、发布或部署。

### 本次检查与限制

本次重新读取两份 APPROVE 记录的固定 Subject、Scope、Evidence 和 Residual Risks，检查报告实现、入口、运行手册及受跟踪源码清单。工程运行计数引用固定实施验收记录，本轮没有重新执行数据库/HTTP 演示，也没有把本次文档提交当作实施 Subject。

M1 原演示 Artifact 保留至 2026-10-08 UTC；M2 测试/演示 Artifact 最早于 2026-10-09T03:43:30Z 到期。现有记录保存定位与摘要，不代表原始数据永久可取；后续展示材料按既有 GitHub 方式保存所需合成结果或明确标注不可用，无需 Company 资源。此前 M2.5 创建 Run 的 P95 1467/1477 ms 未达到 1000 ms 参考目标，canonical 摘要覆盖限制、两项 Windows ACL SKIPPED 以及尚未单独故障注入的 demo Worker FAILED/超时传播均保留，不转为本轮通过结论。

## 下一步执行计划

[Task 1 构建验证](../../m3/minimal-apk-build-verification.md)。当前结果：Task 1 APK 构建及独立工程复审完成，构建记录保留摘要和三项已知 lint 警告。Git 状态：配对实施 Subject 为 9a63699 / b27fc82，记录提交独立，推送以远端核对为准。下一步动作：执行 Task 2，实现 Agent 身份、注册与上下文机器契约。前置条件：Task 2 实施指令；无需 Company 资源。验收目标：mTLS/JWT 隔离、注册/上下文正负验证和双语提交可核对；不声称真机或 M3 验收。
