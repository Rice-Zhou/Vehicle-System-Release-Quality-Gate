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

## 10. Pilot / Company Archive Profiles

The deployment profile governs archive-capability readiness and acceptance interpretation only. It does not modify Release, Manifest, Evidence, Traceability, or the Quality Engine. `ArchiveEvidence.archive(ArchiveCommand)` is the only public archive entry point; callers cannot submit an `ArchivePolicy`, Capability Report, or `ArchiveAuthorization`. The same internal evaluator performs a fresh probe before every readiness query and archive command and generates a `policyFingerprint` and `checkedAt` bound to the complete configuration snapshot. An old report cannot be reused as authorization.

| Profile | `archive.enabled` | Measured Provider State | Archive Readiness | Long-Term Archive Conclusion |
|---|---:|---|---|---|
| `PILOT` | Any | `UNCONFIGURED` | UP, with the actual state reported | Must not record `PASS` |
| `PILOT` | `true` | `LOCAL_PILOT` | UP | Staging only; receipt has `longTerm=false` |
| `PILOT` | `true` | `EXTERNAL_VERIFIED` | UP | Only a successful S3 Archive Receipt can support a long-term conclusion |
| `COMPANY` | `false` | Any | DOWN | Fail closed |
| `COMPANY` | `true` | Not `EXTERNAL_VERIFIED` | DOWN | Fail closed |
| `COMPANY` | `true` | `EXTERNAL_VERIFIED` | UP | A successful receipt is still required before recording `PASS` |

Archive Capability is included only in the readiness group. Liveness does not depend on Object Storage, and other readiness checks remain present. A Provider failure must not trigger a process restart loop or be rewritten as `PILOT` success.

## 11. Archive Environment Variables

The deployment platform injects every variable. The six target-control booleans default to `true`; they express requirements, not external facts.

| Environment Variable | Default | Meaning |
|---|---|---|
| `VSRQG_DEPLOYMENT_MODE` | `PILOT` | `PILOT` or `COMPANY` |
| `VSRQG_EVIDENCE_ARCHIVE_ENABLED` | `true` | Archive operation switch; `COMPANY` is always NOT_READY when this is `false` |
| `VSRQG_EVIDENCE_ARCHIVE_CHECKSUM_VERIFICATION_ENABLED` | `true` | Require SHA-256 verification |
| `VSRQG_EVIDENCE_ARCHIVE_ENCRYPTION_REQUIRED` | `true` | Require an effective encryption control |
| `VSRQG_EVIDENCE_ARCHIVE_PRIVATE_ACCESS_REQUIRED` | `true` | Require an effective private-access control |
| `VSRQG_EVIDENCE_ARCHIVE_RETENTION_POLICY_REQUIRED` | `true` | Require an effective retention policy |
| `VSRQG_EVIDENCE_ARCHIVE_IMMUTABILITY_REQUIRED` | `true` | Require an effective immutability control |
| `VSRQG_EVIDENCE_ARCHIVE_PROVIDER` | `NONE` | `NONE`, `FILESYSTEM_STAGING`, or `S3_COMPATIBLE` |
| `VSRQG_EVIDENCE_ARCHIVE_STAGING_ROOT` | Empty | Pre-created absolute root for filesystem staging |
| `VSRQG_EVIDENCE_ARCHIVE_ENDPOINT` | Empty | Optional S3-compatible endpoint; leave empty for native AWS |
| `VSRQG_EVIDENCE_ARCHIVE_REGION` | Empty | S3 region |
| `VSRQG_EVIDENCE_ARCHIVE_BUCKET` | Empty | Private bucket with versioning and immutability controls |
| `VSRQG_EVIDENCE_ARCHIVE_OBJECT_PREFIX` | `acceptance/` | Relative object prefix |
| `VSRQG_EVIDENCE_ARCHIVE_ACCESS_OWNER` | Empty | Identifier of the controlled-access owner |
| `VSRQG_EVIDENCE_ARCHIVE_RETENTION_PERIOD` | Empty | Positive `Duration`, for example `P365D` |
| `VSRQG_EVIDENCE_ARCHIVE_PROBE_TIMEOUT` | `PT5S` | External Provider timeout for identity and control probes |
| `VSRQG_EVIDENCE_ARCHIVE_OPERATION_TIMEOUT` | `PT30S` | Upload, exact-version readback, and Head-check timeout; must not be shorter than the probe timeout |

An Endpoint must be an absolute `http` or `https` URI with a non-empty host and no user-info, query, or fragment. An error reports only the field rule and never echoes the URI. The prefix must be relative, use `/`, and must not be empty or contain a backslash or `..`. Timeouts and retention must be positive.

`VSRQG_EVIDENCE_ARCHIVE_PROBE_TIMEOUT` and `VSRQG_EVIDENCE_ARCHIVE_OPERATION_TIMEOUT` constrain external Provider requests only. Filesystem staging does not claim cancellable local I/O. It recovers through same-directory `.partial` files, digest recomputation, create-only atomic commit, failed-partial cleanup, and retry with a fresh probe.

