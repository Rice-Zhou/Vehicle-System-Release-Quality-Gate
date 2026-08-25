# M1 Acceptance Checklist

The Project Owner uses this checklist to accept a candidate and does not edit test results directly. Every item must map to a machine gate and CI artifact. Missing evidence is `UNKNOWN`, not a pass.

| Milestone | Acceptance item | Gate / Evidence | Owner check |
|---|---|---|---|
| M1.0 | JDK, Kotlin, Spring Boot, Gradle, PostgreSQL, and CI versions are pinned | `dependencies`, `build-test-security-concurrency`; `evidence.json` | Versions are reproducible and production dependencies do not float |
| M1.1 | Flyway creates the authoritative schema from an empty database; FK, uniqueness, append-only, and Locked immutability constraints work | Full backend test, `schema-export`; schema and migration test reports | Schema matches the V0.2 data design |
| M1.2 | OIDC issuer/audience, project RBAC, hidden resources, and Audit/Outbox/Idempotency atomicity | Security, idempotency, and rollback tests | Unauthorized access fails without leaving business writes |
| M1.3 | V0.1 hash is unchanged; V0.2 schema, JCS, and JVM/Node digests agree | `contract`, Manifest Contract test | Frozen assets are unchanged and the canonical digest is reproducible |
| M1.4 | Manifest Revision, Artifact, Validation, concurrent Lock, ETag, and immutable Export | Manifest registration and Lock tests | Only trusted `VALID` can Lock and exactly one concurrent request succeeds |
| M1.5 | Full API path, PostgreSQL 17.11 dump/restore, and unchanged digest/Audit after restore | `smoke-recovery`, `schema-export`, smoke report | Source and restored databases export exactly the same digest |

## Checks Before the Owner Decision

- [ ] The candidate commit exactly matches `evidence.json.commit`.
- [ ] `evidence.json.status` reflects actual gate results and `ownerDecision` remains `PENDING`.
- [ ] Every gate command, start/end time, and exit code is recorded.
- [ ] The report index contains actual SHA-256 values and no credentials or tokens.
- [ ] The smoke report explicitly marks `m1-acceptance-validator/1` as a fixture.
- [ ] The production validator, OIDC, backup retention, and operational owners are established; unresolved items are recorded as residual risks.
- [ ] Non-Markdown files on the Chinese and English candidate branches are byte-identical.
- [ ] Neither `main` nor `release` is merged automatically, and no M1 tag is created early.

## Decision

The Owner records `APPROVE`, `REJECT`, or `CONDITIONAL` outside the repository or in an approval record, referencing the candidate commit and CI artifact. Static documents and candidate `evidence.json` do not prefill an approval result.
