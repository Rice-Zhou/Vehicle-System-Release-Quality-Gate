# TDR-014 — Bounded Jira CLI Pilot Adapter and Fixture Contract

- Status: Accepted
- Date: 2026-08-28
- Compatibility revision: 2026-08-31, based on the Owner-authorized Jira CLI v1.7.0 Windows Pilot Smoke
- Decision basis: Project Owner approval of the `M2-KD-2026-08-28-01` Written Spec Review
- Scope: deterministic contract testing for the M2 Issue Source Adapter and read-only Smoke against real Jira in the Pilot environment
- Related decisions: [TDR-001](TDR-001-modular-monolith.md), [TDR-002](TDR-002-kotlin-spring-boot.md), [TDR-003](TDR-003-postgresql.md), [TDR-005](TDR-005-rest-openapi.md), [TDR-007](TDR-007-postgresql-job-outbox.md), [TDR-009](TDR-009-oidc-and-service-identities.md), [TDR-011](TDR-011-pilot-company-deployment-profiles.md)

## 1. Why this technology was selected

V0.2 uses a shared `IssueSourcePort`, a deterministic Fixture Contract Suite, and an optional Jira CLI Pilot Adapter. The Fixture Adapter is the authoritative repeatable CI input for pagination, mapping, retry, cursor recovery, and idempotency behavior. Only in an explicitly enabled `PILOT` Profile does the Jira CLI Pilot Adapter use the `jira` CLI already configured and authenticated on the runner to perform a bounded read-only query against one project configured outside the repository.

The default and hard limit for a real query are both 20 Issues. The allowed command shape is fixed:

```text
jira issue list --project <configured-project> --paginate 0:<1..20> --plain --no-headers --no-truncate --columns KEY,SUMMARY,STATUS,PRIORITY,UPDATED --delimiter=<U+241F>
```

The Adapter must use a `ProcessBuilder` argument array or equivalent API; shell-string concatenation is prohibited. Project, executable, and limit come from repository-external configuration. An API caller cannot inject JQL, search text, additional flags, or an arbitrary executable path. `--raw`, Comment, History, Attachment, and fields outside the allowlist are prohibited. CLI credentials and the CLI configuration file remain under the external security mechanism and must not be parsed or copied by the application.

`--delimiter=<U+241F>` must be passed as one argv element. The real Windows Pilot proved that Jira CLI v1.7.0 does not reliably retain a custom delimiter when the flag and value are separate arguments, and its Go `tabwriter` does not retain the `U+001F` C0 control character. The printable Unit Separator Symbol `U+241F` reliably produces five columns. If a field itself contains that fixed symbol, the record still fails closed because its column count is not five. The Jira CLI `UPDATED` transport shape `uuuu-MM-dd'T'HH:mm:ss.SSSxx` is parsed strictly only at the Adapter boundary and normalized to a UTC `Instant`; unknown timestamp shapes remain rejected.

The real Jira Smoke only proves that the current identity, network, CLI, and field-mapping path work. It does not replace the Fixture Contract Suite and does not establish Company Ready Evidence. Until a formal API Contract exists, an internal Issue Source uses only synthetic or fully sanitized recorded fixtures and is verified through the same Port.

## 2. What problem it solves

Issue, Release Issue Snapshot, and Traceability need a stable, replayable data contract. At the same time, Pilot must prove that the system can read real Jira without making changing corporate data, network, or permissions a nondeterministic CI dependency. This decision verifies the two goals separately: Fixtures provide deterministic failure injection and regression; bounded real Smoke provides evidence that the current external read path is authentic and working.

This decision only implements the frozen V0.1 Source Adapter responsibility. It does not leak Jira DTOs into Core or change Issue, Release, Manifest, Evidence, Traceability, Fixed/Included/Verified, or Quality Result semantics.

## 3. Why alternatives were not selected

- Fixture-only: cannot prove that the real Jira identity, CLI, and read path currently work.
- Access real Jira by default in CI: network, permission, and changing data would break the deterministic Gate and make a corporate system a build dependency.
- Integrate Jira REST API now: Pilot already has a usable CLI identity; REST adds credentials, an HTTP Client, pagination, and deployment configuration without enough benefit at the current query scale.
- Parse local Jira CLI credentials or configuration: expands secret exposure and turns private third-party configuration into an application contract.
- Use `--raw` and filter afterward: a real schema probe showed that it returns out-of-scope fields such as Description, Comment, Reporter, and Assignee, violating data minimization.
- Query every project or all Issues now: there is no capacity, throttling, sensitive-field, retention, or deletion-governance Evidence yet.
- Add Kafka, a separate Adapter Service, or a second database: no current requirement for a single-project Pilot of at most 20 records justifies those infrastructure surfaces.

## 4. Impact on V0.2

M2 adds one replaceable Jira CLI Adapter while continuing to use the modular monolith, PostgreSQL Job/Outbox, existing RBAC, Audit, and Idempotency. The configuration contract is:

```text
VSRQG_JIRA_PILOT_ENABLED=false
VSRQG_JIRA_CLI_PATH=<absolute path, required when enabled>
VSRQG_JIRA_PROJECT=<single project key, required when enabled>
VSRQG_JIRA_MAX_ISSUES=20
VSRQG_JIRA_TIMEOUT=PT15S
```

