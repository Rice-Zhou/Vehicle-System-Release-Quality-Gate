# TDR-015 — 版本化 Jira Mapping Profile 与 Adapter Version Authority

- 状态：Accepted
- 日期：2026-09-01
- 决策依据：Project Owner 已于 2026-09-01 批准 `M2-KD-2026-09-01-01` Written Spec Review
- 范围：M2.2 Jira status/severity Mapping Profile、Adapter Version Authority 与 Sync 版本固定
- 相关决定：[TDR-003](TDR-003-postgresql.md)、[TDR-005](TDR-005-rest-openapi.md)、[TDR-007](TDR-007-postgresql-job-outbox.md)、[TDR-009](TDR-009-oidc-and-service-identities.md)、[TDR-011](TDR-011-pilot-company-deployment-profiles.md)、[TDR-014](TDR-014-bounded-jira-cli-pilot-adapter.md)

## 1. 为什么选择该技术

V0.2 使用 PostgreSQL 不可变 Mapping Profile 保存 Jira status/severity 映射内容与 digest，并由代码中的 `IssueSourceRuntimeDescriptor` 声明唯一 Adapter Version。Source 保存当前激活 Mapping Version，每个 Sync Run 固定 Adapter Version 与 Mapping Version。

Release、Manifest、Issue、Test、Evidence 与 Traceability 存在结构化关系、事务、历史查询和一致性要求；MVP 数据规模可控。PostgreSQL 已是 V0.2 结构化数据权威，因此增加一个 Project/Source-scoped、INSERT-only 的 `issue_mapping_profile` 比引入第二配置系统更符合当前约束。

## 2. 解决什么问题

真实 Jira Smoke 中全部 status 被规范化为 `UNKNOWN`，说明合成默认 Map 无法解释真实项目工作流；同一次运行还出现人工 Adapter Version 与既有验收记录不一致。该决定使每个 Jira Source 拥有独立、可审计、可重放的映射历史，并消除 API、Operator、Seed 与运行代码之间的 Adapter Version 多权威。

Mapping Profile 是 Adapter 实施配置而非 Core Entity。本决定不修改 V0.1 Core Contract、Issue/Traceability 语义或 Release-centric architecture。

## 3. 为什么没有选择其他方案

- 仓库外 YAML 作为唯一权威：旧文件丢失后无法重放历史 Sync，也缺少事务激活和数据库 Audit。
- 环境变量或 Spring Map：没有内容摘要、Project Scope、不可变历史和 Idempotency。
- 数据库存储可编辑当前 Map：UPDATE 会让相同 Mapping Version 的历史含义漂移。
- API 接收 Adapter Version：调用方可声明运行代码并不存在的版本，形成第二权威。
- 配置中心或管理 UI：当前 Pilot 的单项目、最多 20 条需求不支撑额外平台成本。
- 正则、模糊或包含匹配：同一输入可能因规则顺序或实现变化得到不同结果，破坏确定性。

## 4. 对 V0.2 的影响

新增不可变表 `issue_mapping_profile`，以 `(source_id, mapping_version)` 唯一定位 Profile，并通过复合外键保证 Project 隔离。`mapping_version` 是 RFC 8785 canonical definition 的 `sha256:<64 lowercase hex>`。数据库 Trigger 拒绝 UPDATE/DELETE；读取时重新校验 digest。

新增最小受鉴权激活操作 `POST /api/v1/issue-sources/{sourceId}/mapping-profiles:activate`，要求 `issue:configure` 与 `Idempotency-Key`。请求只提交 Schema 与 definition，不接受 Mapping Version 或 Adapter Version。Profile 插入、Source 激活、Audit 与 Outbox 在一个事务中完成。

