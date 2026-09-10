---
acceptanceId: M3-SMOKE-REAL-DEVICE-001
subject: 单设备正常与确定 FAIL 真机 Smoke 候选
subjectCommit: 9c9f97d9ebe5aadc64089530024912620eb2deb0
pairedSubjectCommit: b5ed45d4cede1bb7f48f815da838febd647f1f80
branch: docs/m2-issue-traceability-design
status: PENDING
submittedAt: 2026-09-10T13:00:46Z
owner: PENDING
decisionAt: PENDING
---

# 单设备真机 Smoke 候选验收记录

## Scope

本记录仅提交已固定产品 Subject 的正常与确定 FAIL 真机链路，含正式 API Run→Result→LOG/PNG、持久化 RESULT_ACKED、独立 bytes/摘要复核及三项兼容修复。实际设备执行使用配对 ZH Subject；英文候选非 Markdown committed blobs 与之相同。

连接中断、Agent 重启与数据库+Payload 配套恢复仍 UNKNOWN；不代表全部 Task 7、M3、Release Quality Gate 或 Company。旧 M3-SMOKE-IMPLEMENTATION-OWNER-GATE-001 保留原 Subject 与 PENDING，不修改历史或代替 Owner 决定。本记录与产品提交分离，不授权 merge、Tag、发布或部署。

## Evidence

- 固定 [Subject](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/9c9f97d9ebe5aadc64089530024912620eb2deb0) / [paired Subject](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/b5ed45d4cede1bb7f48f815da838febd647f1f80)。
- [真机验证记录](../../../m3/real-device-smoke-verification.md) 保存两次 Run/Attempt、四项 Evidence ID/size/SHA、summary SHA、历史失败、分轮 RED/GREEN、独立方法与受控定位。材料日期为 2026-09-10；访问责任人为 Runtime Owner / Controller，原始日志、截图、配置与凭据不入库。
- [实施计划](../../../superpowers/plans/2026-09-09-single-device-smoke-implementation.md)、[TDR-024](../../../v0.2/tdr/TDR-024-single-device-smoke-execution.md)、[TDR-025](../../../v0.2/tdr/TDR-025-local-demo-evidence-payload.md)。
- 准确最终配对 Subject 的 M1/M2/M3 六条 CI 与六份选定 Artifact 均由 Controller 独立核验 PASS，准确定位、size/SHA/expiry 与验证边界见真机记录。

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| 正常与确定 FAIL 真机运行 | PASS | 真机记录中的两个不同 Run | REAL_DEVICE, clean Subject, COMPLETED, Case PASS/FAIL, wrapper exit 0 |
| 回执与实际 Evidence | PASS | VerifyRealEvidence / 四项 Evidence SHA | JCS, receipt, journal RESULT_ACKED, LOG/PNG bytes, PNG decode |
| 兼容修复与 I1 闭合 | PASS | 分轮测试与独立范围复审 | 最终双语 Agent 各100项全通过；I1 CLOSED，quality APPROVED |
| 准确双语 M3 CI / Artifact | PASS | 真机记录 CI 表 | 各128项、0失败/错误、1项Windows专用测试在Linux跳过 |
| 准确双语 M2 CI / Artifact | PASS | 真机记录 CI 表与受控核验摘要 | 各 12/12 checks PASS；start P95 未达参考目标，M2 恢复不替代 M3 |
| 准确双语 M1 CI / Artifact | PASS | 真机记录 CI 表与受控核验摘要 | 每份 1138 tests、0 failures/errors、3 Windows ACL skips；135 retainedReports bytes/hash 一致，Artifact 外两份 JAR 未独立复算 |
| 受控进程收尾 | PASS | final-runtime-stopped.txt | PostgreSQL / Backend stopped; 55432/58443 无监听；全部数据保留 |
| 连接中断 / Agent 重启 | UNKNOWN | 尚未注入 | 单测不代替现场时序与租约验证 |
| 数据库+Payload 配套恢复 | UNKNOWN | 尚未执行 | 普通停止/启动不等于恢复 |
| Owner decision | PENDING | 本记录 | 未收到对新固定 Subject 的验收决定 |

## Residual Risks

选定 Artifact 已核验；M1 的 3 项 Windows ACL 测试在 Linux 跳过，本轮未声称本机执行；Artifact 外两份 JAR 未独立复算。缺失、过期或不可访问的 Evidence 应为 UNKNOWN。受控原始 Evidence 需由 Runtime Owner 提供访问。尚未执行的连接中断、Agent 重启和配套恢复由 Implementation Owner 按明确授权与恢复条件补充，不借用普通数据库重启或 CI_FIXTURE 证明。

P3 OPEN/NON-BLOCKING/EXPLICITLY DEFERRED、APK 既有 warnings、单账户锁、Windows 持久化/spool 限制保留。M2.5 P95 未达参考目标、canonical 非主路径覆盖与最早 2026-10-07 历史到期约束继续存在；当前 Artifact 实际到期单独记录。Case PASS/FAIL 不产生 Release Quality，NOT_EVALUATED 与 verified=false 保留。

## Decision Reason

PENDING

## Follow-up Actions

| Action | Owner | Due / Trigger | Closure Condition | Completion Evidence |
|---|---|---|---|---|
| 唯一下一步：Task 7 Step 5 | Implementation Owner | 明确注入/设备授权与受控备份副本 | 连接中断、Agent重启、DB+Payload恢复，无重复安装/晚写/假PASS | 时序、租约/终态、恢复 bytes/SHA-256 |
| 固定候选 Owner 决定 | Project Owner | 复核 Subject、证据与风险后 | 明确决定与不可变授权定位 | 独立决定记录提交 |

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| 2026-09-10T13:00:46Z | PENDING | PENDING | 提交正常与确定 FAIL 真机候选，保留恢复未完成事实，等待固定 Subject 的独立决定。 | PENDING |
