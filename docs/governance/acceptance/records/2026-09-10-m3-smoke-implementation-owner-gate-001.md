---
acceptanceId: M3-SMOKE-IMPLEMENTATION-OWNER-GATE-001
subject: 单设备 Smoke 串联工程候选
subjectCommit: dbd59a48ba9c7dc9279588e046182dbf97ab22ef
pairedSubjectCommit: bc1f62637ac9f9357912abf85961a65cb0035852
branch: docs/m2-issue-traceability-design
status: PENDING
submittedAt: 2026-09-10T11:00:12Z
owner: PENDING
decisionAt: PENDING
---

# 单设备 Smoke 串联工程候选验收记录

## Scope

本记录提交 Task 7 单设备演示的工程候选：正式 API 串联、严格本地配置、Agent 完成确认、独立 CI_FIXTURE 与操作手册。真实设备正常/确定 FAIL、断连/Agent 重启及本地数据库/Payload 恢复尚未交付，明确列为 UNKNOWN；不声称全部 Task 7、M3、Release Quality Gate 或 Company 完成。

原设计批准不替代本固定实施 Subject 的 Owner 决定。subjectCommit 为实施提交，后续承载本记录的提交与它分离；不授权 merge、Tag、发布、部署或真实 Provider。

## Evidence

- 固定[实施 Subject](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/dbd59a48ba9c7dc9279588e046182dbf97ab22ef)与[配对实施 Subject](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/commit/bc1f62637ac9f9357912abf85961a65cb0035852)。
- [串联工程记录](../../../m3/single-device-smoke-verification.md)维护实际测试、复审、准确 CI/Artifact、摘要、未覆盖项和下一步。[运行手册](../../../m3/single-device-smoke-runbook.md)说明受控操作。
- [实施计划](../../../superpowers/plans/2026-09-09-single-device-smoke-implementation.md)、[TDR-024](../../../v0.2/tdr/TDR-024-single-device-smoke-execution.md)与[TDR-025](../../../v0.2/tdr/TDR-025-local-demo-evidence-payload.md)保持既有权威边界。
- 2026-09-10 只读预检：Owner 指定的 Android 车机通过 ADB 授权，API 34；私有设备配置与 APK 副本已准备。Owner 明确确认尚无本地数据库。原始序列号、环境值、配置与凭据不入库。

## Acceptance Checks

| Check | Result | Evidence | Notes |
|---|---|---|---|
| 本地组件与包装层验证 | PASS | 工程记录的命令、XML 与日志 | 各轮分别记录，不累加为一次执行；本地无数据库的整链失败另列。 |
| 最终独立复审与 CI 修复范围复审 | PASS | 工程记录 | 最终工程复审及最后一轮 CI 修复范围复审通过，无新 Critical/Important。 |
| 准确双语 M1/M2/M3 CI 与 Artifact | PASS | 工程记录 | 六条准确实施 CI 成功，六份选定 Artifact 已独立核对；初次失败保留。 |
| 本地数据库运行前提 | PASS | [本地运行验证](../../../m3/local-runtime-verification.md) | 后续已获授权准备原生 PostgreSQL，两轮 Backend 健康和正常重启通过；历史 Docker 缺失整链失败保留，不代表真机或备份恢复通过。 |
| 真实设备正常与确定 FAIL 串联 | UNKNOWN | 实际未执行 | API34 预检不是安装或 Run→Result→Evidence 证明。 |
| 断连与 Agent 重启现场恢复 | UNKNOWN | 实际未执行 | 单元测试不代替现场注入、租约、终态及恢复 bytes。 |
| 本地数据库与 Payload 配套恢复 | UNKNOWN | 实际未执行 | 不借用 M2.5 恢复报告证明 M3，必须实际恢复并复算。 |
| Owner 决定 | PENDING | 本记录 | 未收到对此固定实施候选的验收决定。 |

## Residual Risks

真实设备及恢复 Evidence 尚不存在，由 Implementation Owner 在明确设备授权和本地运行条件具备后补充；未关闭项不得改为 PASS。CI_FIXTURE 不证明真实 ADB 安装、UI 或车机行为。初次 CI 失败与本机 Docker 缺失必须保留，后续成功只属于其准确 Subject。

Task 6 首因诊断覆盖 P3 继续 OPEN/NON-BLOCKING/EXPLICITLY DEFERRED，APK 既有 lint/构建警告及单账户设备锁、Windows 持久化、spool 保留限制见工程记录。历史 M2.5 创建 Run P95 未达 1000 ms 参考目标、canonical 非主路径覆盖限制与最早 2026-10-07 Artifact 到期限制继续保留；当前 Artifact 到期以工程记录中的实际 metadata 为准。过期或不可访问时对应 Evidence 应为 UNKNOWN。

## Decision Reason

PENDING

## Follow-up Actions

| Action | Owner | Due / Trigger | Closure Condition | Completion Evidence |
|---|---|---|---|---|
| 准备本地演示运行前提 | Owner / Implementation Owner | 已获后续指令授权并完成 | 本地数据库连接、Backend 健康与正常停止/启动验证通过，不启用 Company 或真实 Provider。 | [本地运行验证](../../../m3/local-runtime-verification.md)。 |
| 完成真机与恢复检查 | Implementation Owner | 设备授权及运行/恢复条件齐备 | 按已接受计划实测正常/FAIL 关联、恢复注入时序、租约、终态、bytes 及未重复安装；未执行部分继续 UNKNOWN。 | 正式 API、下载 bytes/SHA-256 与实际恢复记录。 |
| 固定候选 Owner 决定 | Project Owner | 复核本 Subject、证据及残余风险后 | 保存明确决定及其授权定位；不自动合并或发布。 | 独立决定记录提交。 |

## Decision History

| At | Status | Owner | Reason | Commit |
|---|---|---|---|---|
| 2026-09-10T11:00:12Z | PENDING | PENDING | 提交单设备串联工程候选，保留真实设备与恢复未完成事实，等待固定 Subject 的独立决定。 | PENDING |
