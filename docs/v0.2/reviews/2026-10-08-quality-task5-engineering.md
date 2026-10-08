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

## Formal source matrix and API boundary review

The formal source negative cases now cover a cross-Release Run with the same Manifest digest, a locked Manifest with the wrong Release or digest, a Test Result bound to another Manifest, empty selectedCaseRefs, multiple Runs, missing Result, absent required Issue, and a missing Traceability reference. Existing tests still cover cross-project Traceability, cross-Run Evidence, and appliesWhen=false. A new test rejects a Payload digest mismatch after Evidence metadata has been pinned. The repository's 2020-12 JSON Schema validator checks an actual generated Input Snapshot. Targeted unit tests and Kotlin test compilation passed locally.

An isolated PostgreSQL fixture now checks formal HTTP POST → Evaluation QUEUED → GET history. The fixture inserts a rule version into its temporary test database; it does not call a real publication API. This test covers the API, authorization, project scope, and persisted query for the first half of the flow. It does not cover a complete HTTP sequence through formal sources and a Worker decision. Docker is unavailable locally, so the database result depends on exact-commit CI.

| Negative matrix and API fixture commits | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| Chinese `dc9700a` | [37739784435](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739784435) | [37739784342](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739784342) | [37739784331](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739784331) |
| English `4de90ac` | [37739790841](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739790841) | [37739790868](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739790868) | [37739790826](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37739790826) |

All six exact-commit CI runs for the negative matrix succeeded. Pair Gate confirmed matching non-Markdown test files.

## Isolated formal source and Worker sequence

A PostgreSQL integration fixture now starts with a formal HTTP POST, uses the real `FormalQualitySourceReader` to read isolated Manifest, Issue, Traceability, and terminal Run repository ports, then lets the real Worker pin the snapshot, evaluate a demonstration rule, and write the final result before HTTP GET reads it. The same test submits a missing Traceability reference and confirms a queryable ERROR without a Quality Result. The temporary database follows V16 constraints: it inserts the DRAFT rule set and rule first, then moves the fixture row to PUBLISHED. It does not call the rule publication API or alter real rules. Upstream repositories and the Evidence port are mocked, and the completed path has no Evidence bytes. This test proves the application sequence and persisted decision, not end-to-end behavior with actual sources, Payload, or a device. The demonstration rule deterministically returns BLOCK for this empty-Issue input.

| Isolated decision sequence commits | M1 Backend | M2 Backend | M3 Single Device Smoke |
| --- | --- | --- | --- |
| Chinese `925187c` | [37748672488](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37748672488) | [37748672512](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37748672512) | [37748672621](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37748672621) |
| English `1eee337` | [37748723866](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37748723866) | [37748723958](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37748723958) | [37748723978](https://github.com/Rice-Zhou/Vehicle-System-Release-Quality-Gate/actions/runs/37748723978) |

All six exact-commit CI runs succeeded. Kotlin test compilation and bilingual Pair Gate passed locally. Docker is unavailable locally, so PostgreSQL runtime evidence comes from CI.

## Remaining boundary and next action

A controlled full API sequence from an actual new Release, Traceability Snapshot, terminal Run, published demonstration rule, and real Evidence through completed/error decisions and source navigation remains unexecuted. Rule publication is prohibited by the current project instruction. This work did not call the publication endpoint or change real rule state; that step requires separate authorization and an isolated environment. Task 6 read-only presentation, device evidence, and Owner acceptance remain separate. TDR-026 is still Proposed / REVIEW_REQUIRED.

Current result: the Task 5 formal source negative matrix, snapshot schema projection, and isolated HTTP → formal reader → Worker → HTTP decision sequence passed exact-commit CI; actual sources and Evidence have not been integrated into that sequence. Git status: bilingual tests and engineering records are separately committed and pushed. Next action: integrate and review actual sources, Evidence, and source navigation in an authorized isolated environment. Prerequisites: rule publication and demonstration resources require separate authorization. Acceptance target: give the Owner evidence for fixed sources, failure paths, atomic results, recovery, and actual API decisions for independent acceptance.
