# M1 Evidence Index

This index defines only gates, execution entry points, and CI artifact paths. It does not record candidate commit SHAs, run status, or Owner decisions.

| Gate | Entry point | Relative path in CI artifact |
|---|---|---|
| Candidate Evidence | `scripts/m1/verify.ps1` | `evidence/<commit>/evidence.json` |
| Full Backend Test | `scripts/m1/verify.ps1` | `evidence/<commit>/full-test-results/` |
| Contract | `scripts/tests/verify-contracts.tests.ps1` | `contract` gate in `evidence/<commit>/evidence.json` |
| Security / Concurrency | `scripts/m1/verify.ps1` | `evidence/<commit>/full-test-results/` |
| API Smoke / PostgreSQL Restore | `scripts/m1/acceptance-smoke.ps1` | `acceptance-smoke.json` |
| Schema Export | `scripts/m1/export-schema.ps1` | `schema.sql`, `schema-metadata.json` |
| Boot Artifact | `scripts/m1/verify.ps1` | Report hash in `evidence/<commit>/evidence.json` |

The workflow generates the GitHub Actions artifact name as `m1-evidence-<commit>`. Its root corresponds to `backend/build/m1/`.
