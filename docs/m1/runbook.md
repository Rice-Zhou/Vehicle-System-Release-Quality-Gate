# M1 Operations and Recovery Runbook

## 1. Scope

This runbook covers startup, candidate verification, backup, restore, and migration rollback for the M1 Release/Manifest authority baseline. It does not replace production infrastructure, OIDC, or Artifact payload validator operating procedures.

## 2. Responsibilities

| Role | Responsibility |
|---|---|
| Project Owner | Review the Acceptance Checklist, known limitations, and Evidence, then make the final decision |
| Release Engineer | Run the single gate entry point against a committed candidate SHA with a clean worktree |
| Platform Operator | Provide JDK, Node.js, pnpm, Docker, OIDC, and the application runtime |
| Database Operator | Perform PostgreSQL backup, restore, integrity verification, and retention |
| Security Owner | Review OIDC/RBAC configuration and the trusted validator-version allowlist |

## 3. Prerequisites

- JDK 21, Node.js 24, and pnpm 11.19.0.
- An available Docker daemon that can pull the exact `postgres:17.11` image.
- Candidate changes are committed and `git status --porcelain` has no output.
- Before production Lock, integrate a validator that reads Artifact payloads and recalculates checksums, then configure its exact version through `VSRQG_TRUSTED_MANIFEST_VALIDATOR_VERSIONS`.

The M1 smoke uses the controlled `m1-acceptance-validator/1` fixture to verify the mechanical Lock/restore path. It is not a production checksum validator and is not production evidence of Artifact integrity.

## 4. Candidate Verification

Run from the repository root:

```powershell
./scripts/m1/verify.ps1
```

The script runs locked dependency installation, Contract validation, the full backend test suite, Security/Concurrency coverage, a two-PostgreSQL smoke/restore, and schema export in order. Any failed gate returns a non-zero exit code and records the actual failure in `backend/build/m1/evidence/<commit>/evidence.json`.

## 5. Development Startup

```powershell
$env:VSRQG_DB_PASSWORD = "<managed-secret>"
docker compose -f deploy/dev/compose.yml up -d postgres
./backend/gradlew -p backend bootRun
```

The deployment system must also inject `VSRQG_OIDC_ISSUER_URI`, `VSRQG_OIDC_AUDIENCE`, DataSource parameters, and the trusted validator allowlist. Passwords, tokens, and private keys must not enter the repository or Evidence artifact.

## 6. Backup

Before a state transition or migration deployment, the Database Operator creates a custom-format backup:

```powershell
docker compose -f deploy/dev/compose.yml exec -T postgres `
  pg_dump -U vsrqg -d vsrqg --format=custom --no-owner --no-privileges --file=/tmp/vsrqg.dump
docker compose -f deploy/dev/compose.yml cp postgres:/tmp/vsrqg.dump ./vsrqg.dump
Get-FileHash -Algorithm SHA256 ./vsrqg.dump
```

An external change record must link the backup to its candidate commit, database version, creation time, SHA-256, and retention location. Do not commit the backup file to Git.

## 7. Restore Verification

Restore into a new empty database instance. Never overwrite the only production instance:

```powershell
docker run --name vsrqg-restore -e POSTGRES_PASSWORD=<managed-secret> `
  -e POSTGRES_USER=vsrqg -e POSTGRES_DB=vsrqg -d postgres:17.11
docker cp ./vsrqg.dump vsrqg-restore:/tmp/vsrqg.dump
docker exec vsrqg-restore pg_restore -U vsrqg -d vsrqg `
  --exit-on-error --no-owner --no-privileges /tmp/vsrqg.dump
```

After restore, connect the candidate application to the restored database and export the same Locked Manifest. The `contentDigest`, locked Validation, Audit timeline, and Release state must match the source database.

## 8. Migration Rollback

Flyway migrations are forward-only; destructive automatic down migrations are not provided. To roll back:

1. Stop write traffic and application instances.
2. Preserve the failed instance and logs; do not edit `flyway_schema_history`.
3. Restore the pre-migration backup into a new PostgreSQL 17.11 instance.
4. Deploy the verified application commit from before the migration.
5. Perform read-only checks of Manifest digests, Release state, and Audit counts.
6. Require confirmation from both the Database Operator and Project Owner before switching traffic.

Do not manually delete migration records, rewrite a Locked Manifest in place, or rewrite failed Evidence as successful.

## 9. Failure Handling

- Contract/Build/Test failure: retain `evidence.json` and test reports; fix the problem, create a new commit, and run again.
- Smoke/Restore failure: retain the failed gate. If `backend/build/m1/m1.dump` exists, record its hash and retain it. Testcontainers reclaims the containers; use the CI job log when additional diagnostics are required. Do not rerun only the successful portion.
- Digest mismatch: stop candidate release and record a Finding. Never erase a difference by recalculating the target value.
- A required change to the Core Contract, Manifest authority, or transaction boundary: stop implementation and submit an ADR Proposal.
