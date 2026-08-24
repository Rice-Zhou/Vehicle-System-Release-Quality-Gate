# V0.2 Implementation Architecture Review Report

- Review ID: `V0.2-AR-2026-08-23-01`
- Review Date: 2026-08-23
- Chinese Baseline: `main@65c869b258c444fb3e43784dc3d87e7f18384ede`
- English Baseline: `release@14b59a2909180bd1bbdcead59699258446ba6ce0`
- Technical Review Status: `CHANGES_REQUIRED`
- Design Freeze Eligibility: `BLOCKED`
- V0.1 ADR Required: `NO`
- Owner Approval: `BOUNDARY_DECISIONS_ACCEPTED; FINAL_REVIEW_PENDING`
- Owner Decision Date: 2026-08-24

## 1. Review Goal and Boundary

This review determines whether V0.2 sufficiently answers how to engineer the system without changing V0.1 and whether one primary developer can produce a company-trustworthy Pilot MVP in about six months of spare time.

The review does not approve V0.2 Design Freeze, replace the Owner's final sign-off on WHY, WHAT, BOUNDARY, and ACCEPTANCE, or write production code.

## 2. Executive Conclusion

V0.2 preserves Release-centric, Manifest authoritative, Evidence first-class, Traceability, Deterministic Quality Engine, Adapter, Plugin, AI advisory, and ADR governance. No finding requires changing the V0.1 Core Contract.

Modular Monolith, Kotlin/Spring Boot, PostgreSQL, S3-compatible storage, REST/OpenAPI, Agent Pull, PostgreSQL Outbox, Restricted YAML AST, OIDC, and Containerized VM match current requirements, scale, and the six-month constraint. The technical recommendation is `RECOMMEND_ACCEPT`.

AR-01 through AR-09 now have design revisions or machine-executable contracts and have completed the corresponding design validation. Only AR-10 remains, requiring Tag/TDR/Review state alignment during final Architecture Review. The overall decision therefore remains `CHANGES_REQUIRED`, and no Design Freeze tag may be created.

## 3. Review Evidence

- All 14 V0.2 topic documents and 10 TDRs exist, with every required TDR section present.
- Frozen V0.1 concept anchors are present, with no Schema or Core Contract change.
- Automated bilingual verification passes for branch paths, non-Markdown blobs, heading structure, Inline Tokens, local links, and code fences.
- Verifier regression scenarios pass 6/6.
- Sampling covered bilingual key states, error semantics, Fixed/Included/Verified, PK/FK, Timeout/Retry/Recovery, and TDR alternatives.
- OpenAPI 3.1, Agent Protocol Schema, Quality Rule Schema, Fact Catalog, V0.2 Manifest Schema, positive/negative examples, and pinned validation tooling have been added. Contract Tests validate four Schema classes, 12 positive cases, five negative cases, and 28 API Operations, while protecting the V0.1 Manifest Schema hash.

## 4. Review Gate

| Gate | Result | Explanation |
|---|---|---|
| Frozen V0.1 architecture consistency | PASS | Core Contract and authority relationships are not redefined |
| Bilingual structure and terminology | PASS | Remote Pair verifier passes |
| Technology-choice rationale | PASS WITH CONDITIONS | All 10 TDRs are recommended for acceptance subject to Section 6 |
| Direct Database/ER implementability | PASS (DESIGN) | AR-02 through AR-04 define migration-ready constraints and a Complete Table Catalog; Integration Tests run in M1/M2 |
| Direct Deterministic Rule implementability | PASS (DESIGN) | AR-05 defines a per-operator Matrix and ERROR propagation; Golden/Matrix Tests run in M4 |
| Direct Test/Agent Protocol implementability | PASS (DESIGN) | AR-06/AR-07 align state machine, terminal behavior, and Versioned Paths; Contract Tests run in M3 |
| External Contract completeness | PASS (DESIGN) | AR-01 delivered and validated OpenAPI and Agent/Rule/Fact/Manifest Contracts; implementation Contract Tests run by module in M1–M4 |
| Six-month MVP scope | PASS | Owner accepted the OD-01/OD-02 scope, capacity baseline, and Cut Line |
| Operational recovery objectives | PASS WITH IT VALIDATION | Owner accepted OD-03; the company environment must still validate it or record alternative objectives and risk |
| Design Freeze | BLOCKED | AR-10 must close, final Review must run, and the Owner must approve |

