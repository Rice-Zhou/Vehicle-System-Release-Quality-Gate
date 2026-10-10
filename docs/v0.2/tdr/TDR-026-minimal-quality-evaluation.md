# TDR-026 — 最小质量判定的进程内执行与事实绑定

- 日期：2026-09-15；状态：Proposed / REVIEW_REQUIRED。
- 范围：限定质量切片的技术设计，不是完整 M4 验收或规则发布。
- 设计：[最小质量判定](../../superpowers/specs/2026-09-15-minimal-quality-evaluation-design.md)。

## 需求与依据

复用已交付 Release/Traceability/Smoke，补上正式、确定且可重放的质量决定。沿用 TDR-001/002/003/007/008/025；不引入 Company 资源，不改变冻结职责。

核查发现：现有质量 API 是契约声明；Issue Snapshot 无 required；Traceability confidence 为等级，Fact Catalog v1 要求数字。直接把演示摘要喂入规则或任意映射分数会掩盖这些缺口。

## 提议决定

1. Backend 的 quality 模块在进程内执行纯规则求值，PostgreSQL Job 处理既有异步 202 请求。应用端口读取各模块的唯一正式来源，Evidence 完整性仍由 Evidence 模块实现。
2. 发布内容保存 YAML、受限 AST、目录与引擎版本、审核/身份和摘要；数据库为运行时权威，Git 保存规则来源，不从 Git 动态求值。
3. Fact Catalog v2 显式绑定集合 item 字段，用 minimumConfidenceLevel 保留原等级而不猜测数值；v1 原样保留。requiredIssueRefs 与 selectedCaseRefs 是已发布规则集政策，显式审核、纳入输入和规则集摘要，不改写原 Issue Snapshot。
4. 请求只接受正式引用；固定同 Release 输入后求值。短事务 + 状态复核 + fencing 防止源变化或旧 worker 覆盖结果；历史选择不会因新 Run 成功而改变。
5. 大整数和小数采用有类型的规范值树：每个节点带类型标签；数值值为规范十进制字符串，整数无前导零，小数去无意义尾零、负零归零。对象 key 稳定排序，字符串无 trim/locale 变换；数组按目录顺序。对该无 JSON number 精度风险的表示编码并 SHA-256，独立记录编码版本；不是修改既有模块 JCS。以 golden bytes 锁定编码，禁止实现自行猜测。
6. 沿用现有鉴权/审计/版本保护和只读报告。工程资源上限见设计，不作为 Company SLO。
7. `quality:evaluate` 项目角色限定为 Engineer、Quality Owner、Administrator；`quality:read` 沿用项目只读权限覆盖所有项目角色。角色门禁同时校验 JWT scope 与项目成员关系。当前 V0.2 单 Run、单 Case、单 Attempt 的正式选择由已发布规则集和终态 Test Run 固定，不推测“最新成功”。

## Task 6 只读报告补充提议（2026-10-09）

