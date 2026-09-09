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

已接入实际 HTTP 的 Mapping activation、FULL Sync、Issue Snapshot、两次 Build ingestion、异步 Traceability、same-key replay 和 A/B/history 查询。Build 使用同次 M1 的 Project、Locked Manifest 与实测文件摘要；proofDigest 由既有 canonicalizer 计算。独立 Project 的错误事实先以 200 INVALID 保存，再验证 422 TRACEABILITY_INPUT_NOT_VALID；USER 即使具有 ingestion scope 仍返回 403 ACCESS_DENIED。

M2DemoReport 仅投影实际响应的安全字段，严格校验布尔值、摘要、路径类型和 Gap code，拒绝 Verified=true。轮询总期限 30 秒，单请求最多 5 秒且不超过剩余期限；失败或未完成报告导致非零退出。默认入口保持 M1，IncludeM2 在原生命周期内增加串联与可读结果；生产 bootJar 不包含 demo。

本地 RED：新报告类型缺失使 compileTestKotlin 产生 3 个 unresolved reference，退出非零。GREEN：在 backend、JDK 21 执行如下命令，退出码均为 0：

```text
./gradlew test --tests '*M2DemoReportTest' --tests '*M1DemoReportTest' --tests '*M1DemoPackagingTest'
./gradlew compileDemoKotlin compileTestKotlin
```

JUnit XML 已核对：M2DemoReportTest 3、M1DemoReportTest 3、M1DemoPackagingTest 7，共 13/13 PASS，0 失败、错误或跳过。scripts/tests/m1-demo.tests.ps1 的原 20 项与 M2 opt-in 1 项共 21/21 PASS；四个 PowerShell 文件 AST 检查通过，git diff --check 退出码 0。

新增 M2DemoIntegrationTest 已编译，直接捕获真实 A、A_AGAIN、B 响应 bytes 并断言历史一致、路径、Gap 与 Verified=false；尚未在本机执行。独立评审、exact-commit CI 与保留 volume 的两次 M2 运行仍待完成；尚未创建 Owner 待验收记录。

## 剩余限制

这是合成后端流程，全部 Verified=false；fixture VALID/LOW 不证明真实 GitHub Build。场景 PASS 不等于 Release PASS/BLOCK、完整 MVP 或 Company Ready。历史比较覆盖本次 A 响应，不声称管理员不可修改或任意字段篡改检测。本机没有 Docker，实际 PostgreSQL/HTTP 和保留 volume 复跑需要既有 CI 补证。

## 下一步执行计划

当前结果：任务 2 已实现，本地可执行验证通过，独立评审与真实集成证据待完成。Git 状态：工作区修改尚未提交。下一步动作：完成独立评审、配对提交和 exact-commit CI。前置条件：既有 GitHub CI；本机完整重跑另需已有容器环境和仓库外演示口令。验收目标：完整链、缺边链、历史稳定、重放、权限拒绝、Verified=false 和失败非零退出具有真实证据；完成后提交 Owner 审阅。
