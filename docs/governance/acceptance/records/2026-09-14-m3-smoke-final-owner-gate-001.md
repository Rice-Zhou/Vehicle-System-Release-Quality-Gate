---
acceptanceId: M3-SMOKE-FINAL-OWNER-GATE-001
subject: 单设备 Smoke 最终工程候选
subjectCommit: 9c9f97d9ebe5aadc64089530024912620eb2deb0
pairedSubjectCommit: b5ed45d4cede1bb7f48f815da838febd647f1f80
branch: docs/m2-issue-traceability-design
status: APPROVE
submittedAt: 2026-09-14T01:47:54Z
owner: Project Owner
decisionAt: 2026-09-14T02:41:13Z
---

# 单设备 Smoke 最终候选验收

## Scope

本新候选覆盖已实施的单设备 Smoke Tasks 1–7：APK、身份、Run/租约、本地 Evidence、Event/Result、主机 Agent、串联、真机正常/确定 FAIL、受控恢复与最终工程/证据复核。固定产品配对如上；设计基线、实际恢复 checkout 和此前记录 HEAD 在[最终复核](../../../m3/single-device-smoke-final-review.md)中分别绑定。记录提交与产品代码分离。

不包含完整 M3、物理 USB/ADB 断连、完整 Crash/ANR、断电、车辆 Release Quality Gate 或 Company Evidence Archive。Quality 保持 `NOT_EVALUATED`、`verified=false`。旧设计批准和历史验收不改写；本次验收批准不授权 merge、Tag、release 或 deploy。

## Evidence

Type：固定 Subject 的 CI/Artifact、保留的真机/恢复证据与独立复审报告。六产品 Run/Artifact 精确映射、bytes 摘要与历史记录链接见[最终复核](../../../m3/single-device-smoke-final-review.md)。产品 CI 执行于 `2026-09-10`，恢复执行于 `2026-09-11`；`2026-09-14` 重核仅重读留存 bytes 与实时 metadata，没有重新下载或新产品/设备执行。

Availability：选定产品 Artifact 在 `2026-09-14T01:36:32Z` 的实时 metadata 核对中可访问且未过期，最早 `2026-10-10T13:01:50Z` 到期。Runtime Owner / Controller 当前负责私有材料访问；私有固定保留期及未来可访问性为 `UNKNOWN`。Owner Authorization：已收到下列明确对话批准，机器身份验证仍为 `UNKNOWN`。原始 Payload、环境身份或凭据不入库。

| Type / Locator | Generated At | Subject Commit / Digest or summary |
|---|---|---|
| 产品 CI / 最终复核中的六条准确 Run 与 Artifact | 准确创建时间保留于链接产品 metadata；实时核对 `2026-09-14T01:36:32Z` | 固定产品配对；六条 `completed/success`；留存 ZIP bytes/CRC/路径与实时 size/digest 一致 |
| 真机 / [真机记录](../../../m3/real-device-smoke-verification.md) | 历史执行 `2026-09-10`；准确报告 locator 保留于原记录 | ZH 产品；正常 PASS / 确定 FAIL；四份 Payload 与 Result JCS/receipt/journal 核对 |
| 恢复 / [恢复记录](../../../m3/smoke-recovery-verification.md) | `2026-09-11T07:57:55Z`–`2026-09-11T08:52:55Z` | 实际 checkout 独立绑定；原 wrapper FAIL 与同 Run 补验 PASS 并存 |
| 工程复审 / `whole-implementation-review.md` | 文件 UTC 时间和固定摘要见最终复核 | `APPROVE_FINAL`；完整 Tasks 1–7，无新增 blocker，P3 延期 |
| 证据核对 / `evidence-reconciliation.md` | 文件 UTC 时间和固定摘要见最终复核 | `PASS`；六份留存 ZIP、原真机/恢复材料与冷备 bytes |

私有报告 locator 与固定 SHA-256 记录于最终复核。其产品绑定描述复核范围，不表示每份原始报告均内嵌 Subject 字段。

### Owner Authorization Receipt

本项目当前对话中的 Owner 回复，登记时间 2026-09-14T02:39:19Z。上一问题已明确请求批准本候选及记录中的残余风险。原文：

> APPROVE M3-SMOKE-FINAL-OWNER-GATE-001，Subject 9c9f97d / b5ed45d

完整 Subject 为本记录固定产品配对。本 receipt 保存明确的对话决定，不声称已验证签名或机器认证 Owner 身份。后续决定提交将引用本 receipt 的固定版本；不授权 merge、Tag、release、deploy 或扩展范围。

