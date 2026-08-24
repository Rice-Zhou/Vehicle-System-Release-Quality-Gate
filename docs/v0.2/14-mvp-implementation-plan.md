# 14 — Six-Month MVP Implementation and Acceptance Plan

## 1. 目标

本计划按可验收成果组织，不干涉类、函数和内部编码方式。容量基线是一个主要开发者在 24 周内每周投入 10–12 小时，并预留 20% Contingency。若任一关键里程碑延误超过两周，依次移除 UI、趋势分析、Memory Stretch、自动外部 Issue 写回和非必需报表；不得削弱 Release/Manifest/Evidence/Traceability/Deterministic Quality、Auth/Audit 或恢复不变量。

## 2. 全局 Definition of Done

一个里程碑只有在以下条件同时满足时完成：契约已实现、目标测试通过、失败路径可见、操作文档存在、验收证据已归档、Git 提交目标单一且可追溯。仅“代码已写完”不构成完成。

## 3. 里程碑

### M0 — Design Freeze（第 1–2 周）

交付：本 V0.2 文档集、TDR、OpenAPI/Schema 草案、验收矩阵、无未处理架构冲突。OpenAPI 3.1、Agent Protocol、Quality Rule、Fact Catalog 和 V0.2 Manifest 的机器可执行草案已于 2026-08-24 交付并通过 Contract Test；M0 仍需关闭剩余 Review Finding 并获得 Owner 最终批准。

出口：Owner 使用 [最终验收清单](reviews/2026-08-24-owner-acceptance-checklist.md) 完成 Architecture Review；冲突项已有获批 ADR；为中文 `main` 和英文 `release` 的语义配对提交分别创建 `v0.2.0-design-zh` 与 `v0.2.0-design-en` Annotated Tag，并在 Tag Message 中互相引用。评审前保持 Draft。

### M1 — Release Identity and Manifest Authority（第 3–6 周）

交付：工程骨架、数据库迁移、Release、Manifest Revision/Validation/Lock、基础 OIDC/RBAC/Audit。

出口：并发 Lock、checksum 错误、重复请求、权限拒绝测试通过；外部 APK/Branch/Build 改变不能修改 Release；API 可导出 Locked Manifest 与验证报告。

### M2 — Issue Snapshot and Traceability（第 7–11 周）

交付：Jira Adapter、内部系统 Adapter、CI/Build 入口、Normalized Issue、不可变 Snapshot、四类 Trace Edge、Confidence 和 gap report。

出口：两个 Adapter 通过同一契约套件；分页/429/不可用演练通过；真实 Issue 至 Release 路径可查询；缺失边不被伪装；历史 Snapshot 不随外部变化。

### M3 — Real Device Test and Evidence（第 12–18 周）

交付：Device/Agent 注册、心跳、能力、Plan/Case/Run/Attempt、pull protocol、Crash/ANR/Log/Screenshot、对象直传。Memory Interface、Fact Contract 和 Rule Example 保留；真实 Memory Collector 仅作为满足 M1/M2 按期和台架稳定条件后的 Stretch Goal。

出口：一台真实设备执行 Smoke Plan；断网、Agent 重启和 Device 断电有确定恢复；required Evidence checksum 可复验；Collector 不含 Gate 阈值。

### M4 — Deterministic Gate and Report（第 19–22 周）

交付：Fact Catalog、YAML Rule、Rule Set 发布、Quality Input Snapshot、Engine、Rule Result、Quality Result、报告与独立 Override。

出口：相同输入/规则三次重放 digest 一致；缺失/损坏输入不 PASS；每个 BLOCK/WARNING 可导航到 Evidence 和 Traceability；AI 不在决定路径。

### M5 — Operational Acceptance（第 23–24 周）

交付：部署/升级/回滚手册、监控告警、备份恢复、容量基准、安全检查、真实 Release 验收包。

出口：从空环境部署；恢复备份并重放；完成一个真实 Release 全链；Owner 根据验收矩阵签收或记录明确缺口。

