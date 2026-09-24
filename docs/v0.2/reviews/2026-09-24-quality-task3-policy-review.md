# Minimal quality evaluation Task 3: rule policy review

Date: 2026-09-24. References: [TDR-026](../tdr/TDR-026-minimal-quality-evaluation.md), [technical design](../../superpowers/specs/2026-09-15-minimal-quality-evaluation-design.md), [implementation plan](../../superpowers/plans/2026-09-17-minimal-quality-evaluation-implementation.md).

## Owner decision and scope

The Owner replied “accept these three policies and implement Task 3” to the specific proposal: use Fact Catalog v2 while retaining Traceability confidence levels; source `requiredIssueRefs` explicitly from the published Rule Set; make demonstration Case `PASS` nonblocking and `FAIL`, `BLOCKED`, `SKIPPED`, `ERROR`, and `TIMEOUT` blocking, while a missing or nonterminal Result makes the Evaluation `ERROR`. This authorizes Task 3 implementation, not rule publication, product acceptance, or milestone acceptance.

## Implementation boundary

Task 3 delivers only catalog binding, pure rule evaluation, aggregation, matrix tests, and versioned demonstration rules. Reference ownership, published Rule Set and nonempty `selectedCaseRefs` validation, Evidence integrity, database jobs, and actual integration remain at their formal boundaries in later tasks. Unit-test facts do not constitute acceptance of real inputs; catalog v1, the Core Contract, and existing module digests remain unchanged.

The three policies use the existing versioned catalog and Rule Set source without proposing a change to frozen architecture semantics. Remaining TDR-026 review and V0.2 Architecture Review retain their existing governance status; this record does not mark the whole TDR accepted or approve an Owner Gate.