Owner 明确回复已保存于[固定 receipt](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/blob/1f33aa6c3c7c2c1083dee633ee2b3d6c17f544dc/docs/governance/acceptance/records/2026-09-14-m3-smoke-final-owner-gate-001.md)。本次由录入者登记对话决定，Owner 身份的机器验证仍为 `UNKNOWN`；批准仅限固定产品配对及保留的残余风险。

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| 完整 Tasks 1–7 工程复审 | `PASS` | 独立 `APPROVE_FINAL` 报告 | 仅工程，Owner 独立决定 |
| 准确产品 CI 与选定 Artifact 重核 | `PASS` | 六条运行映射与独立留存 bytes 报告 | `2026-09-10` 执行、`2026-09-14` 重核；skip 与 ZIP 外 JAR 边界保留 |
| 产品配对与记录来源绑定 | `PASS` | `source-binding.json` | `467` 个 non-Markdown blob/mode 一致；记录仅 Markdown 变化 |
| 真机正常 / 确定 FAIL | `PASS` | 历史真机记录与离线重核 | 原始 Case PASS / FAIL 保留；scenario PASS 不是 Release PASS |
| 隔离冷备恢复 | `PASS` | 恢复记录与本轮 bytes 复算 | 源与备份各 `1623` files / `71475501` bytes；未新执行恢复 |
| A/B 原 wrapper | `FAIL` | 原报告保留 | 独立补验 PASS 不改写原失败 |
| A/B 同 Run 补验 | `PASS` | 恢复记录与留存材料重核 | A 持久化 ACK；B SERVER TIMEOUT 与晚写 `409` 拒绝，无假 ACK |
| 物理 ADB / 完整 Crash/ANR / 断电 | `UNKNOWN` | 范围及残余风险 | 无满足条件的执行证据 |
| Company Evidence Archive | `N/A` | 排除范围 | 仅本地受控证据 |
| Owner 决定 | `PASS` | 上述固定 receipt | `M3-SMOKE-FINAL-OWNER-GATE-001` 为 `APPROVE`；机器身份验证仍 UNKNOWN |

## Residual Risks

| Risk | Impact | Owner | Mitigation / Review Condition |
|---|---|---|---|
| P3 `OPEN/NON-BLOCKING/EXPLICITLY DEFERRED` | 后续 Collector 失败可能覆盖首因，但结果仍为 ERROR | Controller | 保留已接受延期，不声称修复 |
| 有界设备/恢复、Windows skips/持久化与 `NotSigned` ZIP | 不能证明全故障覆盖、通用持久化或发布者签名 | Runtime Owner | 审阅本有限候选时采用最终复核边界 |
| M2 性能、canonical 覆盖与 Artifact 到期 | 参考目标未达，非主路径摘要覆盖有限，证据可能到期 | Owner / Controller | 保留最终复核中准确测量/到期及历史限制 |
| 私有证据保留期 `UNKNOWN` | 未来可能无法独立复核 | Runtime Owner | Owner 审阅时确认受控访问及保留安排 |
| 原 wrapper 失败与 A 首轮根因 `UNKNOWN` | 原执行不可改记 PASS | Controller | 保留原 FAIL 与独立同 Run 补验事实 |

## Decision Reason

Project Owner 针对上一条明确包含记录中残余风险的验收问题，批准本准确候选及产品配对。接受有界 Smoke 实施，同时保留 P3 延期、原 wrapper FAIL、物理 ADB/完整 Crash/ANR/断电覆盖 UNKNOWN、性能与 canonical 限制及私有证据未来保留不确定性。对话 receipt 登记该决定，不声称机器认证身份，也不消除任何 UNKNOWN/FAIL。本批准不等于完整 M3/Company 验收或 merge、Tag、发布、部署授权。

## Follow-up Actions

| Action | Owner | Due / Trigger | Closure Condition | Completion Evidence |
|---|---|---|---|---|
| 复用现有 runbook 核对演示交接 | Implementation Owner | 下一步执行指令；保持已验收范围 | 明确启动前提、正常/FAIL 演示步骤及结果/证据定位，不新增基础设施 | 引用现有 runbook 的已复核交接清单 |

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| 2026-09-14T01:47:54Z | PENDING | PENDING | 提交固定产品最终工程候选，完成独立证据核对并保留原失败与残余风险 | PENDING |
| 2026-09-14T02:41:13Z | APPROVE | Project Owner | 批准固定产品的有界 Smoke，明确保留记录中的残余风险与原失败。 | 1f33aa6c3c7c2c1083dee633ee2b3d6c17f544dc |