## 4. 关键依赖顺序

```text
Release/Manifest
  → Issue/Build Facts → Traceability Snapshot
  → Device/Agent → Test Result/Evidence
  → Rule/Fact Catalog → Quality Evaluation
  → Report/Operations Acceptance
```

UI 可在 API 稳定后薄层实现，不应先于核心闭环。高级报表不能阻塞真实设备和确定性重放。

## 5. 验收矩阵

| 冻结原则 | 验收场景 | 期望 | 必需证据 |
|---|---|---|---|
| Release-centric | 同一 APK 出现在两个 Release | 两个 Release 独立，Artifact 可复用 | ER/API 查询 |
| Manifest authoritative | Lock 后外部版本变化 | 原 Release/Manifest 不变 | digest + Audit |
| Evidence first-class | 删除/损坏 Payload | Evidence integrity error，评估拒绝 | reconcile 报告 |
| Traceability mandatory | Issue 只有 Commit | Fixed/Included/Verified 分离 | gap report |
| Deterministic Engine | 同 Snapshot/Rule 重放 | 决策 digest 一致 | replay report |
| Adapter isolation | Jira 字段改变 | Adapter 映射失败可见，Core 不受 DTO 污染 | contract test |
| Plugin collectors | Memory 阈值变化 | 只改 Rule，不改 Collector | Git diff + test |
| AI advisory | AI 不可用/结论不同 | Gate 结果不变 | dependency/flow proof |
| Real device | 真实台架执行 | Run、Device、Environment、Evidence 全关联 | E2E report |
| Auditability | Override BLOCK | 原结果保留，治理决定有 actor/reason | Audit export |

## 6. 故障验收套件

必须演练：重复 Create/Lock/Result、并发 Lock、Jira 429/5xx/分页中断、内部系统不可用、Agent 断连/重启、Device 断电、Evidence 上传中断/checksum 错误、DB 事务失败、Job 重复领取、规则异常、Snapshot 不一致、备份恢复。

每次演练记录前置状态、注入方法、观测信号、系统终态、数据核对、恢复步骤和残留风险。

## 7. GitHub 版本治理

- `main`：只保存已接受的稳定基线。
- 设计/功能使用目标明确的分支和提交；不得提交无关格式化或半成品混合变更。
- V0.1 基线标签：`v0.1.0-architecture`。
- V0.2 评审草案：分支 `docs/v0.2-implementation-architecture`，版本 `0.2.0-draft.N`。
- Architecture Review 通过后合并，并创建互相引用的 `v0.2.0-design-zh` / `v0.2.0-design-en` Annotated Tag；实现里程碑使用带语言后缀的配对标签或在发布说明中关联中英文 commit。
- 每次提交说明 WHY/WHAT、影响文档/模块、验证和剩余风险；发现架构冲突先 ADR。

## 8. 风险与范围控制

| 风险 | 控制 |
|---|---|
| 业余时间不足 | 优先完整纵向闭环，延期 UI/分析/规模能力 |
| 外部 API 不稳定 | Adapter contract + fixture + Snapshot |
| 设备环境不可控 | Environment Snapshot、预检、租约、恢复窗口 |
| Evidence 体积增长 | 对象存储、保留策略、容量实测 |
| 规则失控 | 受限 DSL、版本、golden tests、发布审核 |
| 技术过度设计 | TDR 必须给出当前需求证据和替代方案 |

## 9. 最终验收包

1. Design Freeze 文档和 TDR 索引。
2. 部署版本/数据库/协议/规则/Manifest schema 清单。
3. 真实 Release、Locked Manifest 和 validation report。
4. Issue/Traceability Snapshot 与 gap report。
5. 真实 Device Run、Result 和 Evidence inventory/checksum。
6. Quality Input、Rule Results、Quality Result 和三次 replay。
7. 权限、审计、故障、备份恢复和容量报告。
8. 已知限制、V0.3 候选和未关闭风险。

只有该验收包完整且 Owner 批准，V0.2 MVP 才可宣布完成。
