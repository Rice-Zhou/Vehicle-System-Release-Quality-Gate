# M1 验收清单

Project Owner 使用本清单验收候选，不直接修改测试结果。每一项必须能定位到机器 Gate 和 CI Artifact；缺失证据记为 `UNKNOWN`，不能视为通过。

| Milestone | 验收项 | Gate / Evidence | Owner 检查 |
|---|---|---|---|
| M1.0 | JDK、Kotlin、Spring Boot、Gradle、PostgreSQL 与 CI 版本固定 | `dependencies`、`build-test-security-concurrency`；`evidence.json` | 版本可复现且无浮动生产依赖 |
| M1.1 | Flyway 从空库创建权威 Schema；FK、唯一约束、append-only 与 Locked 不可变约束生效 | 全量 backend test、`schema-export`；Schema 与 Migration test report | Schema 与 V0.2 数据设计一致 |
| M1.2 | OIDC issuer/audience、project RBAC、不可见资源、Audit/Outbox/Idempotency 原子性 | Security、Idempotency 和 rollback tests | 未授权访问失败且失败不留下业务写入 |
| M1.3 | V0.1 hash 未变；V0.2 Schema、JCS 和 JVM/Node digest 一致 | `contract`、Manifest Contract test | 冻结资产未变化，canonical digest 可复算 |
| M1.4 | Manifest Revision、Artifact、Validation、并发 Lock、ETag、不可变 Export | Manifest Registration 与 Lock tests | 仅可信 `VALID` 可 Lock，恰好一个并发成功 |
| M1.5 | API 全链、PostgreSQL 17.11 dump/restore、恢复后 digest/Audit 不变 | `smoke-recovery`、`schema-export`、Smoke report | 原库与恢复库导出 digest 完全一致 |

## Owner 决策前检查

- [ ] 候选 commit 与 `evidence.json.commit` 完全一致。
- [ ] `evidence.json.status` 是实际 Gate 结果，且 `ownerDecision` 仍为 `PENDING`。
- [ ] 所有 Gate command、开始/结束时间和 exit code 已记录。
- [ ] 报告清单包含实际 SHA-256，不包含凭据或 Token。
- [ ] Smoke 明确标记 `m1-acceptance-validator/1` 为 fixture。
- [ ] 生产 validator、OIDC、备份保留和运行负责人已经落实；未落实项进入残余风险。
- [ ] 中英文候选分支的非 Markdown 文件逐字节一致。
- [ ] 没有自动合并 `main`/`release`，没有提前创建 M1 Tag。

## 决策

Owner 在仓库外或批准记录中填写 `APPROVE`、`REJECT` 或 `CONDITIONAL`，并引用候选 commit 与 CI Artifact。静态文档和候选 `evidence.json` 不预填批准结论。