Startup must fail when enablement requirements are not met, the limit is outside 1 through 20, the CLI path is not an absolute regular file, the project identifier is invalid, or the Profile is not `PILOT`. stdout is parsed only in a byte-bounded memory buffer using the fixed `U+241F` separator. Every line must have exactly five columns, line count cannot exceed the configured limit, and fields cannot contain control characters. stderr is converted only to a fixed diagnostic code and digest; its original text is not retained. `PT15S` remains the conservative default; an authorized Pilot host may raise it through repository-external configuration to no more than `PT60S`. A timeout still fails directly and does not trigger an implicit retry.

CI runs only the synthetic Fixture Contract Suite. Real Smoke is manually triggered and emits only execution time, Adapter/Mapping Version, query limit, returned count, redacted schema digest, Sync Run ID, and fixed result code. The complete command, titles, people, Server URL, local paths, raw output, and credentials must not enter Git, logs, CI Artifacts, or acceptance records.

## 5. Impact on future V0.3

V0.3 can add a Jira REST Adapter, a corporate internal Issue Adapter, or another approved transport under the same `IssueSourcePort` and Contract Suite. Migration must not change stored Normalized Issue Revisions, Release Issue Snapshots, or Traceability Snapshots and must not rewrite historical digests.

Before expanding to all Issues, cross-project queries, or automatic scheduled synchronization, a separate review must cover pagination, throttling, capacity, field minimization, data classification, retention, deletion, service identity, and Company operational responsibility. That review may replace the Adapter technology but cannot change Core or Snapshot/Traceability semantics.

## 6. How to migrate

To migrate from a Fixture-only development environment to Pilot, the Operator installs and authenticates Jira CLI outside the repository, configures an absolute executable path and a single project, and explicitly enables Pilot. The shared Fixture Contract Suite runs first, followed by real Smoke over no more than 20 records. The two results are recorded separately; a PASS from one can never cover a failure from the other.

To migrate later to a Jira REST Adapter, first run the same Fixture Contract Suite against both old and new Adapters, then compare the Normalized Contract and mapping digest over the same bounded read-only sample. After alignment, switch Source configuration using a new Adapter Version. Do not overwrite historical Sync Runs, Revisions, Snapshots, Audit, or Cursors. Rollback switches to the former Adapter Version and creates a new Sync Run.

## 7. How to test

Contract Tests verify standard fields, unknown-status mapping, stable ordering, terminal marker, source watermark, mapping version, and same-version idempotency for every Adapter. Fixture tests cover multiple pages, duplicate pages, interrupted pagination, 429 `Retry-After`, bounded 5xx retry, 401/403, timeout, invalid column count, invalid encoding, oversized output, tombstones, non-advancement of Cursor after failure, single-argument delimiter binding, and deterministic normalization of Jira CLI offset timestamps to UTC `Instant`.

Security tests verify that API callers cannot inject command arguments, fields outside the allowlist and `--raw` are rejected, and logs and Problem Details contain no command, stdout/stderr, Issue title, Server URL, path, or credential. PostgreSQL Integration Tests verify transaction boundaries and failure recovery for Sync Run, page checkpoint, Revision, and successful Cursor.

Real Smoke runs manually only on an Owner-authorized Pilot host, reads at most 20 records, and produces a redacted summary. It must verify exactly five columns, the boundary limit, successful mapping, and Sync Run status. It must not perform create, update, transition, comment, assign, or attachment operations. CI requires no Jira credential, and real Jira unavailability cannot be reported as a Smoke PASS.

## 8. How to deploy

No service, port, Broker, database, or container requirement is added. The Jira CLI Adapter ships with the existing Backend and is disabled by default. The Pilot Operator manages CLI installation, authentication, single-project configuration, and permission to run manual Smoke. The application only invokes the configured executable and does not read its credential store.

The Adapter remains unavailable by default in the Company Profile. It can be enabled at Company scale only after Company environment, identity, network, data-governance, and operations acceptance through a new governance decision. Current Pilot results must not be promoted into a Company Ready claim.

## 9. How to recover from failure

When the CLI is missing, unauthenticated, times out, exits non-zero, exceeds output bounds, fails parsing, or fails mapping, the current Sync Run becomes `FAILED` and retains fixed diagnostics, counts, and Audit, but no raw Payload and no advancement of the successful Cursor. After repairing external configuration or service availability, create a new Sync Run from the last successful Cursor. Do not alter failed history or old Snapshots.

If sensitive fields, raw Jira data, or credentials may have leaked, immediately disable `VSRQG_JIRA_PILOT_ENABLED`, stop new real Syncs, isolate affected Artifacts/logs, and use the external security process to revoke and replace credentials. Before allowing Pilot reads again, run the Fixture Contract Suite and a bounded security Smoke. Trusted historical Snapshots remain; audit records must not be deleted to conceal the incident.

## Re-evaluation triggers

Re-evaluate when query scope exceeds one project or 20 records, automatic scheduling is required, additional fields must be read, Jira CLI output or behavior changes incompatibly, the Company environment has an approved REST/API identity, or an internal Issue API Contract is approved. Re-evaluation must not silently change the frozen V0.1 architecture, Snapshot immutability, or Traceability semantics.
