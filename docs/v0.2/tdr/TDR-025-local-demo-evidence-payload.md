# TDR-025 — 单机演示 Evidence Payload 保存

- 日期：2026-09-09；状态：Proposed，未改变既有 Accepted TDR。
- 范围：TDR-024 单设备演示的 LOG/SCREENSHOT，不适用于 Company 或大型 Evidence。

## 需求与选择

当前 Owner 要求先做可展示成品、不增加云资源或归档环境。首个 Case 只有有界日志与截图，现有 Backend 主机已有文件系统和 PostgreSQL。建议 PostgreSQL 保存 Evidence Metadata，Payload 保存于 Backend 的独立受控目录；Agent 仍经 HTTP 上传，不与服务端共享文件路径。GitHub 保存代码、合适的脱敏材料及来源记录，不作运行时业务数据库。

| 方案 | 取舍 |
|---|---|
| 现有 Backend + 受控目录 | 推荐用于本切片；无新服务，服务端可流式校验；需处理文件/数据库非原子及单机备份。 |
| 按 TDR-004 增设 S3/MinIO | 保持既有直传技术选择，但增加首个演示不需要的服务配置与维护。 |
| 每次采集直接提交 GitHub | 混淆运行时权限、事务与项目资料发布，还可能上传真实设备内容；不采用。 |

## 对既有技术契约的显式调整

若本 TDR 获准，仅对该演示 Profile 用 Backend 流式上传替代 TDR-004、TDR-006、Agent/Evidence 文档中的 S3 预签名直传。新增完整版本化 `PUT /agent-api/v1/evidence/uploads/{id}/payload`，使用 Agent mTLS 身份和服务端 Upload Session 授权，不返回无鉴权 URL。Create/Complete 请求字段、Evidence 实体、状态、checksum、关联与权威来源不变。OpenAPI、Agent 端点表和对应契约测试须在实施任务中成套更新；不得声称新端点已经存在或是 S3 兼容实现。

GENERAL/RESTRICTED/HIGH 在本切片一律经既有受鉴权 Payload GET 路径流式下载；不放宽 HIGH 控制。相应 API 文档须明确该演示 Profile 行为，已有 Company 路径不被悄悄改写。下载申请鉴权、project scope、purpose、Audit 和禁止 token/路径泄漏仍成立。涉及权限契约的新增 permission 沿已有权限目录实施，不绕过身份检查。

这是存储与传输实现的局部技术提案，不改变 Core Contract 或 Evidence 必须可关联、可复验的语义；若实施发现需要改变核心权威或历史语义，应停止并走 ADR。

## 文件与事务边界

服务器只使用自身生成的 Upload/Evidence ID 形成路径，拒绝符号链接/非普通文件，不接受客户端路径。LOG 上限 1 MiB，PNG 上限 8 MiB，每个 Attempt 两份 required Evidence；先流式写独占临时文件，Complete 复算实际 size/SHA-256，匹配后固化 Payload，再在事务内写 AVAILABLE、Audit/Outbox。已有文件不被不一致重传覆盖。

重复相同 bytes 的上传/Complete 幂等返回；不同摘要返回冲突；超限、校验失败、错误关联和失效租约不得产生 AVAILABLE。文件已写而 DB 失败时保留可定位的孤儿文件，通过同 Session 重试或对账恢复；DB 已有 Metadata 而文件缺失/损坏时明确 INTEGRITY_ERROR，不隐藏为成功。不可在该事务内假装文件系统与数据库原子提交。

目录位于仓库和公开静态资源目录之外，按服务账号权限隔离。备份/恢复必须成对核对 Metadata 与 Payload 清单/摘要；普通目录权限和内容摘要不声称管理员不可修改或提供 WORM。Agent spool 在确认服务端接收 Result/Evidence 前不清理 required 内容。

## 验证、回退与重新评估

验证上传中断、重传、冲突、超限、路径/链接拒绝、跨项目/Agent/Run 访问、缺失/损坏文件、DB 失败后的重试及备份恢复。停止新 Run 后保留目录与 DB；回退应用不得删除历史 Payload。

多主机、并发容量、更多类型、实际公司数据或长期保留需求出现时重新评估 TDR-004；迁移需按固定清单复验，不以切换 Provider 自动宣称历史数据已迁移。本次设计不启用 Company、对象锁或外部 Provider。
