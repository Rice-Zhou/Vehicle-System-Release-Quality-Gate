# TDR-004 — S3-Compatible Evidence Payload Storage

- 状态：Accepted
- 批准评审：`V0.2-AR-2026-08-23-01`
- 批准日期：2026-08-24
- 已接受残余风险：Owner 最终验收清单第 5 节第 1～5 项
- 范围：日志、截图、trace、dump 等大对象

## 问题与需求

Evidence Payload 体积大、类型多、保留期长，需要流式上传、完整性、生命周期、访问控制和低成本扩展；Metadata 仍需关系查询。把 Payload 写入 PostgreSQL 会放大备份、WAL 和查询成本。

## 决策与理由

Payload 使用公司 S3 或 MinIO 等 S3 兼容对象存储；PostgreSQL 保存 Evidence Metadata、object key、size 和 SHA-256。Agent 使用受限预签名 URL 直传，Server Complete 时校验。GENERAL/RESTRICTED 下载可使用短期 Presigned URL；HIGH 下载由逐请求鉴权的 Backend Proxy/受控 Gateway 流式提供，不把 Bearer URL 伪装成用户绑定能力。S3 API 生态成熟、支持版本/生命周期并易于从开发 MinIO 迁移到公司存储。

## 未选方案

- PostgreSQL bytea：事务方便但大对象影响数据库运维和恢复。
- 共享文件系统：权限、跨主机、生命周期和 API 一致性较弱。
- 数据库外任意本地路径：不可移植且易丢失。

## V0.2 / V0.3 影响

V0.2 增加“数据库与对象存储非原子”的上传状态机。Agent 上传与普通下载不经过 Backend Payload 转发；低频 HIGH 下载为逐请求授权而接受 Backend/Gateway 流量，并必须测量带宽、并发和超时。若 HIGH 流量成为瓶颈，V0.3 可提取受控 Evidence Gateway、采用分层存储或跨区域复制，Metadata 与鉴权契约不变。

## 迁移与回滚

对象通过 content checksum 和 inventory 复制到新 bucket/provider；验证后批量更新受控 URI 引用并保留映射。应用回滚继续读取兼容 S3 API；失败复制不删除源对象。

## 测试、部署与恢复

测试中断上传、过期 URL、错误 checksum、孤儿/缺失对象、普通下载 TTL、HIGH 跨用户访问和日志泄漏。部署私有 bucket、加密、版本/生命周期策略。恢复时结合 bucket inventory 与 DB metadata reconciliation。

## 重新评估条件

公司禁止 S3 API、Evidence 类型需要专用索引/流式处理，或实测成本/性能无法满足保留策略。