`StartIssueSync` 使用 Source 锁固定两个版本。Worker 校验 Descriptor、Run、Profile、Project、Source、Schema 与 digest 后才允许启动 Jira Process。缺失、完整性失败、Schema 不支持或版本不一致时 Jira 调用次数为零，Run 失败且 successful Cursor 不推进。未知 status/severity 仍产生 Warning 并映射为 `UNKNOWN`。

## 5. 对未来 V0.3 的影响

V0.3 可在相同 Profile Authority 下增加 Jira REST Adapter 或内部 Issue Adapter；每种 Source 可声明自己的受支持 Mapping Schema。扩展到跨项目、全量 Issue、自动调度或 Company 时，必须单独治理容量、保留、数据分类、服务身份和运行责任。

历史 Profile、Sync Run、Revision 与 Snapshot 继续不可变。未来技术可以替换 PostgreSQL 的访问实现或 Adapter transport，但必须保留版本固定、digest 校验、Source/Project 隔离和 fail-closed 语义。

## 6. 如何迁移

使用 forward-only Expand Migration 创建表、约束、索引和 immutable Trigger，不 seed 真实公司 Token，不修改历史数据。先用合成 Profile 完成激活、Fixture Contract 与完整性测试，再通过独立 Owner 授权执行真实 Jira 有界复测。

Alias 或映射结果改变时插入新 Profile 并激活，旧 Run 继续使用旧 Profile。若 Profile Schema、规范化算法、CLI argv、解析协议或 Adapter 行为发生不兼容变化，按兼容边界升级 Mapping Schema 或 Adapter Version，并保留旧 Runtime 直到历史兼容性不再需要。

## 7. 如何测试

Unit Test 覆盖 RFC 8785、SHA-256、Unicode NFC、`Locale.ROOT`、Alias 冲突、非法枚举、未知 Schema、边界上限与未知 Token。PostgreSQL/Application Test 覆盖不可变性、Project/Source 隔离、权限、幂等、Audit、Outbox、事务回滚和调用方版本注入拒绝。

Runtime Test 覆盖 Profile A/B 激活竞态、Run 版本固定、五类固定失败诊断、所有版本/完整性失败时 Jira Process Runner 调用次数为零、失败不推进 Cursor、历史 Snapshot digest 不变。安全测试验证日志、Problem Details、Git 与 CI Artifact 不泄露 definition、真实工作流 Token、Issue 内容、路径或 Credential。

## 8. 如何部署

不新增服务、Broker、数据库、容器或 UI。Migration 随现有 Backend 与 PostgreSQL 部署；Jira CLI Pilot Adapter 仍默认关闭，Company Profile 默认不可用。激活真实 Profile 是独立的受鉴权、审计操作，不通过 Seed、环境变量或 Git 配置完成。

部署前必须通过空库、升级库、重复 Migration、Fixture Contract 和权限测试。应用与数据库 Schema 不兼容时启动失败，不允许静默使用硬编码 Map。

## 9. 失败时如何恢复

Profile 激活事务失败时整体回滚并继续使用旧 Profile。新 Profile 导致未知状态时保留 `UNKNOWN_STATUS` Warning，修正 Alias 后插入并激活另一个版本；不得修改旧 Profile 或历史 Run。

若应用不能解释已激活 Mapping Schema，保持 fail-closed，优先 roll-forward。必须回退应用时，通过受审计操作重新选择旧兼容 Profile/版本；不删除新 Profile、不覆盖历史 Sync/Revision/Snapshot。若发生敏感 Token 或 Credential 泄露，停止真实 Sync、隔离 Artifact、按外部安全流程处置后再复测。

## 重新评估条件

当需要跨 Source 共享组织级词典、Profile 数量或读取负载超出 PostgreSQL 当前边界、必须支持非 Jira Source 的不同映射语言、需要 Company 自助配置 UI、Mapping Schema 发生不兼容变化，或 Adapter Runtime 无法继续与 Backend 同版本交付时重新评估。重新评估不得静默改变 V0.1 冻结架构、Core Entity 或 Traceability 语义。
