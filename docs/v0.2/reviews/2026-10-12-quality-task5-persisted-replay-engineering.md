# Task 5 Persisted-Input Cross-JVM Replay Engineering Record

- Date: 2026-10-12; scope: isolated engineering evidence, not Owner or milestone acceptance.
- Basis: the [Task 5 implementation plan](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md), [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md), and the [earlier engineering record](2026-10-08-quality-task5-engineering.md).

## Verification Scope

`QualityEvaluationRecoveryIntegrationTest` stores two Evaluations with the same fixed input in temporary PostgreSQL. Their request IDs and creation times differ, as do their generated Input Snapshot IDs. The test asserts identical `inputDigest` and final `resultDigest` values, and checks that both sets of distinct request metadata were persisted. It then restores an independent database with `pg_dump`/`pg_restore`, copies the controlled Evidence Payload to an independent directory, reads the persisted Input Snapshot and rule AST from the restored database, and replays them in three fresh JVMs. Each replay digest is compared with the original final result digest.

Evidence integrity in the restored copy is initially `VERIFIED`. After deleting that copy's Payload, the current integrity check returns `INTEGRITY_ERROR`, while replay of the historical fixed input still produces the original digest. The test asserts these conclusions separately. The existing `QualityReplayTest` rejects an unknown historical Engine version.

This test constructs a synthetic Input Snapshot and persists it through the repository; it mocks the formal source reader. It proves persistence, metadata isolation, and cross-process replay, but does not prove a real Provider, device, or complete formal source selection. Random fixture data changes the literal result digest; CI compares exact values within each run rather than publishing a fixed literal digest. Multi-Attempt history and other unchecked Task 5 items still need separate review.

## Verification

Local Kotlin test compilation succeeded; the targeted recovery test stopped during Testcontainers initialization because Docker is unavailable locally. The earlier local `QualityReplayTest` passed all four cases, including three fresh JVMs and unknown Engine rejection. The fixed-commit M1 CI runs below are the evidence for database restoration and the new assertions; the M1 `Run M1 candidate gate` step runs `clean test bootJar`.

| Implementation commit | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| Chinese `3abd0c1` | [38230285276](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38230285276) | [38230285272](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38230285272) | [38230285363](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38230285363) |
| English `fed761d` | [38229776816](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38229776816) | [38229776795](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38229776795) | [38229776843](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/38229776843) |

All six fixed-commit CI runs above succeeded. TDR-026 remains Proposed / REVIEW_REQUIRED. No actual rule was published; no real Provider or device was run; no merge, Tag, or acceptance status change was made.