## 5. Design Findings That Must Close

### AR-01 — External Contract Artifacts Are Missing

- Severity: `BLOCKER`
- Resolution Status: `DESIGN_RESOLVED 2026-08-24`
- Evidence: M0 in [14-mvp-implementation-plan.md](../14-mvp-implementation-plan.md) requires OpenAPI/Schema Drafts. Acceptance evidence in [03-api-design.md](../03-api-design.md), [08-test-agent-protocol.md](../08-test-agent-protocol.md), and [11-quality-rule-specification.md](../11-quality-rule-specification.md) depends on machine-verifiable contracts, but the repository has no corresponding files.
- Risk: Backend, Agent, CI, and Rule Engine can implement mutually incompatible contracts, and document review cannot prevent field or error-semantic drift.
- Required Resolution:
  1. Add an OpenAPI 3.1 Draft.
  2. Add Agent Protocol Payload Schemas.
  3. Add a Quality Rule JSON Schema.
  4. Add a Versioned Fact Catalog.
  5. Add a V0.2 Manifest Schema while preserving the V0.1 Schema.
  6. Validate links, Schemas, and Breaking Diffs in CI or locally.
- Resolution: added [`contracts/openapi/v0.2/openapi.json`](../../../contracts/openapi/v0.2/openapi.json), Agent/Rule/Fact/Manifest JSON Schemas, a Versioned Fact Catalog, positive/negative examples, an OpenAPI Compatibility Baseline, and pinned validation tooling. Tests compare OpenAPI against the exact Method/Path sets in both Endpoint tables, and a fixed SHA-256 prevents overwriting the V0.1 Manifest Schema.
- Closure Evidence: every example passes Schema Validation, and OpenAPI/Protocol/Rule/Manifest Contract Tests pass.
- Verification Evidence: on 2026-08-24, `scripts/tests/verify-contracts.tests.ps1` produced `PASS contracts schemas=4 positive=12 negative=5 operations=28`, `PASS frozen-v0.1-manifest`, and `PASS contract artifact tests`.
- Implementation Evidence Gate: M1–M4 must run producer/consumer Contract Tests against the actual Backend, Agent, Manifest Validator, and Rule Engine respectively. Closing this finding does not constitute production implementation acceptance.

### AR-02 — Edge Model Does Not Implement Historical Traceability Snapshot Immutability

- Severity: `BLOCKER`
- Resolution Status: `DESIGN_RESOLVED 2026-08-24`
- Evidence: [06-traceability-design.md](../06-traceability-design.md) freezes Edge ID+version in a Snapshot, while the common Edge columns in [02-database-design.md](../02-database-design.md) define no `version`, immutable Revision, or Snapshot Materialization constraint.
- Risk: an in-place Verification Status or Confidence update can make a historical Quality Result read different traceability facts, breaking Deterministic Replay.
- Required Resolution: use append-only Edge Revisions or materialize complete Edge Facts in the Snapshot. A Snapshot must never reference only a mutable row.
- Resolution: three external provenance Edge types now use append-only Revisions; Artifact→Release derives only from the Locked Manifest; Snapshot Edges/Gaps materialize complete Facts, and replay cannot read the latest Revision. See Sections 6 and 11 of [02-database-design.md](../02-database-design.md) and Sections 2, 7, and 10 of [06-traceability-design.md](../06-traceability-design.md).
- Closure Evidence: after updating an Edge, replaying an old Snapshot preserves Path, Confidence, Verification Status, and digest.
- Implementation Evidence Gate: M2 must produce a real-PostgreSQL Edge Revision Integration Test and Snapshot Replay digest report. Implementation acceptance cannot pass before that evidence exists.

### AR-03 — Database Has Parallel Relationships and a Non-Executable Cross-Table CHECK

