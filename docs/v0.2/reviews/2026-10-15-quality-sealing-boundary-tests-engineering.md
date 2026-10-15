# Quality sealing-boundary test evidence

- Date: 2026-10-15; nature: targeted test evidence, not Owner acceptance.
- Basis: the [sealing evidence review](2026-10-15-quality-confirmation-and-sealing-review.md), [Task 5/6 plan](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md) and [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md). The user authorized these two test categories, with repairs only after actual behavioral failure; no entry-point expansion, rule publication or acceptance-state change.

## Scope and assertions

Only the existing [QualityEvaluationIntegrationTest](../../../backend/src/test/kotlin/com/ricezhou/vsrqg/quality/QualityEvaluationIntegrationTest.kt) is extended, using the real PostgreSQL repository and its existing mocked source port. Production code is unchanged.

| Test | Input and expectation | Persistence assertions |
| --- | --- | --- |
| Evidence metadata change | Retain the same Input Digest, Payload size/checksum and binding, changing only metadataDigest; seal returns QUALITY_SOURCE_CHANGED | No Snapshot, Rule Results, Quality Result or completion Audit/Outbox is added, and the entire Job row stays unchanged. The fixture then explicitly calls fail; the original Evaluation is queryable as ERROR without pinned input or a Quality Result. |
| Seal boundary without reclaim | Never call claimNext again after the first claim; allow 299 seconds, reject exact expiry at 300 seconds and 301 seconds with QUALITY_STALE_LEASE | One Snapshot when allowed, zero when rejected; Job attempt, status, times and summary stay unchanged, with no completion side effects. |
| Complete boundary without reclaim | Seal within the valid lease, then submit rules and a result at the same three times; complete at 299 seconds, reject 300/301 seconds | On rejection, the original Snapshot stays exactly unchanged, the entire Job row is unchanged and Evaluation stays RUNNING; Rule Results, Quality Result and completion Audit/Outbox counts are zero. The allowed control returns the original result with Job SUCCEEDED and attempt 1. |

One ordinary test and two parameterized tests with three inputs each add seven targeted invocations. Existing class-level 60-second timeouts, independent projects and unfinished-Job cleanup remain in use. Removing the repository Evidence-object comparison would fail the metadata test; removing the lease-time condition or relaxing strict expiry would fail the expiry cases. Expected values come from the documented 300-second boundary rather than production comparison logic.

## Local verification and CI limits

compileTestKotlin succeeded in both worktrees. The Chinese targeted class test failed during Testcontainers static initialization with Could not find a valid Docker environment. Gradle reported 18 initialization-failure nodes, including all seven expanded new invocations, before behavioral assertions ran. Logs and original XML remain in the local evidence directory `task5-seal-boundary-20261015`. This environment failure is neither a pre-fix red test nor evidence that the seven invocations passed locally. Distinct operation names in parameterized display names let CI XML distinguish seal and complete at the same time inputs.

Independent read-only review found no Critical, Important or Minor issues. If isolated CI passes the new tests immediately, that establishes existing behavior without a claimed red-green repair. Any actual behavioral failure must retain its output and be diagnosed before code changes. Exact-commit bilingual M1/M2/M3 and Pair Gate still require post-commit verification; local compilation cannot replace them. Target-class XML in the M1 archive should identify each of the seven new invocations with zero failures, errors and skips.

## Uncovered scope and governance

The source port remains mocked in this class. This evidence establishes repository comparison of previously read sources and Job fencing; it does not prove that normal APIs can modify immutable Evidence metadata or establish concurrent changes in real source storage. The metadata case becomes ERROR through an explicit fixture fail call, not a demonstrated automatic Worker path. Payload bytes retain the TDR-026 outside-transaction window; no direct runtime evidence for transaction state during byte verification or lock duration is added.

TDR-026 remains Proposed / REVIEW_REQUIRED; composite Task 5/6 clauses and Owner acceptance states do not change. Test rules are constructed by the existing isolated fixture in temporary databases. No rule-publication API, real Provider, Company resource or device is used.

Current result: seven targeted invocations cover Evidence metadata change and lease expiry before reclaim; local compilation passed and database behavior awaits exact-commit CI. Git status: determined by the paired commits containing this record and remote verification. Next action: add direct test evidence that Evidence byte verification runs without an active transaction. Prerequisites: passing exact-commit targeted cases and CI for this work; reuse isolated environments. Acceptance target: distinguish source-read transactions, outside-transaction byte verification and sealing transactions without rereading files or changing window semantics; engineering validation does not replace Owner acceptance.
