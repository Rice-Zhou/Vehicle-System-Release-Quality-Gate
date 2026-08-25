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