- Severity: `BLOCKER`
- Resolution Status: `DESIGN_RESOLVED 2026-08-24`
- Evidence: [02-database-design.md](../02-database-design.md) defines both `artifact.build_id` and `build_artifact_edge`. It also claims a Database CHECK ensures that an Evidence Test Result belongs to the same Test Run, although PostgreSQL CHECK cannot query other rows. It defines `normalized_issue.source_version` as bigint while [05-issue-adapter-design.md](../05-issue-adapter-design.md) permits an ETag/external Version identifier.
- Risk: Build→Artifact gains two Sources of Truth. Evidence can reference the wrong Release/Run/Result, or implementations can choose different Trigger/Application logic.
- Required Resolution:
  1. Retain only `build_artifact_edge` for Build→Artifact.
  2. Derive Artifact→Release only from Locked Manifest, never as a second Release-content entry.
  3. Use Composite FKs or an explicitly defined Deferred Constraint Trigger to keep Evidence, Test Result, Test Run, and Release consistent.
  4. Define Source Version as an opaque string or specify a lossless mapping from every Adapter to one comparable type.
  5. Add Constraint Integration Tests against real PostgreSQL.
- Resolution: the design removes `artifact.build_id`; Build→Artifact uses only Edge Revision; Artifact→Release is a read-only Locked Manifest derivation; Evidence uses Run/Release and Result/Run Composite FKs; `source_version` is an opaque string. See Sections 4, 5, 7, and 11 of [02-database-design.md](../02-database-design.md) and Section 5 of [05-issue-adapter-design.md](../05-issue-adapter-design.md).
- Closure Evidence: the database rejects an illegal cross-Run/Release Evidence write and no duplicate relationship is ambiguous.
- Implementation Evidence Gate: M1/M2 must run the Constraint Integration Test against real PostgreSQL; H2/Mock results are not substitutes.

### AR-04 — The “Complete ER Diagram” Omits Persistent Entities

- Severity: `BLOCKER`
- Resolution Status: `DESIGN_RESOLVED 2026-08-24`
- Evidence: the ER diagram in [02-database-design.md](../02-database-design.md) omits complete PK/FK/Cardinality for Device, Agent, Environment Snapshot, Audit Event, Outbox/Job, Idempotency Record, Governance Decision, and Quality Input Snapshot.
- Risk: implementation still has to redesign critical tables, so the Implementation Architecture cannot serve as the database acceptance baseline.
- Required Resolution: label the current diagram Core ER Overview and add Domain-specific complete ER diagrams plus Table Catalog, PK/FK, Unique, Delete/Retention, and Cardinality.
- Resolution: Section 3 of [02-database-design.md](../02-database-design.md) now provides the Core Overview and three Domain ERs. Sections 4 through 8 provide a Complete Table Catalog covering Device, Agent, Environment Snapshot, Audit, Outbox/Job, Idempotency, Governance Decision, and Quality Input Snapshot.
- Closure Evidence: every persistent Entity in the database model maps to a reviewable table definition and relationship.
- Implementation Evidence Gate: the M1 Migration Review compares Schema Export against the Table Catalog; an unregistered ORM Entity blocks merge.

### AR-05 — Rule Missing/Empty/Null Semantics Are Incomplete

- Severity: `BLOCKER`
- Resolution Status: `DESIGN_RESOLVED 2026-08-24`
- Evidence: [11-quality-rule-specification.md](../11-quality-rule-specification.md) says a Missing Path is not false but does not define Missing/Empty/Null results per operator for `eq`, `ne`, comparisons, `exists`, `count`, `all`, `any`, `consecutive`, and Boolean composition.
- Risk: identical Snapshots can produce PASS, false, 0, or ERROR in different Engine implementations, directly violating determinism.
- Required Resolution: define a three-value/error propagation table, Empty Collection behavior, Null comparison, unit conversion, and numeric precision. Implementations must not choose defaults.
- Resolution: Section 5 of [11-quality-rule-specification.md](../11-quality-rule-specification.md) defines Missing/Empty/Null/Type Error, a per-operator Matrix, ERROR-precedence propagation, appliesWhen, fixed-point decimal, and canonical unit. [10-quality-engine-design.md](../10-quality-engine-design.md) makes it the sole evaluation specification.
- Closure Evidence: every operator has value/empty/missing/null/type-error Golden Tests and repeated execution produces the same digest.
- Implementation Evidence Gate: M4 must provide the full Operator Matrix, operand-order permutations, and three replay digests; Engine acceptance fails if any is absent.

