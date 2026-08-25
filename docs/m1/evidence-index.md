# M1 Evidence 索引

本索引只定义 Gate、执行入口和 CI Artifact 路径。它不记录候选 Commit SHA、运行状态或 Owner 决策。

| Gate | 执行入口 | CI Artifact 相对路径 |
|---|---|---|
| Candidate Evidence | `scripts/m1/verify.ps1` | `evidence/<commit>/evidence.json` |
| Full Backend Test | `scripts/m1/verify.ps1` | `evidence/<commit>/full-test-results/` |
| Contract | `scripts/tests/verify-contracts.tests.ps1` | `evidence/<commit>/evidence.json` 中的 `contract` Gate |
| Security / Concurrency | `scripts/m1/verify.ps1` | `evidence/<commit>/full-test-results/` |
| API Smoke / PostgreSQL Restore | `scripts/m1/acceptance-smoke.ps1` | `acceptance-smoke.json` |
| Schema Export | `scripts/m1/export-schema.ps1` | `schema.sql`、`schema-metadata.json` |
| Boot Artifact | `scripts/m1/verify.ps1` | `evidence/<commit>/evidence.json` 中的 report hash |

GitHub Actions Artifact 名称由 workflow 生成，格式为 `m1-evidence-<commit>`；Artifact 内根目录对应 `backend/build/m1/`。
