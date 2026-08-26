# M1 运行与恢复手册

## 1. 适用范围

本手册覆盖 M1 Release/Manifest authority baseline 的启动、候选验证、备份、恢复和 Migration 回退。它不替代生产基础设施、OIDC 或 Artifact payload validator 的运维手册。

## 2. 责任分工

| 角色 | 责任 |
|---|---|
| Project Owner | 审核 Acceptance Checklist、已知限制和 Evidence，给出最终决定 |
| Release Engineer | 对已提交且工作树干净的候选 SHA 执行唯一 Gate 入口 |
| Platform Operator | 提供 JDK、Node.js、pnpm、Docker、OIDC 和应用运行环境 |
| Database Operator | 执行 PostgreSQL 备份、恢复、完整性验证和保留策略 |
| Security Owner | 审核 OIDC/RBAC 配置与可信 validator version allowlist |

## 3. 前置条件

- JDK 21、Node.js 24、pnpm 11.19.0。
- Docker daemon 可用，并能够拉取精确镜像 `postgres:17.11`。
- 候选修改已经提交，`git status --porcelain` 无输出。
- 生产 Lock 前必须接入能够读取 Artifact payload 并复算 checksum 的 validator，并通过 `VSRQG_TRUSTED_MANIFEST_VALIDATOR_VERSIONS` 配置精确版本。

M1 Smoke 使用 `m1-acceptance-validator/1` 受控 fixture 验证 Lock/restore 机械链路。它不是生产 checksum 验证器，也不能作为生产 Artifact 完整性证据。

## 4. 候选验证

在仓库根目录执行：

```powershell
./scripts/m1/verify.ps1
```

脚本按顺序执行依赖锁定、Contract、全量后端测试、Security/Concurrency、双 PostgreSQL Smoke/Restore 和 Schema Export。任一 Gate 失败立即返回非零退出码，同时在 `backend/build/m1/evidence/<commit>/evidence.json` 记录真实失败。

## 5. 开发环境启动

```powershell
$env:VSRQG_DB_PASSWORD = "<managed-secret>"
docker compose -f deploy/dev/compose.yml up -d postgres
./backend/gradlew -p backend bootRun
```

应用还需要从部署系统注入 `VSRQG_OIDC_ISSUER_URI`、`VSRQG_OIDC_AUDIENCE`、DataSource 参数和可信 validator allowlist。密码、Token 和私钥不得写入仓库或 Evidence Artifact。

## 6. 备份

在状态迁移或部署 Migration 前，由 Database Operator 创建 custom-format 备份：

```powershell
docker compose -f deploy/dev/compose.yml exec -T postgres `
  pg_dump -U vsrqg -d vsrqg --format=custom --no-owner --no-privileges --file=/tmp/vsrqg.dump
docker compose -f deploy/dev/compose.yml cp postgres:/tmp/vsrqg.dump ./vsrqg.dump
Get-FileHash -Algorithm SHA256 ./vsrqg.dump
```

备份必须与候选 commit、数据库版本、创建时间、SHA-256 和保留位置建立外部变更记录；备份文件不得提交到 Git。

## 7. 恢复验证

恢复必须写入新的空数据库实例，禁止覆盖唯一生产实例：

```powershell
docker run --name vsrqg-restore -e POSTGRES_PASSWORD=<managed-secret> `
  -e POSTGRES_USER=vsrqg -e POSTGRES_DB=vsrqg -d postgres:17.11
docker cp ./vsrqg.dump vsrqg-restore:/tmp/vsrqg.dump
docker exec vsrqg-restore pg_restore -U vsrqg -d vsrqg `
  --exit-on-error --no-owner --no-privileges /tmp/vsrqg.dump
