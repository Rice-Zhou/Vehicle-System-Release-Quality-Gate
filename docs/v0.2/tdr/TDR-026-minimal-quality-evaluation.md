# TDR-026 — In-process minimal quality evaluation and fact binding

- Date: 2026-09-15; status: Proposed / REVIEW_REQUIRED.
- Scope: technical design for a bounded quality slice, not full M4 acceptance or rule publication.
- Design: [minimal quality evaluation](../../superpowers/specs/2026-09-15-minimal-quality-evaluation-design.md).

## Requirements and basis

Reuse delivered Release/Traceability/Smoke capabilities to add formal, deterministic and replayable decisions. Retain TDR-001/002/003/007/008/025 without Company resources or changed frozen responsibilities.

Inspection found quality APIs are contract declarations, Issue Snapshots lack required, and Traceability confidence is categorical while Fact Catalog v1 requires a number. Feeding demo summaries directly to rules or inventing numeric scores would obscure these gaps.

## Proposed decisions

1. The Backend quality module performs pure rule evaluation in-process, with PostgreSQL Jobs serving the existing asynchronous 202 request contract. Application ports read each module's sole formal source; the Evidence module still implements integrity checks.
2. Published content retains YAML, restricted AST, catalog/engine versions, review/identity and digests. The database is runtime authority; Git retains rule provenance without dynamic evaluation from Git.
3. Fact Catalog v2 explicitly binds collection item fields and uses minimumConfidenceLevel to retain categories without inventing numbers; preserve v1 unchanged. requiredIssueRefs and selectedCaseRefs are reviewed published Rule Set policy, included in input and Rule Set digests without rewriting Issue Snapshots.
4. Requests accept formal references only and pin same-Release input before evaluation. Short transactions, state rechecks and fencing prevent source changes or stale workers from overwriting results; newer successful Runs do not alter historical selection.
5. Use a typed canonical value tree for large integers and decimals: every node has a type tag; numeric values are canonical decimal strings, integers lack leading zeros, decimals omit insignificant trailing zeros and negative zero becomes zero. Sort object keys stably, preserve strings without trim/locale changes and order arrays by catalog. Encode and SHA-256 this representation without JSON-number precision risk, recording an independent encoding version; existing module JCS is unchanged. Golden bytes fix the encoding instead of implementation guesswork.
6. Retain existing authorization/audit/version protection and read-only reporting. Engineering resource limits are specified in the design, not Company SLOs.

## Alternatives and trade-offs

- Offline JS decisions: easy presentation but introduce a second quality authority and bypass formal snapshots; rejected.
- Separate rule service/general scripting engine: no distributed requirement in this slice, with higher deployment/replay costs; rejected.
- Invented probabilities for HIGH/MEDIUM/LOW: changes fact meaning; rejected in favor of explicit catalog versioning.
- Synchronous HTTP evaluation only: shorter implementation but inconsistent with the existing asynchronous 202 contract and recovery approach; reuse Jobs.

Cost: complete machine response contracts, catalog v2 and policy provenance instead of adding two if statements. Explicitly reject old catalogs without runtime bindings and retain old contract tests without claiming old-version runtime compatibility.

## Testing and migration

Deliver catalog/AST/encoding golden tests and the full operator matrix before API/DB and real integration. Cross-Release input, missing/corrupt facts, rule exceptions, idempotency conflicts, late writes, recovery and three replays map to A1–A8. Use additive migrations and immutable protection for new tables without rewriting old snapshots/algorithms. Rollback to a version without the new module retains new historical data; finish or pause outstanding tasks first, preventing old software from processing new Engine versions.

Implementation-plan preflight must verify parser node capabilities, restriction settings and dependency pinning. No version is installed or selected this turn; configuration assumptions are not verified facts. Existing positive Schema examples do not establish safe node parsing.

## Review boundaries and reconsideration

This TDR remains Proposed. Explicitly review catalog type integration, required policy provenance and the Case action table; frozen semantic changes require ADR first. This document authorizes no implementation, rule publication, real Provider, device operations, merge, Tag or deployment.

Reconsider if the catalog cannot preserve historical interpretation, measured resources exceed budgets, multiprocess capacity is actually required or the parser cannot enforce strict node restrictions. Record measurements and alternatives instead of silently adding services or permissive parsing.
