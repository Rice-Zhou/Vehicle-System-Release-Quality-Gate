# M1 Release Identity and Manifest Authority 启动设计

- Spec ID：`M1-KD-2026-08-24-01`
- Owner Design Direction：`APPROVED 2026-08-24`
- Written Spec Review：`APPROVED 2026-08-24`
- Architecture Baseline：`V0.2-AR-2026-08-23-01`
- Design Freeze Tags：`v0.2.0-design-zh` / `v0.2.0-design-en`
- 计划周期：第 3～6 周
- 容量：一名主要开发者，每周 10～12 小时，预留 20% Contingency

## 1. 目的

M1 将冻结的 Release Identity 和 Manifest Authority 从设计转化为首个可运行、可测试、可恢复的实施增量。它必须证明系统能够创建独立 Release、注册和验证 Manifest Revision、并发安全地 Lock 权威 Manifest，并从 API 导出不可变 Manifest 与验证报告。

本设计规定实施顺序和验收证据，不规定类名、函数拆分或个人编码风格。任何实现都不得改变 V0.1 Core Contract、Release-centric 架构、Manifest Authority、Evidence 一级实体、Traceability、Deterministic Quality Engine、Adapter/Plugin 或 ADR 治理。

## 2. 已选择的拆分方式

采用按业务不变量纵向拆分的增量方案。每个批次同时覆盖所需契约、持久化、应用行为、API、测试和操作证据，因此能够独立评审和回滚。

未采用以下方案：

- 按技术层拆分：数据库、Backend 和测试分别完成会把集成风险推迟到 M1 末尾。
- 先做 Demo 再补治理：会形成临时身份、弱事务、无审计或假成功路径，不满足公司级课题要求。

## 3. M1 范围

M1 包含：

- Kotlin/JVM + Spring Boot Modular Monolith 工程骨架。
- PostgreSQL Migration 和真实数据库 Constraint Test。
- Release、Release State History、Manifest Revision、Manifest Validation、Artifact、Project、Principal、Project Assignment、Audit Event、Idempotency Record 和 Outbox Event 的 M1 必需子集。
- 基础 OIDC 接口、固定 RBAC、Service Identity 和 Audit。
- 已冻结 OpenAPI 中 Release/Manifest 的 Endpoint。
- V0.2 Manifest Schema、RFC 8785 JCS、UTF-8、SHA-256、Validation 和并发 Lock。
- Locked Manifest 与 Validation Report 导出。

M1 不包含：

- UI。
- Jira/Internal Issue Adapter、Traceability Snapshot。
- Device、Test Agent、Test Run 或 Evidence Payload。
- Quality Engine 或 Rule 执行。
- Kafka、Kubernetes、Redis、Graph Database 或 Microservice 拆分。
- 公司特定 IdP 产品绑定；M1 实现标准 OIDC 边界并用受控 Test Issuer 验证，公司环境接入保留为部署前条件。

## 4. 实施批次

### M1.0 — 工程与质量基线

交付：

- Backend 和模块骨架，至少建立 Release、Manifest、Identity/Audit 与 shared infrastructure 边界。
- 固定 JDK、Kotlin、Spring Boot、Gradle、PostgreSQL 和 Testcontainers 版本。
- 本地 PostgreSQL 开发环境；Object Storage 留到需要 Evidence Payload 的 M3，M1 不提前部署。
- CI 执行编译、单元测试、真实 PostgreSQL Integration Test、OpenAPI/Schema Contract Test、架构依赖测试和 Secret Scan。

出口证据：

- 从空工作区可按文档启动并通过 Health/Readiness smoke。
- 模块依赖测试阻止绕过 Application Port 的跨模块访问。
- 现有 Contract Test 继续通过，V0.1 Manifest Schema 哈希不变。

回滚：

- 仅包含工程骨架和开发基础设施；删除新增应用模块即可回到冻结文档基线，不产生持久数据。

### M1.1 — PostgreSQL 权威数据基线

交付：

- Flyway forward-only Migration。
- M1 表、PK、FK、UNIQUE、CHECK、Composite FK、不可变约束和必要索引。
- Release、Manifest、Artifact、Identity、Audit、Idempotency 与 Outbox 的单一数据所有者。
- Schema Export、Migration Test 与 Constraint Integration Test。

出口证据：

- 空库迁移成功，重复迁移无变化。
- 支持的上一 Schema 状态可升级。
- 不合法 FK、重复身份、跨 Project 引用和不可变记录 UPDATE/DELETE 被 PostgreSQL 拒绝。
- Migration 失败会中止启动，不允许应用绕过数据库约束。

