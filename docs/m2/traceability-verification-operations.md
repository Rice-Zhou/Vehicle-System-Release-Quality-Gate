# M2.5 Traceability Verification Snapshot Operations Specification

## 1. Runtime boundary

M2.5 enables asynchronous Traceability Verification only in the Pilot Profile. It consumes the Locked Manifest, M2.3 Issue Snapshot, and M2.4 Edge Revisions already fixed in PostgreSQL and produces an immutable Snapshot. Execution does not access Jira, GitHub, CI, Device, or a Company environment, and it does not produce `Verified=true`.

Keep both the entry point and Worker disabled by default:

```text
VSRQG_TRACEABILITY_VERIFICATION_ENABLED=false
VSRQG_TRACEABILITY_VERIFICATION_WORKER_ENABLED=false
```

Pilot may explicitly set both variables to `true` only after the candidate commit's M2.5 Gate, real PostgreSQL tests, Evidence digest, and Owner Gate all pass. The Worker's `VSRQG_TRACEABILITY_VERIFICATION_POLL_INTERVAL` and `VSRQG_TRACEABILITY_VERIFICATION_INITIAL_DELAY` use ISO-8601 Duration; Pilot should retain the default `PT1S` rather than using high-frequency polling as a substitute for capacity validation. The Company Profile remains disabled and must not inherit the Pilot enablement state or identity.

## 2. Deployment sequence

Use this fixed deployment sequence:

1. Create a recoverable PostgreSQL backup and record the backup locator, current Flyway version, and application-image digest.
2. Run the V11 Migration Constraint Test against the target database. Migration is forward-only; do not run a down migration.
3. Deploy the same Backend image corresponding to the candidate commit while keeping the entry point and Worker disabled.
4. With a synthetic Pilot fixture, run one complete known-chain Smoke and one Smoke for each fixed Gap. Confirm `Fixed`, `Included`, and the fixed `Verified=false`, then reread the Snapshot digest.
5. Explicitly enable the Pilot entry point and Worker only after confirming that historical Snapshot response bytes and digest are unchanged, the Worker backlog is observable, and no abnormal Dead Letter exists.

The known-chain/gap Smoke uses only a locally synthesized Project, Release, Manifest, Issue Snapshot, and Edge Revision. It neither authorizes nor connects to a real Company, Jira, GitHub, CI, or Device environment.

## 3. Fixed diagnostics and checks

Only these fixed diagnostics may appear in external and operational Evidence:

- `TRACEABILITY_INPUT_NOT_VALID`: the fixed input identity, digest, authority, or state is untrusted. Disable the entry point and retain the Run, Input Ledger, and Migration/Gate Evidence.
- `TRACEABILITY_INPUT_LIMIT_EXCEEDED`: fixed Edges exceed 2,000. Do not truncate them, shrink them and claim success, or modify an old Run.
- `TRACEABILITY_VERIFICATION_RETRY_SCHEDULED`: an infrastructure failure remains within the bounded retry policy. Check PostgreSQL availability and the Worker backlog.
- `TRACEABILITY_VERIFICATION_RETRY_EXHAUSTED`: the Run is `FAILED` and the Job is `DEAD_LETTER`; the old terminal state must not be resumed.
- `PERSISTENCE_UNAVAILABLE`: the database is unavailable. Entry and reads remain fail-closed, with no fallback to JSON, files, or cache.

Fixed diagnostics must not include SQL, JDBC URLs, credentials, tokens, original payloads, external Issue content, local paths, or stack traces.

## 4. Backlog and Dead Letter

In an authorized read-only database session, inspect only these aggregate metrics filtered by `job_type='TRACEABILITY_VERIFY'`:

- count of `QUEUED`, earliest `available_at`, and backlog age;
- count of `RUNNING`, earliest `started_at`, and count beyond the 300-second lease;
- count of `DEAD_LETTER`, fixed `diagnosticCode`, and corresponding Verification Run ID;
- counts of Run `QUEUED/RUNNING/SUCCEEDED/FAILED`, and whether every successful Run points to a complete Snapshot.

Do not export Job payloads, request bodies, or database exception text. A poison job must remain `DEAD_LETTER` after three attempts. After correcting the root cause, create a new Verification Run with a new `Idempotency-Key`. A manual retry does not update the old Run, old Job, old Snapshot, or old digest.

## 5. Failure recovery

For an application failure, first set both Pilot flags to `false` and restart the previous compatible image. Keep the V11 extensions in the database and roll the database forward only: repair Schema defects with a new forward Migration. Do not roll back a Migration, delete history, or bypass an immutable trigger.

After database recovery, perform these fixed actions:

1. Verify the Flyway version and expected Migration chain.
2. Use a PostgreSQL custom-format dump to restore the relational closure of a completed Snapshot into an independent PostgreSQL instance, then recompute the canonical digest from that instance's fixed relational facts.
3. Before and after restarting the independent restored instance, compare `pg_postmaster_start_time()` through fresh connections; the later value must be strictly newer. Then use a fresh repository to verify that a `RUNNING` Job persisted before the crash can be reclaimed after its 300-second lease and that its attempt increases monotonically. The drill must not restart a shared test or production database.
4. Run the known-chain/gap/replay, transaction, concurrency, recovery, and security Gates.
5. If a digest differs, a Snapshot is incomplete, or a fixed input cannot be loaded, stop using the result immediately, retain Evidence, and keep the entry point disabled.

Do not reconstruct a historical Snapshot from the latest Edge Revision, latest Issue Snapshot, external systems, JSON, files, or cache. A historical conclusion may be explained only from its fixed input and immutable Snapshot; every new verification must create a new Run.

## 6. Candidate Gate and Evidence

Run these commands on a clean, fixed candidate commit:

```powershell
pwsh -NoProfile -File scripts/tests/m2-5-verify-gates.tests.ps1
pwsh -NoProfile -File scripts/m2/verify-m25.ps1
```

The Gate runs clean-tree, fixed-commit, contract, migration, domain, transaction, concurrency, replay/recovery, performance, secret, acceptance, and evidence-digest in that fixed order. If any check fails, the overall status is `FAILED`, but the Gate still produces a redacted `backend/build/m2/m2-5-evidence.json` and SHA-256 sidecar. A later PASS must not overwrite an earlier failure.

Before upload, performance and recovery child reports must pass a recursive exact-property allowlist and be reconstructed from explicit fields. The total Evidence and both child reports must reject secrets and Windows/Unix absolute paths. Any unexpected field or leak fails the Gate with the fixed `EVIDENCE_INVALID` and excludes the affected child reports from the Artifact.

Performance Evidence uses exactly 20 Issues, 2,000 Edges, and at least three samples. It records start/worker/query p50, p95, max, hard limits, reference targets, and hardware/runtime metadata. The reference P95 targets are `≤1s/≤10s/≤1s`; the relaxed shared-CI hard limits only detect algorithmic regression and are not Company performance acceptance. The fixture must not be skipped, truncated, or reduced.

The GitHub Actions workflow has only `contents: read`, configures no Provider credential, makes no Company call, and uploads `m2-5-evidence-${{ github.sha }}`. Evidence, the bilingual Pair Gate, and exact-head CI success form candidate material only; the Owner Decision must remain `PENDING` until an independent review.
