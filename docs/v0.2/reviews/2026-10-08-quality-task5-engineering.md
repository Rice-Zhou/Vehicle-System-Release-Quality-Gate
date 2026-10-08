# Minimal Quality Evaluation Task 5: Engineering Record for Formal Inputs and Asynchronous Evaluation

Date: 2026-10-08. Sources: [implementation plan](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md), [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md), and [Task 4 engineering record](2026-09-30-quality-task4-engineering.md). This record reports engineering evidence and does not replace Owner acceptance.

## Implementation

V17 adds incremental tables and immutability constraints for Evaluation, Input Snapshot, Rule Result, and final Quality Result. `quality:evaluate` uses the project roles explicitly confirmed by the Owner: Engineer, Quality Owner, and Administrator, together with the JWT scope. `quality:read` covers project members. Requests accept only formal references to a Rule Set, Traceability Snapshot, and one Test Run. The published rule set's required Issues and selected Case, locked Manifest, fixed Issue/Traceability snapshots, terminal Test Run/Result, and Evidence module metadata and Payload verification form the pinned input. Clients cannot submit facts.

The Job reuses PostgreSQL claim, attempt count, lease, and fencing. After reading sources and verifying Evidence bytes, the Worker rereads sources and seals the input in a short transaction. Pure evaluation then writes Rule Results, Quality Result, Audit, Outbox, and the terminal Job state atomically. Source failure leaves a queryable ERROR without fabricating a final Result. Queries read persisted history; replay is determined by the fixed input and rule, catalog, engine, and encoder versions. Unknown engine versions are rejected. Scheduling is off by default and requires explicit enablement in a controlled environment. This work did not publish a real rule, enable Company resources, deploy, or operate a device.

The rule publication gate still accepts only the two built-in Task 4 demonstration YAML rules. The database recovery exercise uses isolated DB+Payload copies. The positive formal source reader test mocks the other modules' repositories and is not represented as real device end-to-end acceptance.

## Engineering verification

- Unit and contract checks cover formal source binding, project permissions, cross-Run Evidence rejection, Worker failure, replay of one fixed input in three fresh JVMs, and rejection of unknown engines. `node scripts/contract-validator.mjs` passed 7/7 tests with schemas=7, positive=22, negative=9, operations=36.
- PostgreSQL CI covers V17 migration and rollback recovery, idempotent duplicate requests, a unique final result, transaction rollback, reclaim and stale lease writes, and queryable ERROR. Recovery of independent DB+Payload copies separates the historical result digest from a current Payload corruption diagnostic. Docker is unavailable locally, so database claims rely on the exact CI commits.
- The core fix commits `8140fee072aa62200f5334650a73e26f4d44d2d8` (Chinese) / `01c5861d79726d9a106b1f32c8e74da1caf4b991` (English) passed all M1/M2/M3 runs. Formal source fact test commits `644b0ca7b16aa7d5b8e4a825bae4e5399647db5b` / `21f7d13fe4b41834b74bc361a51bed2bfe843ec6` also passed all M1/M2/M3 runs.

| Final test fixture correction | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| Chinese `c889cf0` | [37722834666](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722834666) | [37722834847](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722834847) | [37722834965](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722834965) |
| English `b016358` | [37722840061](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722840061) | [37722839968](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722839968) | [37722839961](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37722839961) |

All six exact-commit CI runs for the final fixture corrections succeeded. Pair Gate confirmed matching non-Markdown content.

## Open scope and execution order

The implementation plan's first item still needs an explicit formal source negative matrix for the same APK on another Release, empty selectedCaseRefs, multiple Runs, missing Result, absent required Issue, corrupted Evidence, and appliesWhen=false. The code contains corresponding guards, but isolated existing tests do not replace that matrix. A controlled demonstration environment must also exercise the formal APIs with a new Release, a published demonstration rule, a terminal Run, and Evidence, checking completed/error queries and source navigation. Until that happens, Task 5 as a whole must not be described as engineering accepted. Task 6 read-only reporting and device evidence remain separate work.

Next, complete the source negative matrix and review schema projections; then exercise the formal API in an isolated demonstration environment and verify exact CI commits. Follow with engineering review and separate Owner acceptance. TDR-026 remains Proposed / REVIEW_REQUIRED. This record changes neither real rule publication nor governance status.

Current result: Task 5 core implementation and several engineering checks are in place; the full source matrix and formal integration are open. Git status: bilingual implementation and test commits are pushed; this record and plan status update await paired commits and exact CI. Next action: finish the negative matrix, controlled integration, and review. Prerequisites: six successful CI runs for the latest fixture commits, plus separate authorization and an isolated environment for demonstration rule publication. Acceptance target: the Owner can independently judge Task 5 from fixed-input, failure, recovery, query, and presentation evidence; this record does not grant acceptance.
