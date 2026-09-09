# M2 合成串联演示实施记录

- 记录日期：2026-09-09；TDR-022 任务 2 工程记录，不是 Owner 验收。
- 依据：[TDR-022](../v0.2/tdr/TDR-022-synthetic-m2-demonstration.md)、[实施计划任务 2](../superpowers/plans/2026-09-08-synthetic-m2-demonstration.md)。
- 实施前基线：中文 77b8a841322779d8db48f493d2f88529c8e2165b；英文 f22243ceac1d20fdf7589851ff2a27f8bf01bf5d。基线四条 CI 均已核对 SUCCESS。

## 执行依据与范围

Owner 在任务 1 完成、下一步明确为任务 2 的上下文中，于 2026-09-09 指示执行下一步。原文按 Unicode 转义保留；本次授权仅覆盖任务 2 实施，不代表 Owner 验收、部署或下一里程碑。

```json
{"instruction":"\u6267\u884c\u4e0b\u4e00\u6b65"}
```

本次复用已交付的合成输入与 M1 启动器，接入实际 HTTP 串联、单命令 IncludeM2、结果报告和原有 CI。操作与结果解释见[运行说明](synthetic-demo-runbook.md)。V0.1 语义、生产接口、权限、Schema 和 canonicalization 保持冻结。

## 实施与验证状态

已接入实际 HTTP 的 Mapping activation、FULL Sync、Issue Snapshot、两次 Build ingestion、异步 Traceability、same-key replay 和 A/B/history 查询。Build 使用同次 M1 的 Project、Locked Manifest 与实测文件摘要；proofDigest 由既有 canonicalizer 计算。独立 Project 的错误事实先以 200 INVALID 保存，再验证 422 TRACEABILITY_INPUT_NOT_VALID；USER 即使具有 ingestion scope 仍返回 403 PROJECT_SCOPE_MISMATCH。

M2DemoReport 仅投影实际响应的安全字段，严格校验布尔值、摘要、路径类型和 Gap code，拒绝 Verified=true。轮询总期限 30 秒，单请求最多 5 秒且不超过剩余期限；失败或未完成报告导致非零退出。默认入口保持 M1，IncludeM2 在原生命周期内增加串联与可读结果；生产 bootJar 不包含 demo。

本地 RED：新报告类型缺失使 compileTestKotlin 产生 3 个 unresolved reference，退出非零。GREEN：在 backend、JDK 21 执行如下命令，退出码均为 0：

```text
./gradlew test --tests '*M2DemoReportTest' --tests '*M1DemoReportTest' --tests '*M1DemoPackagingTest'
./gradlew compileDemoKotlin compileTestKotlin
```

JUnit XML 已核对：M2DemoReportTest 3、M1DemoReportTest 3、M1DemoPackagingTest 7，共 13/13 PASS，0 失败、错误或跳过。scripts/tests/m1-demo.tests.ps1 的原 20 项与 M2 opt-in 1 项共 21/21 PASS；四个 PowerShell 文件 AST 检查通过，git diff --check 退出码 0。

新增 M2DemoIntegrationTest 直接捕获真实 A、A_AGAIN、B 响应 bytes 并断言历史一致、路径、Gap 与 Verified=false。本机未执行 Docker 集成；实际 PostgreSQL/HTTP 与保留 volume 的两次 M2 运行已由下方固定提交的 CI 补证。

## 评审与首轮集成修复

任务评审要求精确校验 B/DEMO-2 的四边顺序，全计划复审要求显式校验其 A=false/B=true 的 Fixed 值。两项均已在场景与真实 HTTP 测试中补齐，局部复审后工程结论 Approved，0 遗留发现；这不是 Owner 验收。

首轮 M1 CI [34306619848](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34306619848) 与 [34306619637](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34306619637) 均失败，各 959 项测试、1 失败、2 既有平台跳过；实施 Subject 为 040e996 / 7988db1。已读取中文 Artifact 10086981300 的失败 XML，确认 M2_USER_INGESTION_NOT_REJECTED；英文日志也确认同一异常。

根因是演示把“有 scope 的 USER”错误地按“缺 scope”断言为 ACCESS_DENIED。既有 TraceabilityIngestAuthorizer 与 BuildProvenance 集成测试规定此路径应为 403 PROJECT_SCOPE_MISMATCH；HTTP 403 本身正确。修复只更正演示的准确错误码，不放宽断言，不改生产权限逻辑。后续 HTTP 返回字段与状态已定向对照既有 DTO、Controller 和集成测试。

修复后 compileDemoKotlin、compileTestKotlin 退出码 0；实际集成 GREEN 由下方修复后固定 Subject 的 CI 提供，上述失败运行与工程复审不替代运行证据。

## 最终实施与 CI Evidence

