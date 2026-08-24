# 04 — Release Manifest Lifecycle

## 1. 权威原则

Release Manifest 是 Release 内容的唯一权威定义。APK、Jira Version、Build Number、Git Branch 或外部系统变化只能成为新输入，绝不能自动修改已创建 Release 或已锁定 Manifest。

## 2. 生命周期

```mermaid
stateDiagram-v2
  [*] --> DRAFT
  DRAFT --> VALIDATED: schema + semantics + checksum pass
  VALIDATED --> DRAFT: content changed before registration
  VALIDATED --> REGISTERED: immutable revision created
  REGISTERED --> LOCKED: authorization + revalidation + atomic bind
  DRAFT --> REJECTED: explicit discard
  VALIDATED --> REJECTED: explicit discard
  LOCKED --> [*]
```

### 创建

创建草稿时绑定目标 `releaseId` 和 Manifest schema version。草稿允许修订，但不是 Release 权威定义。

### 校验

按顺序执行：

1. JSON Schema：必填字段、类型、枚举、未知字段。
2. 语义：Release ID 匹配、Artifact ID 唯一、类型专属字段合理。
3. 完整性：至少一个 Artifact；required Artifact 不缺失。
4. 身份：APK package/version/signing fingerprint、Image build identity 等可验证字段。
5. checksum：读取实际 Artifact 或可信构建元数据并验证 SHA-256。

每次校验生成不可变 Validation Report，记录 schema、validator version、输入 digest、逐项结果和时间。无法访问 Artifact 时校验失败或保持明确 INCOMPLETE，不得通过。

### 注册

注册把规范化 JSON、内容 digest、Artifact 关联和 Validation Report 固化为不可变 Revision。相同 Release + 内容 digest 的重复请求返回原 Revision。

### Lock

Lock 必须满足：调用者有权限；Revision 属于该 Release；校验仍有效；Artifact checksum 未变化；Release 仍允许 Lock；不存在已锁定 Manifest。

一个数据库事务完成 Manifest 状态、Release `lockedManifestId`、Release 状态、审计和 Outbox。并发 Lock 只有一个成功。

## 3. Release 状态协作

```text
Create Release(DRAFT)
→ Register Manifest: Release REGISTERED
→ Lock Manifest: Release READY_FOR_TEST
→ Create Run: TESTING
→ Complete Evaluation: QUALITY_EVALUATED（关联独立的 PASS/WARNING/BLOCK Quality Result）
→ Governance complete: COMPLETED
```

`COMPLETED` 不覆盖质量状态；最终页面同时显示算法 Quality Result 和治理状态。

## 4. 版本与兼容

- `manifestVersion` 表示文档 schema major/minor。
- `revision` 表示同一 Release Lock 前的不可变候选版本。
- V0.1 Schema `schemas/release-manifest.schema.json` 保持不变；V0.2 使用独立 `schemas/v0.2/release-manifest.schema.json`，不得覆盖或静默升级历史文档。
- 新 schema 读取旧 Manifest 必须有解释器；禁止后台静默改写已锁定文档。
- 一旦测试开始，内容变化在 V0.2 必须创建新 Release，避免同 Release 多权威版本造成验收歧义。

### 4.1 V0.2 Schema 语义

V0.2 Artifact 必填公共字段：artifactId、type、name、version、source、checksum.algorithm、checksum.value 和 `required`。`required` 必须显式提供 boolean；缺失为 Schema Error，不使用 JSON Schema default，也不允许实现自行解释为 true 或 false。

类型身份字段：APK 必须包含 packageName、versionCode 字符串和 signingCertificateSha256；SYSTEM_IMAGE/VENDOR_IMAGE 必须包含 buildId 与 buildFingerprint；FIRMWARE/CONFIG 必须包含 target 与版本身份。OTHER 必须包含 type-specific identity map，且 key 由 Schema 白名单限制。未知写入字段被拒绝。

### 4.2 Canonicalization 与 digest

1. 输入先通过 V0.2 JSON Schema 和语义校验；重复 JSON key、非 NFC Unicode string、浮点/指数形式和超出 `[-(2^53)+1, (2^53)-1]` 的 JSON integer 直接拒绝。可能超出该范围的身份数字使用十进制 string。
2. 通过校验的 JSON 按 RFC 8785 JSON Canonicalization Scheme（JCS）生成 canonical bytes；JCS 不执行业务默认值、trim、大小写转换或 Unicode normalization。
3. canonical bytes 使用 UTF-8、无 BOM、无尾随换行；`contentDigest` 为这些字节的 SHA-256，编码为 `sha256:<lowercase-hex>`。
4. Artifact 顺序属于 Manifest 语义，不自动重排；Object property 顺序由 JCS 决定。
5. Validation Report 保存 schema ID/version、canonicalization ID `RFC8785-JCS-1`、validator version、canonical byte length 与 digest。

Canonicalization Fixture 必须覆盖 property order、Unicode、escape、integer boundary、Artifact order、显式 `required=false` 和缺失 required。至少使用 JVM 实现和一个独立实现计算相同 canonical bytes/digest；任何差异阻止 Lock。

## 5. 失败处理

- Schema/语义失败：返回 422 和字段级 violation。
- checksum 不符：标记 INVALID，记录 expected/actual；不得 Lock。
- Artifact 不可访问：明确 INCOMPLETE，可重新校验。
- 并发修改：If-Match 不符返回 409。
- Lock 事务失败：全部回滚。
- Lock 后发现外部 Artifact 被替换：原 Release 仍指向原 checksum；创建新 Release，并对来源系统触发安全告警。

## 6. MVP 与延期

MVP 支持现有 schema 的 APK、SYSTEM_IMAGE、VENDOR_IMAGE、FIRMWARE、CONFIG、OTHER，SHA-256 以及 Lock 前 Revision。签名链治理、供应商 SBOM 和 Manifest 签名延期，但保留 schema/version 扩展点。

## 7. 验收场景

1. 相同输入重复注册返回相同 Manifest。
2. 两个操作者并发 Lock，只有一个成功。
3. Lock 后数据库/API 均无法修改内容或 Artifact 集合。
4. 外部 APK、Jira Version、Branch 或 Build 变化不改变 Release。
5. checksum 不符、Artifact 缺失或 Schema 未知均不能进入测试。
6. 从 Quality Result 可回溯到 Locked Manifest 原文、摘要与 Validation Report。
7. 相同语义 JSON 的 property order 不影响 digest；Artifact 数组顺序变化会改变 digest。
8. 缺失 `required`、非 NFC string 或非规范数值不能注册 V0.2 Manifest。

验收证据：状态机契约测试、并发测试、V0.1/V0.2 Schema Compatibility Test、跨实现 Canonicalization Fixture、校验报告、Audit Event、已锁定 Manifest 导出与 checksum 复验。
