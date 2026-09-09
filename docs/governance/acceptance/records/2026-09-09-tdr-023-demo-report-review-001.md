---
acceptanceId: TDR-023-DEMO-REPORT-REVIEW-001
subject: TDR-023 M1/M2 离线只读演示报告
subjectCommit: c5400fd33e6fa502f141f6ce25bf950a9b350fb6
pairedSubjectCommit: 1fc37d4f795f815a7643c3791d7fc4878d6f1681
branch: docs/m2-issue-traceability-design
status: PENDING
submittedAt: 2026-09-09T06:43:16Z
owner: PENDING
decisionAt: PENDING
---

# 离线演示报告 Owner 审阅记录

## Scope

审阅 TDR-023 任务 1：从同次既有 M1/M2 JSON 生成单文件中英文只读 HTML，展示 Release/Manifest、Artifact、Issue 的 Fixed/Included/Verified、A/B 路径与 Gap、原历史检查及失败状态。包括生成器、原样合成样例、测试、既有 CI 集成与操作说明。固定实施 Subject 如 metadata；本记录提交与产品变更分离。

当前目标是可展示成品。未增加数据库、在线服务、依赖或 Company 资源；报告只投影已记录事实，不执行质量决定或重新验证历史。Owner 的“执行下一步”授权实施，不代表本记录 APPROVE，也不授权 merge、Tag、发布、部署、真实 Provider 或下一里程碑。

## Evidence

- [TDR-023](../../../v0.2/tdr/TDR-023-offline-demo-report.md)、[实施计划](../../../superpowers/plans/2026-09-09-offline-demo-report.md)、[操作说明](../../../demo/offline-report-runbook.md)。
- [验证记录](../../../demo/2026-09-09-offline-report-verification.md)记录原样例来源、测试、实际浏览器、独立评审、准确提交 CI 与 Artifact 核对结果。源样例 commit 8d5354d 与本次生成器 Subject 不混用。
- [Chinese M1 CI](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34320107876)、[Chinese M2 CI](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34320107724)。
- [English M1 CI](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34320107781)、[English M2 CI](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/34320107709)。四条运行均创建于 2026-09-09T06:40:04Z，head_sha 分别对应 metadata 固定 Subject。

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| 输入关联、类型及失败语义 | PASS | 25 项 node:test / 独立评审 | 同次关联；保留部分 FAILED；显式 null 数组拒绝；原输入与旧输出不覆盖 |
| 双语离线展示 | PASS | Edge 152 实际浏览器 8/8 | 正常、最小失败、部分失败、特殊文本各 zh/en；无请求、脚本执行或页面错误；390px 窄屏检查 |
| 实施提交 CI 与报告来源 | PASS | 四条准确提交 CI / 验证记录 | 全部成功；16 份 HTML 与同目录 JSON 再生成完全一致；保留源 FAILED |
| 工程审查及双语配对 | PASS | 最终 Approved / Pair Gate | 三项首轮问题和一项最终类型问题均关闭；非 Markdown 一致 |
| 当前阶段展示目标是否满足 | PENDING | 等待 Owner 决定 | 自动检查与工程 Approved 不代替 Owner 验收 |

## Residual Risks

全部是合成演示，Verified=false；REPORT_RENDERED 只表示呈现成功，源 FAILED 仍显示 FAILED。Manifest 文件是注册输入，Lock/导出结论来自原场景状态；本工具不产生新的业务权威。浏览器呈现原历史检查，不重新访问数据库或执行重放。

原 M2.5 创建 Run P95 1467/1477 ms 高于 1000 ms 参考目标、canonical 摘要覆盖限制、既有 Windows ACL 跳过及 Worker FAILED/超时未单独故障注入的边界保持不变。报告未建立 Company 性能或任意字段篡改可检测的证明。

CI/Artifact 对照已完成，准确到期时间与 ZIP 摘要见验证记录。Artifact 有限保留；原样例三个 JSON 已随 Git 保存，其他资料按既有 GitHub 治理处理，无新归档资源前置条件。

## Decision Reason

PENDING

## Follow-up Actions

| Action | Owner | Due / Trigger | Closure Condition | Completion Evidence |
|---|---|---|---|---|
| 审阅固定 Subject 的正常及失败报告 | Project Owner | 工程证据收口后 | 明确决定当前离线展示是否满足阶段目标 | 限定 Acceptance ID 与 Subject 的 Owner 决定及后续独立记录 |

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| 2026-09-09T06:43:16Z | PENDING | PENDING | 固定离线报告实施 Subject 并整理审阅证据；未代录 Owner 批准。 | PENDING |