### AR-06 — Test/Attempt States Conflict With Run Completion

- Severity: `BLOCKER`
- Resolution Status: `DESIGN_RESOLVED 2026-08-24`
- Evidence: the Attempt State List in [07-test-architecture.md](../07-test-architecture.md) omits `RECOVERY_PENDING`, while the power-loss flow uses it. Run completes when “all required cases terminal” without defining how a still-running optional Attempt terminates.
- Risk: power recovery and Run Completion can produce illegal transitions, late Results, or changing Evaluation inputs.
- Required Resolution: add `RECOVERY_PENDING` to the Attempt State Machine. Run Completion must require every scheduled Attempt to be terminal, or explicitly cancel optional Attempts and record a Result. Define late Event/Result behavior.
- Resolution: [07-test-architecture.md](../07-test-architecture.md) adds RECOVERY_PENDING to the Attempt State Machine, requires a terminal Resolution for every Plan Case and exactly one Result for every terminal Attempt, and rejects late/stale writes. [08-test-agent-protocol.md](../08-test-agent-protocol.md) defines same-digest idempotency and conflict quarantine.
- Closure Evidence: State Contract Tests pass for power loss, recovery-window expiry, optional Cases, and late Results.
- Implementation Evidence Gate: M3 rehearses those four scenarios with a real Agent/Device and proves terminal Run input digest is unchanged.

### AR-07 — Agent Endpoint Forms Are Inconsistent

- Severity: `MAJOR`
- Resolution Status: `DESIGN_RESOLVED 2026-08-24`
- Evidence: only the registration Endpoint in [08-test-agent-protocol.md](../08-test-agent-protocol.md) includes `/agent-api/v1`; other Endpoints begin with `/agents`, `/commands`, or `/attempts`.
- Risk: Server and Agent can generate different URLs, and OpenAPI cannot establish a unique Base Path rule.
- Required Resolution: use complete Versioned Paths in every table row, or explicitly state that every row is relative to `/agent-api/v1` and apply that form consistently.
- Resolution: every Endpoint in [08-test-agent-protocol.md](../08-test-agent-protocol.md) now uses a complete `/agent-api/v1` Path and prohibits double-prefixing or unversioned aliases.
- Closure Evidence: Agent OpenAPI/Protocol Contract Tests use one URL set.
- Implementation Evidence Gate: the OpenAPI delivered by AR-01 must exactly match the URL set in the table.

### AR-08 — Manifest Canonicalization and V0.2 Schema Semantics Are Not Frozen

- Severity: `MAJOR`
- Resolution Status: `DESIGN_RESOLVED 2026-08-24`
- Evidence: [04-release-manifest-design.md](../04-release-manifest-design.md) says only “stable field ordering and encoding.” In the existing V0.1 Schema, Artifact `required` may be absent and identity fields required by the design are incomplete.
- Risk: JSON serializers can produce different digests, and absent `required` can mean true, false, or invalid.
- Required Resolution: specify the JSON Canonicalization standard, UTF-8, and exact SHA-256 input bytes. Create a new V0.2 Manifest Schema with explicit absent-`required` semantics without modifying the V0.1 Schema.
- Resolution: [04-release-manifest-design.md](../04-release-manifest-design.md) specifies a separate V0.2 Schema, mandatory `required`, RFC 8785 JCS, UTF-8 without BOM, SHA-256 digest form, NFC/numeric restrictions, and cross-implementation Fixtures. The V0.1 Schema remains unchanged.
- Closure Evidence: cross-implementation Canonicalization Fixtures produce the same digest, and V0.1/V0.2 Schema Compatibility Tests pass.
- Implementation Evidence Gate: AR-01 creates the V0.2 Schema. M1 validates canonical bytes/digest with the JVM implementation and an independent implementation.