回滚：

- 应用回滚到兼容镜像；不修改已应用 Migration。不可逆失败使用演练过的备份恢复，不使用手工反向 SQL。

### M1.2 — Identity、RBAC 与 Audit 边界

交付：

- 标准 OIDC Principal 映射和独立 Service Identity。
- 固定 M1 Role/Permission Matrix 与 Project Scope。
- 写操作审计、request ID、actor、reason、resource identity 和 outcome。
- 测试环境使用受控 Test Issuer；生产 Profile 缺失 issuer/audience/credential 时启动失败。

出口证据：

- 未认证返回 401，无权限或跨 Project 返回 403。
- Token 的 issuer、audience、expiry 和 signature 校验失败时拒绝访问。
- 高风险写操作在 Audit 持久化失败时整体回滚。
- 日志、数据库 Fixture 和配置中无明文 Secret。

回滚：

- 回滚应用但保留 Principal/Audit 历史；不得回退到匿名访问、共享人员 Token 或默认 Admin。

### M1.3 — Release Identity

交付：

- 创建与查询 Release、初始状态历史、Audit 和 Outbox 同事务。
- `POST /api/v1/releases` 与 `GET /api/v1/releases/{releaseId}`。
- `Idempotency-Key`、request digest、原响应重放、冲突检测和乐观并发。
- Release ID 为系统生成的不透明稳定标识。

出口证据：

- 相同 Principal、Endpoint、Key 和 request digest 只产生一个 Release 并返回原响应。
- 相同 Key 配不同 digest 返回 409，且不产生第二个 Release。
- 外部 APK、Branch 或 Build 数据变化不修改既有 Release Identity。
- 创建事务任一步失败时 Release、History、Audit 和 Outbox 均不部分写入。

回滚：

- 回滚应用版本；已创建 Release 和 Audit 保留。错误业务数据只能通过显式治理流程纠正，不物理删除审计历史。

### M1.4 — Manifest Revision、Validation 与 Lock

交付：

- `POST /api/v1/releases/{releaseId}/manifests`、Validate 和 Lock Endpoint。
- V0.2 Manifest Schema 校验、RFC 8785 JCS canonical bytes、UTF-8 无 BOM、SHA-256 content digest。
- Lock 前不可变 Revision；Lock 时完整性复检、Release 引用、状态历史、Audit 和 Outbox 同事务。
- Artifact Identity/Checksum Validation Result 和稳定 Validation Report。

出口证据：

- 相同语义且 property order 不同的 JSON 得到相同 digest；Artifact 数组顺序变化得到不同 digest。
- 缺失 required、非 NFC string、不规范数值、错误 checksum 和不支持的 Schema 被拒绝。
- 相同幂等请求返回同一 Manifest Revision。
- 两个操作者并发 Lock 只有一个成功；失败方得到 409，无部分写入。
- Lock 后外部 APK、Branch、Build 或源 Manifest 变化不能修改已锁定内容。

回滚：

- Lock 前失败 Revision 可保持 FAILED/REJECTED 记录；Lock 后内容永不回写。需要不同内容时创建新 Release。

### M1.5 — M1 综合验收

交付：

- 一条 API 流程完成 Create Release → Register Manifest → Validate → Lock → Export。
- Locked Manifest、Validation Report、Release State History 和 Audit Timeline 验收包。
- M1 Runbook，覆盖启动、Migration、常见失败、数据导出、应用回滚和数据库恢复。

出口证据：

- Contract、Unit、Integration、Security、Concurrency 和 Smoke Test 全部通过。
- 独立 PostgreSQL 实例从空库完成流程，并从备份恢复后导出相同 Locked Manifest digest。
- Owner 可仅通过 API 和归档证据判断 M1 是否满足，不需要直接修改数据库。

回滚：

- 综合验收不改变权威数据；失败时记录 Finding，M1 保持未通过，不以局部 PASS 宣称完成。

## 5. 数据与控制流

```text
Authenticated Principal
  → Release API
  → Application authorization / idempotency
  → Release transaction
  → Release + State History + Audit + Outbox
  → Manifest registration
  → Schema validation + canonicalization + checksum validation
  → immutable Manifest Revision + Validation Report
  → concurrent Lock transaction
  → Locked Manifest authority + Release reference + Audit + Outbox
  → immutable export
```

