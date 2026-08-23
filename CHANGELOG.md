# Changelog

This file records reviewable and traceable VSRQG architecture versions. Only changes with a clear purpose that can be reviewed independently form a commit or version tag.

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