### AR-09 — Sensitive Evidence Download Acceptance Exceeds Presigned URL Capability

- Severity: `MAJOR`
- Resolution Status: `DESIGN_RESOLVED 2026-08-24`
- Evidence: [12-authentication-design.md](../12-authentication-design.md) requires a sensitive download URL to be non-reusable across users. A standard S3 Presigned URL is normally a Bearer URL until expiration and cannot bind to an application user.
- Risk: the selected technology cannot prove the acceptance condition; URL leakage can bypass application authorization.
- Required Resolution: ordinary Evidence may use short-lived Presigned URLs. Sensitive Evidence uses a Backend Proxy/controlled Gateway that authorizes every request, or acceptance is changed to a technically provable Bearer URL control.
- Resolution: [09-evidence-design.md](../09-evidence-design.md), [12-authentication-design.md](../12-authentication-design.md), and [03-api-design.md](../03-api-design.md) split GENERAL/RESTRICTED from HIGH. HIGH uses only a per-request authenticated Proxy/Gateway, prohibits Presigned URL/redirect, and requires no-store, Audit, and log-leak controls.
- Closure Evidence: cross-user sensitive downloads fail and URLs never enter Log/Audit Payload.
- Implementation Evidence Gate: the M3 security test proves User B receives 403 when reusing User A's path, and Log/Audit Payload contains no object URL/token.

### AR-10 — Bilingual Tag and Review-State Governance Conflict

- Severity: `MAJOR`
- Evidence: [14-mvp-implementation-plan.md](../14-mvp-implementation-plan.md) uses one `v0.2.0-design` tag, while [language-policy.md](../../language-policy.md) requires paired `v0.2.0-design-zh` / `v0.2.0-design-en` tags. All 10 TDRs remain `Proposed for V0.2 Review`.
- Risk: Design Freeze cannot prove paired Chinese and English commits, and TDR acceptance remains ambiguous.
- Required Resolution: use paired Annotated Tags consistently. Change TDR status to Accepted and record the Review ID only after Architecture Review approval.
- Closure Evidence: Tag Messages cross-reference each other, and TDR status matches the Review Report.

## 6. TDR Technical Recommendations

| TDR | Recommendation | Condition |
|---|---|---|
| TDR-001 Modular Monolith | `RECOMMEND_ACCEPT` | Keep module-dependency tests and a single data owner |
| TDR-002 Kotlin/Spring Boot | `RECOMMEND_ACCEPT` | Record the concrete LTS JDK and support lifecycle during implementation |
| TDR-003 PostgreSQL | `RECOMMEND_ACCEPT` | AR-02 through AR-04 are design-resolved; run real-PostgreSQL acceptance in M1/M2 |
| TDR-004 S3-compatible Storage | `RECOMMEND_ACCEPT` | AR-09 is design-resolved; M3 runs Proxy cross-user and Inventory Reconciliation tests |
| TDR-005 REST/OpenAPI | `RECOMMEND_ACCEPT` | AR-01 OpenAPI Draft is delivered; retain Compatibility Checks during implementation |
| TDR-006 Agent Pull | `RECOMMEND_ACCEPT` | AR-06/AR-07 are design-resolved; M3 runs State/Protocol Contract Tests |
| TDR-007 PostgreSQL Outbox | `RECOMMEND_ACCEPT` | Retain bounded retries, Dead Letter, and idempotency tests |
| TDR-008 Restricted YAML AST | `RECOMMEND_ACCEPT` | AR-05/AR-01 delivered Rule semantics and Schema; M4 runs Matrix Tests |
| TDR-009 OIDC/Service Identity | `RECOMMEND_ACCEPT` | Confirm company IdP, Secret Manager, and Break-glass process |
| TDR-010 Containerized VM | `RECOMMEND_ACCEPT` | Owner/IT confirms RPO/RTO and target platform |

