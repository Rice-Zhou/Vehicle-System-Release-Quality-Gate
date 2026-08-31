# TDR-014 — 有界 Jira CLI Pilot Adapter 与 Fixture Contract

- 状态：Accepted
- 日期：2026-08-28
- 兼容性修订：2026-08-31；依据 Owner 授权的 Jira CLI v1.7.0 Windows Pilot Smoke
- 决策依据：Project Owner 已批准 `M2-KD-2026-08-28-01` Written Spec Review
- 范围：M2 Issue Source Adapter 的确定性契约测试与 Pilot 环境真实 Jira 只读 Smoke
- 相关决定：[TDR-001](TDR-001-modular-monolith.md)、[TDR-002](TDR-002-kotlin-spring-boot.md)、[TDR-003](TDR-003-postgresql.md)、[TDR-005](TDR-005-rest-openapi.md)、[TDR-007](TDR-007-postgresql-job-outbox.md)、[TDR-009](TDR-009-oidc-and-service-identities.md)、[TDR-011](TDR-011-pilot-company-deployment-profiles.md)

## 1. 为什么选择该技术

V0.2 采用共享 `IssueSourcePort`、确定性 Fixture Contract Suite 和可选 Jira CLI Pilot Adapter。Fixture Adapter 是 CI 中分页、映射、重试、游标恢复和幂等行为的权威可重复测试输入；Jira CLI Pilot Adapter 仅在显式启用的 `PILOT` Profile 中，使用运行节点已经配置和认证的 `jira` CLI，对一个仓库外配置的项目执行有界只读查询。

真实查询的默认值和硬上限均为 20 条 Issue。允许的命令形状固定为：

```text
jira issue list --project <configured-project> --paginate 0:<1..20> --plain --no-headers --no-truncate --columns KEY,SUMMARY,STATUS,PRIORITY,UPDATED --delimiter=<U+241F>
```

Adapter 必须使用 `ProcessBuilder` 参数数组或等价 API，禁止 shell 字符串拼接。项目、可执行文件和上限来自仓库外配置；API 调用方不得注入 JQL、搜索文本、附加参数或任意可执行路径。`--raw`、Comment、History、Attachment 以及白名单外字段均禁止读取。CLI 凭据及其配置文件保持在外部安全机制中，应用不得解析或复制。

`--delimiter=<U+241F>` 必须作为单一 argv 元素传入。真实 Windows Pilot 证明 Jira CLI v1.7.0 对分离的 flag/value 参数不会稳定保留自定义 delimiter，Go `tabwriter` 也不会保留 `U+001F` C0 控制字符；可打印 Unit Separator Symbol `U+241F` 能稳定产生五列。任何字段本身包含该固定符号时仍因列数不等于五而 fail-closed。Jira CLI 的 `UPDATED` 传输格式 `uuuu-MM-dd'T'HH:mm:ss.SSSxx` 只在 Adapter 边界严格解析并规范化为 UTC `Instant`；未知时间格式继续拒绝。

真实 Jira Smoke 仅证明当前身份、网络、CLI 与字段映射路径可用，不替代 Fixture Contract Suite，也不构成 Company Ready 证据。内部 Issue Source 在获得正式 API Contract 前仅使用合成或完全脱敏的 recorded fixture，并通过同一个 Port 验证。

## 2. 解决什么问题

Issue、Release Issue Snapshot 和 Traceability 需要稳定、可复放的数据契约；同时 Pilot 必须证明系统能够读取真实 Jira，而不能让变化的公司数据、网络或权限成为 CI 的非确定性依赖。该决定把两个不同目标分开验证：Fixture 提供确定性失败注入与回归；有界真实 Smoke 提供当前外部读取路径的真实性证明。

该决定只实现 V0.1 已冻结的 Source Adapter 责任，不让 Jira DTO 泄漏到 Core，不改变 Issue、Release、Manifest、Evidence、Traceability、Fixed/Included/Verified 或 Quality Result 语义。

## 3. 为什么没有选择其他方案

- 仅使用 Fixture：无法证明真实 Jira 身份、CLI 和读取路径当前可用。
- 在 CI 中默认访问真实 Jira：网络、权限和变化数据会破坏确定性 Gate，并使公司系统成为构建依赖。
- 现在直接集成 Jira REST API：当前 Pilot 已有可用 CLI 身份，REST 会额外引入 credential、HTTP Client、分页和部署配置；在不扩大查询规模时收益不足。
- 解析 Jira CLI 的本地 credential 或配置：扩大秘密暴露面，并把第三方私有配置变成应用契约。
- 使用 `--raw` 后再过滤：真实 schema 探针表明它会返回 Description、Comment、Reporter、Assignee 等范围外字段，违反最小化采集。
- 当前查询所有项目或全部 Issue：尚无容量、限流、敏感字段、保留和删除治理证据。
- 引入 Kafka、独立 Adapter Service 或第二数据库：当前最多 20 条的单项目 Pilot 没有真实需求支撑这些基础设施。

## 4. 对 V0.2 的影响

M2 新增一个可替换的 Jira CLI Adapter，但仍由模块化单体、PostgreSQL Job/Outbox、既有 RBAC、Audit 和 Idempotency 承载。配置契约为：

```text
VSRQG_JIRA_PILOT_ENABLED=false
VSRQG_JIRA_CLI_PATH=<absolute path, required when enabled>
VSRQG_JIRA_PROJECT=<single project key, required when enabled>
VSRQG_JIRA_MAX_ISSUES=20
VSRQG_JIRA_TIMEOUT=PT15S
```

