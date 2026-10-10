# Task 6 Export Contract: First Engineering Segment

- Date: 2026-10-10; scope: schema, read-only input validation, and positive/negative tests. This is neither Task 6 completion nor Owner acceptance.
- Basis: the [implementation plan](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md), [technical review](2026-10-10-quality-task6-report-technical-review.md), and the proposed TDR-026/TDR-023 additions.

## Implemented boundary

`quality-report-export.schema.json` accepts only terminal `COMPLETED` or `ERROR` Evaluations and optional formal Traceability/Test query responses. An `ERROR` before input pinning cannot include source responses, preserves `NOT_EVALUATED`, and does not invent a Quality Result. Top-level and source fields use the existing Quality Schema/OpenAPI; `quality-report-export.mjs` also checks the exact Evaluation ID, Release/Project, Snapshot/Manifest/Issue Snapshot references, and selected Run/Case/Attempt/Result digest and status. No second source supplies the Issue Snapshot digest, so it remains only in the pinned input.

The independent export byte limit is 16 MiB: exactly the limit is accepted and one extra byte is rejected. This is the controlled budget for the current 4 MiB quality input and at most 2000 traceability edges; a retained CI response must still verify the margin. Provenance supports only `UNKNOWN` or `SYNTHETIC_FIXTURE` with a controlled fixture ID. This validator cannot prove the external origin of a fixture ID; the later exporter must constrain that source. This segment adds no report CLI, HTML, API calls, rule evaluation, or real demonstration.

## Verification and remaining work

Positive cases cover PASS/WARNING/BLOCK and ERROR before/after pinning. Negative cases cover unknown and credential fields, queued state, source and selection conflicts, invalid UTF-8, and byte boundaries. `node scripts/contract-validator.mjs` and `node --test scripts/tests/demo-report.test.mjs` pass. Samples are constructed from existing machine contracts, not a complete retained Task 5 CI export. They therefore do not establish actual source collection, exact-ID pagination, HTML safety, Evidence navigation, or A1–A8 acceptance. The next segment must retain controlled CI responses before implementing exact-ID export and report projection.