## 12. `FILESYSTEM_STAGING` Operating Boundary

Filesystem staging is `LOCAL_PILOT` only. It is neither long-term archive nor a substitute for company deployment. Before enabling it, one Owner must pre-create the absolute root, confirm that the target filesystem supports hardlink create-only, and run a payload, receipt, failed-retry, and digest-recomputation smoke. If hardlink is unsupported, the operation must fail closed rather than degrade to an overwrite.

Deployment constraints: the root must not be on a network share or uncontrolled mount; the runtime identity must not be shared with an untrusted process; untrusted users must not be able to write the root. Cross-process, same-OS-identity TOCTOU attacks are outside the current V0.2 threat model and are mitigated by that deployment isolation. Kotlin `internal` is a source and module governance boundary, not a sandbox against hostile same-JVM reflection. V0.2 does not invent such isolation through JPMS or an additional module split.

A filesystem failure cleans only the partial owned by that invocation and does not delete a committed payload, receipt, or source file. An existing-target digest conflict, path escape, symlink, directory replacement, or cleanup failure stops the operation and preserves failure evidence.

## 13. `S3_COMPATIBLE` Cutover and Daily Controls

Perform a company cutover in this order:

1. Inject credentials through Secret Manager, workload identity, or a controlled environment. Never put an access key, secret key, or token in Git, YAML, Manifest, or an acceptance record.
2. The native AWS path uses STS `GetCallerIdentity` through the same default credential chain. A custom endpoint requires an approved equivalent identity attestor from trusted wiring. Configuration must never self-report a principal.
3. The probe obtains a Provider-attested `RuntimeIdentityRef`. Raw ARN, account, subject, user ID, and session name exist only in memory for normalization and hashing. Logs, health, receipts, and Evidence expose only allowlisted fields, and a receipt does not store the principal fingerprint.
4. Use `policyFingerprint`, identity fingerprint, and UTC date to create separate create-only `target.json` and `result.json` objects. Only one mutation winner is allowed for the tuple. A loser must re-attest the same identity and read the identity-bound `DailyControlRecord` by exact version. Two identities must each produce a winner and must not reuse each other's result.
5. Overwrite, delete, and bypass must all be `DENIED_AS_EXPECTED`. `ALLOWED`, `INDETERMINATE`, network error, timeout, 5xx, missing identity claim, invisible result, or binding mismatch all fail closed. A daily result expires at the next UTC midnight; lifecycle can clean an expired control object only after its retain-until.
6. Verify connection, encryption, private access, versioning, actual `COMPLIANCE` object protection, and effective retention. A bucket Object Lock flag alone cannot pass immutability.

## 14. Payload, Receipt, and Acceptance Evidence

The payload is content-addressed by source SHA-256; replay of the same source must resolve to the same exact `StoredObjectRef`. Every upload, readback, and HeadObject-style protection check binds the bucket, key, `versionId`, digest, and size returned by Put. Reading latest is prohibited; a version shadow, delete marker, concurrent replacement, or field mismatch fails closed.

The Archive Receipt is fully canonicalized before its SHA-256 is used for content-addressed create-if-absent storage. An identical candidate may replay the same exact receipt reference. A new `checkedAt`, `archivedAt`, or Capability fact creates a new immutable receipt without overwriting an old receipt. The receipt records the exact payload reference but not its own locator, version, or digest. A separate `ArchiveReceiptReference` stores the receipt's exact version and digest, avoiding a self-hash cycle.

Only when payload and receipt both pass actual mode, retain-until, identity-bound control, and exact-version verification may the operation return `longTerm=true`. The acceptance record must save the successful `ArchiveReceiptReference` selected for that run. Evidence retention must not be recorded as `PASS` without a successful receipt. This configuration does not automatically change the current `M1-OWNER-GATE-001` state from `CONDITIONAL`.

## 15. Archive Failure and Rollback

- Probe, identity-attestation, or control failure: remain `EXTERNAL_UNVERIFIED`; repair configuration, permission, or Provider, then start a new probe. Do not retain old authorization.
- Upload, readback, Head, or receipt failure: retain the source file, control target/result, payload, and any uploaded receipt for inventory reconciliation. Clean only the current temporary download file.
- Digest, version, or protection mismatch: stop the company release and inspect the exact-version inventory. Never fall back to latest or overwrite an expected value with a new digest.
- Identity change or UTC-date boundary: create a new identity/date control. Do not delete or reuse the old control.
- Configuration rollback: switching back to `PILOT` is allowed only to restore non-production development. Never reduce effective retention, delete a source object, use a bypass identity, or rewrite staging as long-term success.
- Provider migration: first produce a version-aware inventory, then copy and compare key, version, size, SHA-256, and protection for every object. Do not delete source objects before cutover verification succeeds.
