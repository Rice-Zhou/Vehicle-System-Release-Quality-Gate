# Task 6 report fields and reference consistency engineering record

- Date: 2026-10-14; nature: report-boundary fixes and engineering verification.
- Basis: the [Task 6 plan](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md) and the read-only report addendum in [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md). The Owner authorized completing fields and reference checks, failing tests before fixes, and paired commits and pushes; this does not authorize extending the demo entry point, publishing rules, or changing acceptance states.

## Fixed behavior

The common report validator previously allowed policy Cases to conflict with pinned selections, Evidence from another Run/Attempt or absent from the queried Test Result, and rules referencing Evidence absent from the input. These inputs now fail with `QUALITY_REPORT_INPUT_MISMATCH`; Evidence IDs in pinned input must be unique. Applicable rules that finish evaluation reference the complete pinned Evidence set, while `ERROR` and `NOT_APPLICABLE` rules reference none. Set order does not affect association checks. These checks compare existing references without recomputing quality decisions or business digests.

Association checks reside in the existing common export boundary. The CI fixture no longer duplicates Run/Attempt/Result membership checks; it separately checks Evidence metadata, digest, size, state and the integrity observation at that time. Exports without source responses remain allowed with explicit report limitations, but policy/selection consistency, Evidence binding and rule references must still match.

Bilingual HTML now includes `resultId`, the Evaluation Rule Set identity, the pinned selection Case/version, Attempt number and other Attempt references, plus `matchedFacts` values, rule explanation parameters and original error parameters. Diagnostic values retain JSON types, `INTEGER`/`DECIMAL` tags and decimal strings, `false`, `null` and explicit empty collections. Added content uses the existing HTML escaping path. Pre-pin `ERROR` remains `NOT_EVALUATED` with its Rule Set identity and no fabricated Quality Result.

## Verification evidence

New failing tests ran first with exit code 1; failures showed missing expected rejection and report fields rather than environment errors. Independent review found that the pre-pin ERROR early return bypassed rule Evidence validation. After adding failing tests for nonempty `ERROR`/`NOT_APPLICABLE` references, that path now uses the same validation. After fixes, 63 target report, export, CI fixture and legacy M1/M2 regression tests passed. Full contract validation passed (7 schemas, 22 positive, 9 negative, 36 operations), as did acceptance-record validation and `git diff --check`. Windows cannot create symbolic links, so the quality-file rejection test did not exercise that branch locally; the aggregate Node result does not replace evidence for that branch.

This record is committed with the patch. Verify the exact Subject, bilingual Pair Gate, remote commits and M1/M2/M3 results against the corresponding Git and GitHub commits. This record does not predeclare remote CI success. Backend production code, API/Schema and migrations are unchanged; PostgreSQL integration tests were not rerun locally.

## Scope and next action

This work does not change `Proposed / REVIEW_REQUIRED`, composite Task 5/6 clauses or Owner acceptance states. Pinned Evidence digests do not prove current Payload integrity; metadata reads record integrity observations, and downloads require separate authorization, purpose and audit. Static reports do not query services or download Payloads.

Current result: omitted fields and conflicting references have red-green regression evidence. Git status: determined by the paired commits containing this record and remote verification. Next action: verify the patch's exact paired commits with Pair Gate and M1/M2/M3 CI. Prerequisites: existing GitHub access. Acceptance target: both remote branches point to their corresponding patch commits, Pair Gate passes and all six exact-commit CI runs succeed; this engineering target does not replace Owner acceptance.