启用条件不满足、上限不在 1～20、CLI path 非绝对普通文件、项目标识无效或 Profile 不是 `PILOT` 时必须启动失败。stdout 仅在 byte-bounded 内存缓冲区按固定 `U+241F` 解析；每行必须恰有五列，行数不得超过配置上限，字段不得包含控制字符。stderr 只转换为固定诊断码和 digest，不保留原文。`PT15S` 是保守默认值；经授权的 Pilot 主机可通过仓库外配置提高到不超过 `PT60S`，超时仍直接失败且不隐式重试。

CI 只运行合成 Fixture Contract Suite。真实 Smoke 必须人工触发，并仅输出执行时间、Adapter/Mapping Version、查询上限、返回数量、脱敏 schema digest、Sync Run ID 和固定结果码。完整命令、标题、人员、Server URL、本地路径、原始输出与 credential 不得进入 Git、日志、CI Artifact 或验收记录。

## 5. 对未来 V0.3 的影响

V0.3 可在相同 `IssueSourcePort` 和 Contract Suite 下增加 Jira REST Adapter、公司内部 Issue Adapter 或批准的其他传输实现。迁移不得修改已存储的 Normalized Issue Revision、Release Issue Snapshot 或 Traceability Snapshot，也不得重写历史 digest。

扩大到所有 Issue、跨项目或自动周期同步前，必须单独评审分页、限流、容量、字段最小化、数据分类、保留、删除、服务身份和 Company 运行责任。该评审可以替换 Adapter 技术，但不能改变 Core 或 Snapshot/Traceability 语义。

## 6. 如何迁移

从 Fixture-only 开发环境迁移到 Pilot 时，Operator 在仓库外安装并认证 Jira CLI，配置绝对可执行路径和单一项目，然后显式启用 Pilot。首次运行先执行共享 Fixture Contract Suite，再执行最多 20 条的真实 Smoke；两类结果分别记录，任何一类失败都不能被另一类 PASS 覆盖。

未来迁移到 Jira REST Adapter 时，先让新旧 Adapter 通过同一 Fixture Contract Suite，再针对同一个有界只读样本比较 Normalized Contract 与 mapping digest。确认一致后以新的 Adapter Version 切换 Source 配置。历史 Sync Run、Revision、Snapshot、Audit 和 Cursor 不覆盖；回退时切回旧 Adapter Version 并创建新的 Sync Run。

## 7. 如何测试

Contract Test 对所有 Adapter 验证标准字段、未知状态映射、稳定排序、terminal marker、source watermark、mapping version 和同版本幂等。Fixture 测试覆盖多页、重复页、分页中断、429 `Retry-After`、有界 5xx retry、401/403、timeout、非法列数、无效编码、超限输出、tombstone、Cursor 不前移、单参数 delimiter 绑定，以及 Jira CLI offset 时间到 UTC `Instant` 的确定性规范化。

安全测试验证命令参数不能被 API 调用方注入，非白名单字段和 `--raw` 被拒绝，日志与 Problem Details 不包含命令、stdout/stderr、Issue title、Server URL、路径或 credential。PostgreSQL Integration Test 验证 Sync Run、page checkpoint、Revision、successful Cursor 的事务边界与失败恢复。

真实 Smoke 只在 Owner 授权的 Pilot 主机人工执行，最多读取 20 条并生成脱敏摘要。它必须验证确切五列、边界上限、映射成功和 Sync Run 状态；不得执行 create、update、transition、comment、assign 或 attachment 操作。CI 不需要 Jira credential，且真实 Jira 不可用时不得把 Smoke 标为 PASS。

## 8. 如何部署

不新增服务、端口、Broker、数据库或容器要求。Jira CLI Adapter 随现有 Backend 交付，默认关闭。Pilot Operator 管理 CLI 安装、认证、单一项目配置和人工 Smoke 权限；应用只调用已配置的可执行文件，不读取其 credential store。

Company Profile 中该 Adapter 默认不可用。只有完成 Company 环境、身份、网络、数据治理和运维验收后，才能通过新的治理决定启用公司级 Adapter；当前 Pilot 结果不得升级为 Company Ready 声明。

## 9. 失败时如何恢复

CLI 缺失、未认证、超时、非零退出、输出越界、解析失败或映射错误时，当前 Sync Run 标记为 `FAILED`，保留固定诊断、计数与 Audit，但不保留 raw Payload，也不推进 successful Cursor。修复外部配置或服务后创建新的 Sync Run，从最后成功 Cursor 重试；不得修改失败历史或旧 Snapshot。

若怀疑敏感字段、原始 Jira 数据或 credential 泄露，立即禁用 `VSRQG_JIRA_PILOT_ENABLED`，停止新的真实 Sync，隔离相关 Artifact/日志，并通过外部安全流程撤销和替换 credential。恢复后先运行 Fixture Contract Suite 和有界安全 Smoke，再重新允许 Pilot 读取。历史可信 Snapshot 保留，不得通过删除审计记录掩盖事故。

## 重新评估条件

当查询范围超过单项目或 20 条、需要自动计划任务、必须读取额外字段、Jira CLI 输出/行为发生不兼容变化、Company 环境具备正式 REST/API 身份，或内部 Issue API Contract 获批时重新评估。重新评估不得静默改变 V0.1 冻结架构、Snapshot 不可变性或 Traceability 语义。
