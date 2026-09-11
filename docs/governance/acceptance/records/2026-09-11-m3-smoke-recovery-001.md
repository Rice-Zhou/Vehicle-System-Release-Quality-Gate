---
acceptanceId: M3-SMOKE-RECOVERY-001
subject: 单设备 Smoke 受控恢复候选
subjectCommit: 9c9f97d9ebe5aadc64089530024912620eb2deb0
pairedSubjectCommit: b5ed45d4cede1bb7f48f815da838febd647f1f80
branch: docs/m2-issue-traceability-design
status: PENDING
submittedAt: 2026-09-11T08:55:28Z
owner: PENDING
decisionAt: PENDING
---

# 单设备 Smoke 受控恢复候选验收

## Scope

本候选仅包含固定产品 Subject 的 Task 7 Step 5：停止 PostgreSQL 后的数据库与 Payload 冷备、新隔离副本恢复、真实 Agent 在 Server 接受后且持久化 ACK 前重启、自有 Backend 停服后的租约超时及晚写拒绝、同 Run 独立补验与清理。实际执行使用 ZH clean checkout `9a569258c3d22bdbe50b7286bdcb8740ae02541d`，配对 EN `5ac9a2047d8d0ea7bf5ddfcbb75c1822378eaa0d`；相对产品 Subject 仅 Markdown 差异。

不包含物理 USB/ADB 断连、设备重启、在线备份、跨数据库版本恢复、Company Evidence Archive、完整 M3 或发布验收。Task 7 Step 6 最终复核尚待完成。本记录不修改旧验收记录、Subject 或 Owner 决定，不授权合并、Tag、发布或部署。

## Evidence

以下条目 Type 均为受控真实执行报告；Locator 相对私有根 `recovery-20260911/`，精确 SHA-256 和过程证据见[恢复验证记录](../../../m3/smoke-recovery-verification.md)。本次执行来源映射至实际 checkout `9a569258c3d22bdbe50b7286bdcb8740ae02541d`，固定产品绑定为本记录候选；各报告原有字段不改写，不表示每份报告均内嵌 Subject 字段。Availability：Runtime Owner / Controller 当前可访问；保留期及未来可访问性为 `UNKNOWN`。Owner Authorization：`UNKNOWN`，尚无验收决定授权。原始设备配置、凭据、Payload 与日志不入库。

| Locator | Generated At | Digest / Summary |
|---|---|---|
| `cold-copy-manifest.json` | `2026-09-11T07:57:55Z` | 停止状态冷备，1623 files / 71475501 bytes |
| `restore-report.json` | `2026-09-11T08:02:51Z` | 原 2 Run / 4 Evidence 正式读取与下载校验通过 |
| `a-supplement/supplement-result.json` | `2026-09-11T08:45:18Z` | 同 Run ACK 重放补验 exit 0 / PASS；原 wrapper FAIL 保留 |
| `b-supplement/supplement-result.json` | `2026-09-11T08:46:31Z` | 同 Run 超时与晚写补验 exit 0 / PASS；原 wrapper FAIL 保留 |
| `restore-post-exercises-report.json` | `2026-09-11T08:52:00Z` | 再次恢复验证通过，与首次报告 SHA-256 相同 |
| `final-cleanup-check.json` | `2026-09-11T08:52:55Z` | 源与备份全文件哈希未变；自有服务及进程已停止 |

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| 冷备与隔离副本恢复 | `PASS` | 两次恢复报告与冷备清单 | 原 2 Run / 4 Evidence 完整；未覆盖源数据 |
| A 原 wrapper | `FAIL` | 原演练及摘要 | evidenceIds Set 顺序误判；原退出码与摘要不改写 |
| A 同 Run 恢复补验 | `PASS` | A supplement | Server 接受后重启；持久化 ACK；重启 install/launch 各 0；Result/audit/outbox 无重复 |
| B 原 wrapper | `FAIL` | 原演练失败报告 | input_digest 从初始 null 到终态生成值被误判 |
| B 同 Run 超时与晚写补验 | `PASS` | B supplement | SERVER TIMEOUT；409 晚写拒绝；重放 install/launch 各 0；终态不变 |
| 留存上传 Evidence | `PASS` | 两项补验下载校验 | B 的留存 LOG/PNG 不属于 SERVER TIMEOUT Result 的空 Evidence 引用集合 |
| 历史数据及清理 | `PASS` | 最终恢复与清理报告 | 1623 文件哈希未变；自有服务停止 |
| 物理 ADB 断连 | `UNKNOWN` | 本轮未执行 | Backend 连接中断不能替代物理断连证据 |
| Task 7 Step 6 最终复核 | `UNKNOWN` | 后续独立整体复核 | 本记录不预填文档 CI 或最终复核通过 |
| Company Evidence Archive | `N/A` | Scope | 本次仅本地受控演练 |
| Owner 决定 | `PENDING` | `N/A` | 等待 Owner 复核 |

## Residual Risks

| Risk | Impact | Owner | Mitigation / Review Condition |
|---|---|---|---|
| 单设备、有界故障注入 | 不能推广至完整故障矩阵或物理 ADB 恢复 | Runtime Owner | 扩大范围时另行明确授权与证据 |
| 原私有 wrapper 误报及 A 首轮根因 UNKNOWN | 不能将原执行历史改记 PASS | Controller | 保留原 FAIL、独立补验与复核；A 首轮根因保持未定 |
| 私有材料保留期未固定 | 未来不可访问时证据为 UNKNOWN | Runtime Owner | Owner 复核时确认可访问性及保留安排 |

## Decision Reason

`PENDING`

## Follow-up Actions

| Action | Owner | Due / Trigger | Closure Condition | Completion Evidence |
|---|---|---|---|---|
| Task 7 Step 6 最终整体复核 | Controller / Independent Reviewer | 本记录提交后 | 固定来源与失败历史完整，双语文档校验及整体复核完成 | 后续固定 commit 与复核报告 |
| 候选验收决定 | Owner | 完成最终复核并可访问证据时 | 对本记录明确作出决定 | 新 commit 更新决定字段并追加历史 |

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| 2026-09-11T08:55:28Z | PENDING | PENDING | 提交受控恢复候选，保留原失败与同 Run 独立补验，等待最终复核及 Owner 决定 | PENDING |