1. 本切片复用 `GET /api/v1/releases/{releaseId}/quality-results` 的分页历史，不在 Task 6 新增质量查询权威。演示入口保存 202 响应的 `evaluationId`，按 `nextCursor` 查找该精确 ID；查不到或重复出现时显式失败，不选“最新”或其他成功记录。报告输入为受控导出的正式响应，渲染器不得请求评估、执行规则或从旧 M1/M2 摘要推断质量状态。
2. `COMPLETED` 展示 `qualityResult.action/resultDigest/ruleResults` 与 `inputSnapshot` 的来源 ID、版本、摘要、选择、执行版本和 `uncoveredFacts`；`ERROR` 展示 `NOT_EVALUATED`、原错误码与可用的固定输入，不制造 Quality Result。仅在存在 `inputSnapshot` 时，以固定 `snapshotId` 查询 Traceability 并核对 Release、Snapshot ID、Issue Snapshot ID、Manifest ID/摘要，以所选 `runId` 查询 Test Result 并核对 Attempt 与 Result 摘要。Traceability GET 不提供 Issue Snapshot 摘要，报告保留 Quality Input 的该摘要，但不声称独立复验。若 ERROR 发生在输入固定前，跳过来源查询并明确显示未固定；任一已查询关联冲突使报告输入失败。
3. 对适用且完成求值的规则，当前 `ruleResults[].evidenceRefs` 为该输入的全部 Evidence ID；`NOT_APPLICABLE` 或 `ERROR` 时为空，不能称为某条规则的独有或因果 Evidence。报告按 `inputSnapshot.evidenceRefs` 展示 Evidence ID、类型、Run/Attempt、摘要及大小，规则旁标为“固定输入中的 Evidence 引用”；若将来要求逐规则因果定位，须先修订本 TDR、响应契约与求值证据语义。
4. Evidence 元数据/下载路由受 `vsrqg.demo.evidence.enabled` 控制；元数据 GET 会记录完整性观察，下载需要单独授权、目的和审计。纯离线报告不预取元数据、下载 Payload、嵌入凭据或声称静态链接已获授权。仅在启用的隔离环境以具有项目权限的身份验证精确 Evidence ID 可读；未验证时显示定位符和限制，不把它计为已完成导航。
5. quality 报告导出为独立 `quality-report-export.json`，只含精确 Evaluation、可用时的 Traceability/Test 查询响应与演示来源标记；标记来自受控夹具记录，不参与质量决定，缺少证明时为 UNKNOWN。导出不含 Token、Payload 或预签名 URL。quality 输入上限与旧 M1/M2 每文件 1 MiB 限制独立，实施前按最大受控样本固定显式字节上限并测试恰好上限与超限拒绝。

该补充只确定 Task 6 的技术投影边界，仍待本 TDR 评审；不授予规则发布、真机运行、Owner 验收或 TDR 状态变更。

## 替代方案与取舍

- 离线 JS 判定：易展示，但形成第二质量权威且绕开正式快照，拒绝。
- 独立规则服务/通用脚本引擎：本切片没有分布式需求，增加部署和可重放成本，拒绝。
- 给 HIGH/MEDIUM/LOW 编造概率：改变事实意义，拒绝；选择显式版本化目录。
- 仅同步 HTTP 求值：实现较短，但不符合现有 202 异步契约和恢复方式；复用现有 Job。

代价：须补齐机器响应契约、目录 v2 与政策来源，不能只加两条 if。对旧目录缺少运行绑定明确拒绝，保留旧契约测试，不声称旧版本运行兼容。

## 测试与迁移

先完成目录/AST/编码黄金测试和全操作符矩阵，再交付 API/DB 与真实串联。输入跨 Release、缺失/损坏、规则异常、幂等冲突、晚写、恢复及三次重放映射 A1–A8。新表采用增量迁移与不可变保护；原快照/算法不改写。回滚到未启用新模块的版本保留新历史数据；已有任务须先终结或暂停，不能旧程序继续处理新 Engine 版本。

后续[技术探针](../reviews/2026-09-15-quality-evaluation-preflight.md)已用现有 SnakeYAML 2.5 验证事件信息可用，选择其为锁定候选。事件解析不自动执行全部 LoaderOptions 限制，必须实施独立的事件守卫；UTF-8 bytes、节点/深度、重复 key、alias/tag/merge key 与 scalar grammar 均显式校验。按复核第 3 节固定完整规范树和数值展开上限。生产守卫、全编码 golden tests 与 BOM 兼容性仍是实施计划首段验证项，不能据探针宣称生产解析已通过。

## 评审边界与重新评估

本 TDR 仍为 Proposed。目录类型衔接、required 政策来源及 Case 动作表须明确评审；若影响冻结语义则先 ADR。本文件不授权实施、规则发布、真实 Provider、设备操作、merge、Tag 或部署。

重新评估触发：事实目录无法保持历史解释、资源预算不足、确有多进程容量需求或现有解析器无法落实严格节点限制。此时记录测量与替代方案，不能静默增加服务或退回宽松解析。
