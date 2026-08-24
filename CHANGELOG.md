# Changelog

This file records reviewable and traceable VSRQG architecture versions. Only changes with a clear purpose that can be reviewed independently form a commit or version tag.

## 0.2.0-draft.2 — Architecture Review Revision Draft — 2026-08-24

- Records Owner approval of OD-01 through OD-04, freezing the Memory Stretch, capacity/Cut Line, Pilot RPO/RTO, and two-person approval boundaries.
- Changes Traceability Edges to append-only Revisions and materializes complete Edge Facts in Snapshots, design-closing AR-02.
- Removes the parallel Build→Artifact source and defines Locked Manifest derivation, Composite FKs, and opaque `source_version`, design-closing AR-03.
- Adds the Core ER Overview, three Domain ERs, and Complete Table Catalog, design-closing AR-04.
- Freezes the Rule Missing/Empty/Null/type-error Matrix and ERROR propagation, design-closing AR-05.
- Completes RECOVERY_PENDING, Run Completion, late Event/Result behavior, and full Agent Versioned Paths, design-closing AR-06/AR-07.
- Specifies V0.2 Manifest RFC 8785 JCS, mandatory `required`, and cross-implementation digest rules, design-closing AR-08.
- Changes HIGH Evidence download to a per-request authenticated Proxy/Gateway, design-closing AR-09.
- Adds machine-executable OpenAPI 3.1, Agent Protocol, Quality Rule, Fact Catalog, and V0.2 Manifest contracts, positive/negative examples, and compatibility checks, design-closing AR-01.
- Aligns paired Design Freeze Tags, TDR status transitions, and Review status; adds the final Owner acceptance checklist and governance validation, moving AR-10 to awaiting Owner approval.
- All implementation acceptance still runs at the corresponding M1–M4 Gates. This version remains Draft and creates no Design Freeze tag.

## 0.2.0-draft.1 — Implementation Architecture Review Draft — 2026-08-21

- Added an index and 14 V0.2 implementation architecture and technology decision documents.
- Refined Domain, Database, API, Manifest, Adapter, Traceability, Test, Agent, Evidence, Quality, Authentication, Deployment, and the MVP acceptance plan.
- Added 10 TDRs covering Modular Monolith, Kotlin/Spring Boot, PostgreSQL, S3, REST/OpenAPI, Agent Pull, PostgreSQL Outbox, YAML Rule, OIDC, and containerized VM deployment.
- Defined Technology Decision Delegation, three architecture red lines, the six-month spare-time implementation boundary, and GitHub version governance.
- Established paired Chinese `main` and English `release` documentation governance, automated verification, and semantic-review workflow.
- This version is a review draft. V0.2 Design Freeze has not been performed.

## 0.1.0 — Architecture Baseline — 2026-08-21

- Froze the Release-centric core architecture and Core Contract.
- Established the authority of the Release Manifest.
- Established Evidence, Traceability, Deterministic Quality Engine, Adapter, Plugin, and ADR governance.
- Provided the initial Release Manifest JSON Schema and V0.2 evolution boundary.

## Version Governance

- Frozen V0.1 architecture may be changed only through an approved ADR.
- V0.2 design retains Draft status until its review passes and must not be labeled Design Freeze.
- Each commit contains one explainable and reviewable logical change.
- Release tags point only to commits that have passed the corresponding review.