Transport 只负责协议映射。Application Use Case 负责认证结果、权限、幂等和事务协调。Domain 负责状态与权威不变量。Persistence Adapter 负责执行数据库契约，不在 ORM callback 中隐藏业务决策。

## 6. 失败与恢复原则

- 输入错误返回稳定 RFC 9457 Problem Details，不返回假成功。
- 权限失败不得透露不可见资源是否存在。
- 幂等摘要冲突和并发状态冲突返回 409。
- Schema 正确但领域校验失败返回 422 和机器可读 violations。
- PostgreSQL 不可用时写入失败；不得以进程内缓存暂存并宣称成功。
- Audit 或 Outbox 属于同一业务事务，失败时整体回滚。
- 未知异常记录 request ID，不向调用方泄露堆栈、Token、配置或外部响应。
- 数据不一致时隔离 Release 并拒绝 Lock，不通过宽松 fallback 继续。

## 7. 测试与验收证据

每批必须先定义失败测试，再实现最小行为。测试层级包括：

1. Domain Unit Test：状态转换、Identity、Revision、digest 和 Lock 不变量。
2. Application Test：权限、幂等、事务回滚与错误映射。
3. PostgreSQL Integration Test：Migration、Constraint、并发 Lock 和不可变保护，不使用 H2 替代。
4. API Contract Test：实现与冻结 OpenAPI 的请求、响应、权限、幂等和 Problem Details 一致。
5. Security Test：OIDC 校验、跨 Project 拒绝、Secret Scan 与日志检查。
6. Smoke/Recovery Test：空环境启动、流程执行、应用回滚和数据库恢复。

每项证据记录命令、版本、Git commit、开始/结束时间、退出码、失败数量和 Artifact 路径。截图只能作为辅助，机器可读报告是主要证据。

## 8. Owner 分批验收

| Gate | Owner 验收重点 | 技术证据 | 未通过行为 |
|---|---|---|---|
| M1.0 | 工程可构建、边界明确、无过度基础设施 | Build/Contract/Module Test | 停止 M1.1 |
| M1.1 | PostgreSQL 是唯一结构化事实源 | Migration/Constraint/Schema Export | 禁止业务 Endpoint |
| M1.2 | 默认拒绝、权限与审计可证明 | Security/RBAC/Audit Test | 禁止开放写 API |
| M1.3 | Release Identity 稳定且事务完整 | Idempotency/Concurrency/Transaction Test | 不进入 Manifest |
| M1.4 | Manifest Authority 与 Lock 不变量成立 | Canonicalization/Checksum/Lock Test | 不宣布 M1 完成 |
| M1.5 | 端到端验收包完整并可恢复 | API E2E/Export/Restore Report | 登记 Finding 并重验 |

Owner 在每批只验收目标、边界和证据是否满足；具体代码组织和技术实现由 Codex 负责。批次通过不替代 M1.5 总体验收。

## 9. Git 与双语治理

- 中文实施与证据文档进入中文功能分支，英文等价文档进入英文配对分支。
- 非 Markdown Artifact 必须完全相同；英文 Markdown 不得包含汉字。
- 每个 Commit 只包含一个可解释增量，并在提交前通过目标测试。
- 每批先推送候选分支，通过 Pair Verification 后再分别合并到 `main` 和 `release`。
- 不修改或移动 `v0.2.0-design-zh` / `v0.2.0-design-en`；M1 验收版本使用新的配对里程碑标签。
- 任何 Core Contract、Manifest Authority 或其他冻结语义冲突必须停止实施并提出 ADR。

## 10. 排期与 Cut Line

| 周次 | 目标 |
|---|---|
| 第 3 周 | M1.0；开始 M1.1 |
| 第 4 周 | 完成 M1.1；完成 M1.2 |
| 第 5 周 | 完成 M1.3；开始 M1.4 |
| 第 6 周 | 完成 M1.4；执行 M1.5 |

若 M1.1 或 M1.2 延误超过一周，先移除非必需本地便利工具、额外报告格式和非关键 observability enrichment。不得移除真实 PostgreSQL 测试、事务、权限、Audit、幂等、Manifest canonicalization、checksum、并发 Lock 或恢复证据。M1 不启用 UI 和公司特定平台优化。

## 11. 完成定义

M1 只有在 M1.0～M1.5 全部通过、证据已归档、双语文档已配对、Git 提交可追溯、残余风险已登记且 Owner 完成 M1 总体验收后才算完成。

本规格获书面复核后，下一步是生成逐文件、逐测试、逐提交的 M1 Implementation Plan。该计划可以决定实现细节，但不能扩大本规格范围。