This recommendation does not change TDR status. Proposed becomes Accepted only after the Owner approves Architecture Review.

## 7. Owner Boundary / Acceptance Decision Record

On 2026-08-24, the Project Owner explicitly accepted the recommendations for OD-01 through OD-04. This record approves the following Boundary and Acceptance decisions; it does not approve remaining unresolved Review Findings and does not authorize Design Freeze tags.

### OD-01 — Does Memory Enter the Six-Month MVP?

- Conflict: [roadmap.md](../../roadmap.md) puts Memory/CPU/FPS in Phase 2, while V0.2 includes basic Memory in Domain, Test, Evidence, and M3.
- Recommendation: keep Crash, ANR, Log, and Screenshot mandatory in MVP. Retain the Memory Interface, Fact, and Rule Example, but make the real Memory Collector a Stretch Goal entered in M3 only when M1/M2 are on schedule and the real bench is stable.
- Decision: `ACCEPTED`

### OD-02 — Spare-Time Capacity Baseline and Cut Line

- Gap: the plan has 24 weeks but no weekly capacity or scope-cut trigger, so the six-month commitment cannot be assessed.
- Recommendation: plan for 10–12 hours per week with 20% Contingency. If a critical milestone slips more than two weeks, first remove UI, trend analytics, Memory Stretch, automated external Issue updates, and non-essential reports. Do not weaken Manifest, Evidence, Traceability, Deterministic Quality, Auth/Audit, or recovery.
- Decision: `ACCEPTED`

### OD-03 — Pilot RPO/RTO

- Gap: [13-deployment-design.md](../13-deployment-design.md) requires measurement but gives no acceptance target.
- Recommendation: set initial Pilot targets to `RPO ≤ 1 hour` and `RTO ≤ 4 hours`. If company infrastructure cannot meet them, Owner and IT record alternative values and risk acceptance.
- Decision: `ACCEPTED`; IT environment validation remains a pre-deployment condition.

### OD-04 — Two-Person Principle for High-Risk Operations

- Gap: Rule Publish and BLOCK Override currently allow a process-only compensation for single-person action.
- Recommendation: Pilot may use an external approval record, but before use in a real company project, Production Rule Publish and BLOCK Override require two-person approval or an equivalent company approval control.
- Decision: `ACCEPTED`

## 8. Six-Month Feasibility

V0.2 is feasible as a Pilot MVP with a Modular Monolith, one PostgreSQL, one Object Storage, one real bench, sequential execution, fixed RBAC, restricted Rules, and no complex UI.

If it simultaneously requires production-grade dual Adapters, a Memory Collector, full UI, automated approvals, complex reports, and company-grade high availability, six months of one primary developer's spare time carries unacceptable risk. OD-01 and OD-02 are necessary scheduling inputs.

## 9. Closure Order

1. Owner confirms OD-01 through OD-04. `COMPLETED 2026-08-24`
2. Revise Database/ER and Traceability invariants to close AR-02, AR-03, and AR-04. `DESIGN_COMPLETED 2026-08-24`
3. Revise Rule, Manifest, Test/Agent, and Evidence Security to close AR-05 through AR-09. `DESIGN_COMPLETED 2026-08-24`
4. Deliver and validate machine-executable Contract Artifacts to close AR-01. `DESIGN_COMPLETED 2026-08-24`
5. Align Tag/TDR/Review states to close AR-10.
6. Re-run bilingual Pair Verification, Contract Tests, and Architecture Review.
7. Create paired Design Freeze Tags only after explicit Owner approval.

## 10. Owner Sign-Off

```text
Review Decision: PENDING (all remaining Review Findings must close first)
OD-01 Memory Scope: ACCEPTED
OD-02 Capacity Baseline: ACCEPTED
OD-03 RPO/RTO: ACCEPTED; IT validation pending before deployment
OD-04 Two-Person Approval: ACCEPTED
Accepted Residual Risks:
Owner: Project Owner
Date: 2026-08-24
```

Current final sign-off status is `PENDING`. V0.2 remains `0.2.0-draft.2` until sign-off.