```

恢复后使用候选应用连接恢复库，导出同一 Locked Manifest。恢复前后的 `contentDigest`、锁定 Validation、Audit Timeline 和 Release 状态必须一致。

## 8. Migration 回退

Flyway Migration 采用 forward-only 策略，不提供破坏性的自动 down migration。需要回退时：

1. 停止写流量和应用实例。
2. 保留失败实例及日志，不修改 `flyway_schema_history`。
3. 从 Migration 前备份恢复到新的 PostgreSQL 17.11 实例。
4. 部署 Migration 前的已验证应用 commit。
5. 执行只读 Manifest digest、Release 状态和 Audit 数量核对。
6. 切换流量前由 Database Operator 与 Project Owner 双人确认。

禁止手工删除 Migration 记录、原地回写 Locked Manifest 或把失败 Evidence 改写为成功。

## 9. 故障处理

- Contract/Build/Test 失败：保留 `evidence.json` 和测试报告，修复后创建新 commit 再运行。
- Smoke/Restore 失败：保留失败 Gate；若 `backend/build/m1/m1.dump` 存在，记录其 hash 并保留。容器由 Testcontainers 自动回收，需补充诊断时从 CI Job 日志取证；不得只重跑成功部分。
- digest 不一致：停止候选发布并登记 Finding；不得以重新计算目标值消除差异。
- 需要修改 Core Contract、Manifest authority 或事务边界：停止实施并提交 ADR Proposal。

## 10. Pilot / Company 归档 Profile

部署 Profile 只决定归档能力的就绪度和验收解释，不修改 Release、Manifest、Evidence、Traceability 或 Quality Engine。`ArchiveEvidence.archive(ArchiveCommand)` 是唯一公开归档入口；调用方不能提交 `ArchivePolicy`、Capability Report 或 `ArchiveAuthorization`。同一个 internal evaluator 在每次 readiness 查询和每次归档命令前执行新鲜 probe，生成绑定完整配置快照的 `policyFingerprint` 与 `checkedAt`；旧报告不能复用为授权。

| Profile | `archive.enabled` | Provider 实测状态 | Archive readiness | 长期归档结论 |
|---|---:|---|---|---|
| `PILOT` | 任意 | `UNCONFIGURED` | UP，并如实展示状态 | 不得记录 `PASS` |
| `PILOT` | `true` | `LOCAL_PILOT` | UP | 只允许 staging，回执 `longTerm=false` |
| `PILOT` | `true` | `EXTERNAL_VERIFIED` | UP | 仅成功 S3 Archive Receipt 可支持长期结论 |
| `COMPANY` | `false` | 任意 | DOWN | fail closed |
| `COMPANY` | `true` | 非 `EXTERNAL_VERIFIED` | DOWN | fail closed |
| `COMPANY` | `true` | `EXTERNAL_VERIFIED` | UP | 仍须成功回执才能记录 `PASS` |

归档 Capability 只加入 readiness group；liveness 不依赖对象存储，其他 readiness 检查继续保留。Provider 故障不得触发进程重启循环，也不得被改写为 `PILOT` 成功。

## 11. 归档环境变量

所有变量由部署平台注入。六个目标控制默认 `true`，只表示要求，不代表外部事实。

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `VSRQG_DEPLOYMENT_MODE` | `PILOT` | `PILOT` 或 `COMPANY` |
| `VSRQG_EVIDENCE_ARCHIVE_ENABLED` | `true` | 归档操作开关；`COMPANY` 设为 `false` 时必定 NOT_READY |
| `VSRQG_EVIDENCE_ARCHIVE_CHECKSUM_VERIFICATION_ENABLED` | `true` | 要求 SHA-256 校验 |
| `VSRQG_EVIDENCE_ARCHIVE_ENCRYPTION_REQUIRED` | `true` | 要求实际加密控制 |
| `VSRQG_EVIDENCE_ARCHIVE_PRIVATE_ACCESS_REQUIRED` | `true` | 要求实际私有访问控制 |
| `VSRQG_EVIDENCE_ARCHIVE_RETENTION_POLICY_REQUIRED` | `true` | 要求实际保留策略 |
| `VSRQG_EVIDENCE_ARCHIVE_IMMUTABILITY_REQUIRED` | `true` | 要求实际不可变控制 |
| `VSRQG_EVIDENCE_ARCHIVE_PROVIDER` | `NONE` | `NONE`、`FILESYSTEM_STAGING` 或 `S3_COMPATIBLE` |
| `VSRQG_EVIDENCE_ARCHIVE_STAGING_ROOT` | 空 | filesystem 的预创建绝对根目录 |
| `VSRQG_EVIDENCE_ARCHIVE_ENDPOINT` | 空 | 可选 S3-compatible endpoint；AWS 原生路径留空 |
| `VSRQG_EVIDENCE_ARCHIVE_REGION` | 空 | S3 region |
| `VSRQG_EVIDENCE_ARCHIVE_BUCKET` | 空 | 私有且启用版本与不可变控制的 bucket |
| `VSRQG_EVIDENCE_ARCHIVE_OBJECT_PREFIX` | `acceptance/` | 相对 object prefix |
| `VSRQG_EVIDENCE_ARCHIVE_ACCESS_OWNER` | 空 | 受控访问责任方标识 |
| `VSRQG_EVIDENCE_ARCHIVE_RETENTION_PERIOD` | 空 | 正 `Duration`，例如 `P365D` |
| `VSRQG_EVIDENCE_ARCHIVE_PROBE_TIMEOUT` | `PT5S` | identity 与 control probe 的外部 Provider 超时 |
| `VSRQG_EVIDENCE_ARCHIVE_OPERATION_TIMEOUT` | `PT30S` | 上传、精确版本回读与 Head 检查超时；不得短于 probe timeout |

Endpoint 仅接受具有非空 host、无 user-info/query/fragment 的绝对 `http` 或 `https` URI。错误只报告字段规则，不回显 URI。prefix 必须为相对路径，使用 `/`，不得为空、包含反斜杠或 `..`。timeout 必须为正，retention 必须为正。

`VSRQG_EVIDENCE_ARCHIVE_PROBE_TIMEOUT` 和 `VSRQG_EVIDENCE_ARCHIVE_OPERATION_TIMEOUT` 只约束外部 Provider 请求。filesystem 不宣称可取消本地 I/O；它通过同目录 `.partial`、摘要复算、create-only 原子提交、失败清理和新鲜 probe 重试恢复。

## 12. `FILESYSTEM_STAGING` 运维边界

filesystem 仅用于 `LOCAL_PILOT`，不是长期归档，也不是公司上线替代方案。启用前由单一 Owner 预创建绝对根目录，确认目标文件系统支持 hardlink create-only，并执行一次 payload、receipt、失败重试和摘要复算 smoke。若 hardlink 不受支持，必须 fail closed，不能退化为覆盖写。

部署限制：根目录不得位于网络共享或不受控 mount；运行身份不得与不可信进程共享；不得允许不可信用户写入根目录。跨进程、同一 OS identity 的 TOCTOU 攻击不属于 V0.2 当前 threat model，必须通过上述部署隔离补偿。Kotlin `internal` 是源码与 module 治理边界，不是针对敌对同 JVM reflection 的 sandbox；V0.2 不以 JPMS 或额外 module 拆分虚构隔离能力。

filesystem 失败只清理本次拥有的 partial，不删除已提交 payload、receipt 或源文件。现有目标摘要冲突、路径逃逸、symlink、目录替换或清理失败均停止操作并保留错误证据。

## 13. `S3_COMPATIBLE` 切换与日控制

公司切换前按以下顺序执行：

1. 以 Secret Manager、工作负载身份或受控环境注入凭据；禁止在 Git、YAML、Manifest 或验收记录中填写 access key、secret key 或 token。
2. AWS 原生路径使用同一 default credential chain 的 STS `GetCallerIdentity`；自定义 endpoint 必须由受信 wiring 注入经批准的等价 identity attestor。禁止通过配置自报主体。
3. probe 取得 Provider-attested `RuntimeIdentityRef`。原始 ARN、account、subject、user ID 和 session name 仅在内存中规范化并哈希，日志、health、receipt 与 Evidence 只能使用允许字段，且 receipt 不保存 principal fingerprint。
4. 以 `policyFingerprint`、identity fingerprint 和 UTC 日期分别建立 create-only `target.json` 与 `result.json`。同一三元组只允许一个 mutation winner；loser 必须重新证明相同身份，并按 exact version 读取 identity-bound `DailyControlRecord`。两个身份必须各自产生 winner，禁止交叉复用。
5. 只有 overwrite、delete、bypass 都是 `DENIED_AS_EXPECTED` 才通过。`ALLOWED`、`INDETERMINATE`、网络错误、timeout、5xx、无 identity claim、结果未可见或绑定不一致均 fail closed。日结果在下一个 UTC 零点失效；过期 control object 只能在其 retain-until 后由 lifecycle 清理。
6. 验证 connection、encryption、private access、versioning、实际 `COMPLIANCE` object protection 和有效保留期。仅 bucket Object Lock 开关不能通过不可变性检查。

## 14. Payload、Receipt 与验收证据

payload 以源 SHA-256 内容寻址；同一源重放必须解析为同一 exact `StoredObjectRef`。每次上传、回读和 HeadObject-style 保护检查都绑定 Put 返回的 bucket、key、`versionId`、digest 与 size，禁止读取 latest；version shadow、delete marker、并发替换或字段不一致均 fail closed。

Archive Receipt 先完成 canonical 序列化，再按完整 receipt bytes 的 SHA-256 内容寻址并 create-if-absent。完全相同 candidate 可重放同一 exact receipt ref；新的 `checkedAt`、`archivedAt` 或 Capability 事实产生新的不可变 receipt，旧 receipt 不覆盖。receipt 内记录 payload exact ref，但不包含自身 locator/version/digest；独立 `ArchiveReceiptReference` 保存 receipt exact version 与 digest，避免自哈希循环。

只有 payload 和 receipt 都通过实际 mode、retain-until、identity-bound control 与精确版本验证，才返回 `longTerm=true`。验收记录必须保存本次选择的成功 `ArchiveReceiptReference`；没有成功回执时 Evidence retention 不得写为 `PASS`。本配置不会自动改变当前 `M1-OWNER-GATE-001` 的 `CONDITIONAL` 状态。

## 15. 归档故障与回滚

- probe、identity attestation 或 control 失败：保持 `EXTERNAL_UNVERIFIED`，重新修复配置、权限或 Provider 后发起新 probe；不沿用旧授权。
- 上传、回读、Head 或 receipt 失败：保留源文件、control target/result、payload 与任何已上传 receipt 供 inventory 对账；只清理本次临时下载文件。
- 摘要、version 或 protection 不一致：停止公司发布，检查 exact version inventory；不得 fallback 到 latest 或用新摘要覆盖预期值。
- 身份变化或跨 UTC 日期：创建新的 identity/date control，不删除或复用旧 control。
- 配置回滚：仅可切回 `PILOT` 恢复非生产研发；不得降低已生效保留期、删除源对象、使用旁路身份或把 staging 改写为长期成功。
- Provider 迁移：先生成 version-aware inventory，逐对象复制并比对 key、version、size、SHA-256 和保护；切流验证完成前不删除源对象。
