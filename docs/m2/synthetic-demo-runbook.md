# M2 合成串联演示操作说明

本流程在同次 M1 文件校验、Lock 与导出后，执行合成 Issue Sync、Issue Snapshot、Build Facts、Traceability 和历史查询。它展示已有后端能力如何串联；不代表真实 GitHub Build、车辆测试或最终 Quality Engine 结论。

## 运行

沿用 [M1 运行前准备](../m1/demo-runbook.md)：已有 PowerShell 7、Git、JDK 21、本机 Docker Compose，以及仓库外保存的演示数据库口令。口令设置方式见 M1 说明，不写入命令、仓库或报告。无需新增服务、Company 或云资源。

在仓库根目录运行：

```powershell
pwsh -NoProfile -File scripts/demo/run-m1.ps1 -IncludeM2
```

不带 IncludeM2 仍只运行原 M1。M2 复用同一专用 vsrqg_demo 数据库、Compose project vsrqg-m1-demo 和保留 volume；既有 Worker 可能处理此演示库中的残留任务，因此不要把真实业务数据放入该库。每次运行创建新的合成 Project、身份与记录，不覆盖旧结果。

## 如何理解结果

成功退出码为 0；任一阶段失败为非零。不要仅凭 M1 summary.json 的 PASS 判断 M2 成功：还必须有同次 m2-summary.json 的成功结果和总命令退出码 0。

| Snapshot | Issue | Fixed | Included | Verified | 解释 |
|---|---|---|---|---|---|
| A | DEMO-1 | true | true | false | 四边路径完整，仍缺 TEST_RESULT_EVIDENCE_MISSING |
| A | DEMO-2 | false | false | false | 没有 ISSUE_COMMIT，报告 ISSUE_COMMIT_MISSING |
| B | DEMO-1 | true | true | false | 原完整路径保留，仍缺测试 Evidence |
| B | DEMO-2 | true | true | false | 新 Build 事实补齐路径，仍缺测试 Evidence |

随后重新查询 A，完整响应 bytes 和 contentDigest 必须保持原样；latest 指向 B。历史稳定性是既有 Snapshot 语义的演示，不承诺管理员不可修改或任意字段篡改检测。

classification=SYNTHETIC_DEMO、proofKind=SYNTHETIC_FIXTURE 明确表示合成输入。独立 fixture validator 的 VALID/LOW 仅表示输入符合演示样例；GitHub 形状的样例 locator 不构成外部证据，也不会被访问。所有 Issue 的 Verified 均为 false。场景 PASS 表示断言符合预期；它不是 Release PASS/BLOCK。

## 报告与失败

同次输出目录为 backend/build/demo/m1/<runId>/，保留 M1 summary.json、manifest.json，增加 m2-summary.json。可查找最新 M2 报告：

```powershell
Get-ChildItem backend/build/demo/m1 -Filter m2-summary.json -Recurse |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1 |
    Get-Content
```

报告保存运行/代码标识、实际 HTTP 状态、场景状态、Snapshot 标识与摘要、Issue 路径及 Gap；不保存 Token、原始身份、口令、连接信息、原始 HTTP 文本或样例 locator。workingTreeDirty=true 时 codeCommit 只表示 HEAD，不证明未提交的修改。

HTTP 失败、Worker FAILED、轮询超时、无效字段和结果不符都必须明确失败，不能当作缺边场景成功。缺边是成功计算出的业务结果；无法读取或未完成计算是执行失败。保留本次报告用于定位，不修改数据库历史或删 volume 来制造成功。

## 服务与验收范围

结束时只停止本次启动的演示服务，保留 volume 和报告；执行前已经运行的服务保持运行。完整操作规则沿用 M1 说明。再次执行使用同一仓库外口令并创建新 runId。

[TDR-022](../v0.2/tdr/TDR-022-synthetic-m2-demonstration.md)与[实施计划](../superpowers/plans/2026-09-08-synthetic-m2-demonstration.md)定义范围。工程验证和 Owner 接受是不同事项；本说明不授权 merge、Tag、发布、部署或真实 Provider。

工程验证结果见[实施记录](2026-09-08-synthetic-demo-walkthrough.md)，阶段展示接受情况见[Owner 审阅记录](../governance/acceptance/records/2026-09-09-tdr-022-m2-demo-review-001.md)（APPROVE）。

已有运行结果可按[离线报告说明](../demo/offline-report-runbook.md)生成便于展示的双语 HTML；生成报告不重新运行演示。