| 分支 | 最终实施 Subject Commit | M1 CI | M2 CI |
|---|---|---|---|
| Chinese | 8d5354dcf21ae7b506b27f56eae4d044b9beb895 | [34307583566](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34307583566) SUCCESS | [34307583503](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34307583503) SUCCESS |
| English | db98f89ab07e427beda63ac2e9616422f6f38f34 | [34307583091](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34307583091) SUCCESS | [34307583088](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34307583088) SUCCESS |

四条运行的 completed/success 与完整 head_sha 已通过 GitHub API 核对。中文运行创建于 2026-09-09T03:33:30Z，英文为 2026-09-09T03:33:29Z。后续记录提交不是上述实施 Subject。

已下载并读取双语测试 Artifact，各 95 份 full-test-results XML、959 项测试：957 PASS、2 SKIPPED、0 失败/错误。跳过仅为既有 EvidenceArchiveDirectoryAccessReaderTest 的两项 Windows ACL 测试。M2DemoIntegrationTest 1/1、M2DemoReportTest 3/3、M2DemoInputsTest 4/4、M1DemoIntegrationTest 2/2、M1DemoPackagingTest 7/7、M1DemoReportTest 3/3 均通过。

双语 CLI 生命周期检查与保留 volume 复跑步骤成功。逐份读取演示 ZIP：每个分支有三次 M1 PASS 和一次错误口令导致的预期 FAILED，其中两次为 IncludeM2；四份 m2-summary.json 均 10/10 场景 PASS、workingTreeDirty=false，绑定对应 Subject 和不同 runId。A/DEMO-1 为 true/true/false 与四边路径；A/DEMO-2 为 false/false/false、空路径和 ISSUE_COMMIT_MISSING；B 两条均 true/true/false、四边路径和 TEST_RESULT_EVIDENCE_MISSING。A/B ID 不同，历史响应 bytes 稳定、latest=B；USER ingestion 状态为 403，无效事实验证为 422。报告字段 allowlist 与无敏感文本检查通过。真实 bytes 相等断言由上述实际 HTTP 集成测试执行，未将报告布尔值当作独立重放证明。

| Artifact | ID | 生成时间 UTC | 到期 UTC | ZIP SHA-256 |
|---|---|---|---|---|
| Chinese tests | 10087410214 | 2026-09-09T03:43:32Z | 2026-10-09T03:43:31Z | 765555212f61083c9a7208703be7536989951a56d507c39d4ef2bf81fc731933 |
| English tests | 10087413513 | 2026-09-09T03:43:42Z | 2026-10-09T03:43:41Z | d6eef04df93be767fc5e52d3c4d3edc58e858c05e657836c1f2aea85bd66ff78 |
| Chinese demo | 10087409439 | 2026-09-09T03:43:30Z | 2026-10-09T03:43:30Z | 1490d7979dcac9815bdbaadeaed832be1a3c401ff905f5a385e63dfceb1d65b4 |
| English demo | 10087412935 | 2026-09-09T03:43:40Z | 2026-10-09T03:43:40Z | 4dd2767c82eb954cbd5bc7ef9a7effee9abe1fa8b434fbce9cd9211991be6cc8 |

任务评审、全计划评审和 CI 错误码修复复审均无遗留发现。最终实施 Pair Gate 与原子配对推送已完成；记录提交继续执行双语与验收记录校验。上述摘要和定位不表示原始 Artifact 已永久保存。

## 剩余限制

这是合成后端流程，全部 Verified=false；fixture VALID/LOW 不证明真实 GitHub Build。场景 PASS 不等于 Release PASS/BLOCK、完整 MVP 或 Company Ready。历史比较覆盖本次 A 响应，不声称管理员不可修改或任意字段篡改检测。本机没有 Docker，实际运行证据来自 CI。Worker FAILED 与轮询超时的传播路径经代码审阅，本次未单独注入这两类故障来验证 demo 进程退出；不将该项写成运行 PASS。两项平台跳过不计通过，Artifact 有限期；既有 M2.5 性能参考差距和 canonical 摘要范围限制仍保留。

## 下一步执行计划

当前结果：TDR-022 两项任务已完成实施、独立评审与双语 CI 验证；[Owner 审阅记录](../governance/acceptance/records/2026-09-09-tdr-022-m2-demo-review-001.md)为 PENDING。Git 状态：实施 Subject 8d5354d / db98f89 已配对推送，当前记录按双语治理版本化。下一步动作：Owner 审阅 TDR-022-M2-DEMO-REVIEW-001 并对固定 Subject 给出决定。前置条件：Owner 明确决定；查看报告无需新增环境。验收目标：确认合成串联满足当前展示目标，或列出具体条件/调整项，并按既有治理记录决定。
