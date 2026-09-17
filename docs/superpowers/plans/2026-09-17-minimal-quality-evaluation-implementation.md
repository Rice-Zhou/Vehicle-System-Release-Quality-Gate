# Minimal quality evaluation implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: use superpowers:executing-plans task by task; if subagent execution is explicitly selected, use superpowers:subagent-driven-development. Check boxes only after actual completion.

**Goal:** Produce explainable, replayable bounded quality outcomes from formal same-Release input.
**Architecture:** Add quality to the existing modular Backend; application read ports bind formal sources, PostgreSQL Jobs pin input, a pure evaluator produces results, and reporting is read-only.
**Tech Stack:** Kotlin 2.2.21, Spring Boot 3.5.16, JDK 21, PostgreSQL, SnakeYAML 2.5 candidate and existing Node contract tools.
**Spec:** [technical design](../specs/2026-09-15-minimal-quality-evaluation-design.md), [scope A1–A8](../specs/2026-09-15-minimal-quality-evaluation-scope-draft.md), [probe amendments](../../v0.2/reviews/2026-09-15-quality-evaluation-preflight.md), [TDR-026](../../v0.2/tdr/TDR-026-minimal-quality-evaluation.md).

## Global constraints and execution state

The current “execute the next step” instruction authorizes planning, not recording TDR Accepted or product acceptance. Plan status is READY_FOR_REVIEW; all tasks below are unexecuted. Before product implementation, confirm TDR-026 catalog integration, required policy and Case actions; frozen changes require ADR first. Smoke approval does not extend to this slice.

- Preserve Core Contract, old Snapshots, catalog v1 and digest algorithms. Reject unsupported runtime catalogs without silent conversion.
- One Run/Case, 20 Issues / 2000 Edges; 64 KiB per rule, depth 32, 4096 nodes; 32 rules per Set, 4 MiB input, 100000 evaluation steps.
- Numeric token/precision/integer-digit limit 4096, absolute scale 4096, expanded length 8192; excess explicitly yields ERROR.
- Uncollected Crash/ANR remains Missing/UNKNOWN; missing required inputs, no applicable rules or integrity errors never PASS.
- No new services, Company resources or real Providers; no automatic device actions, rule publication, merge, Tag, release or deployment.
- Reuse existing bilingual worktrees; commit and push each coherent segment, pairing Markdown and matching non-Markdown blobs. Preserve user changes.
- Set default JUnit backend unit-test timeout to 60 seconds; DB integration uses existing controlled test configuration. Missing DB environments mean unexecuted tests, not passing skips.

Path convention: P = backend/src/main/kotlin/com/ricezhou/vsrqg/quality, T = backend/src/test/kotlin/com/ricezhou/vsrqg/quality. These are exact directory prefixes, not declarations that the module already exists; create each file in its task. Gradle commands run from backend; Node commands run from repository root.

## Task 1: Machine contracts and source fact binding contracts

**Files:** Create contracts/facts/v0.2/fact-catalog-v2.json, schemas/v0.2/fact-catalog-v2.schema.json, schemas/v0.2/quality-evaluation.schema.json, scripts/tests/quality-contract.test.mjs; modify contracts/openapi/v0.2/openapi.json and scripts/contract-validator.mjs. Preserve original fact-catalog.json and its Schema.
**Interfaces:** v2 explicitly declares minimumConfidenceLevel enums, source/Case/order fields and local item bindings; define itemBindings and enumValues in the new Schema while old Schema rejects them. Rule Sets declare catalogVersion/engineVersion/requiredIssueRefs/selectedCaseRefs/project; requests still accept formal references only and responses separate Evaluation ERROR from quality actions.
**Binding map:** Issue required comes only from published Rule Sets; severity/source from exact Issue Snapshots; fixed/included/verified from matching Traceability; Cases from formal Resolution; Evidence from its sole verification port. Inspect readable source fields individually; missing fields need application read ports, never defaults.

- [ ] Write failing contract tests: unchanged v1 bytes, item.status allowed only for Test collections, item.required only for Issues, UNKNOWN level accepted but numbers rejected, empty selectedCaseRefs rejected. Cross-project reference rejection belongs in runtime tests, not a claim that Schema can inspect ownership.
- [ ] Run Node tests and record failures caused by missing new contracts.
- [ ] Complete contracts and explicit positive/negative fixtures; the full validator loads both v1/v2. Document request extensions to existing unimplemented quality routes in the compatibility report; never silently update compatibility-baseline.
- [ ] After passing tests, commit the contract segment and record A1/A3/A4/A5 structural checks and remaining runtime checks.

```js
// scripts/tests/quality-contract.test.mjs: preserve the v1 identity.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
test('catalog versions stay distinct', async () => {
  const read = async p => JSON.parse(await readFile(p, 'utf8'));
  assert.equal((await read('contracts/facts/v0.2/fact-catalog.json')).version, 1);
  assert.equal((await read('contracts/facts/v0.2/fact-catalog-v2.json')).version, 2);
});
```

```powershell
node --test scripts/tests/quality-contract.test.mjs
node scripts/contract-validator.mjs
```

## Task 2: Strict parsing, catalog binding and exact canonical encoding

**Files:** Modify backend/build.gradle.kts; create P/domain/QualityFailure.kt, P/domain/QualityValue.kt, P/domain/QualityCanonicalEncoder.kt, P/adapter/StrictRuleYaml.kt, T/StrictRuleYamlTest.kt, T/QualityCanonicalEncoderTest.kt.
**Interfaces:** QualityFailure(code: String) is an explicit domain exception; QualityValue is a sealed Null/Bool/Text/Integer/Decimal/ArrayValue/ObjectValue tree. StrictRuleYaml.parse(bytes: ByteArray): QualityValue handles strict syntax only; Task 3 validates catalog/AST types. QualityCanonicalEncoder.encode(value: QualityValue): ByteArray produces the review's typed UTF-8 format.

- [ ] First run dependencyInsight against the current BOM and explicitly pin SnakeYAML 2.5. Stop this segment on a conflict and report it without upgrading Spring Boot.
- [ ] Write rejection cases for duplicate decoded keys, alias/anchor/tag/merge, multiple documents, complex keys, plain dates/yes/on, invalid UTF-8, unpaired surrogates and byte/depth/node limits; preserve quoted strings. Test exact limits and limit-plus-one.
- [ ] Write encoding golden bytes for every type, control characters, Unicode scalar key order, no normalization, negative zero/trailing zeros, adjacent large integers and exponent expansion limits.
- [ ] Implement an event stack and per-mapping key sets, rejecting before general object construction; LoaderOptions is supplementary. Reject oversized numeric inputs before constructing/expanding numbers; never use double.
- [ ] Run targeted tests and build, then commit parsing/encoding. The 17 probe checks cannot replace rejection-layer and all-type tests.

```kotlin
@Test
fun rejectsDuplicateKeys() {
    val failure = assertThrows<QualityFailure> {
        StrictRuleYaml().parse("a: 1\na: 2\n".toByteArray())
    }
    assertEquals("RULE_DUPLICATE_KEY", failure.code)
}
```

```powershell
./gradlew.bat dependencyInsight --dependency snakeyaml --configuration runtimeClasspath
./gradlew.bat test --tests '*StrictRuleYamlTest' --tests '*QualityCanonicalEncoderTest'
./gradlew.bat assemble
```

## Task 3: Full restricted evaluator and two demo rules

**Files:** Create P/domain/RuleAst.kt, P/domain/FactBindings.kt, P/domain/RuleEvaluator.kt, P/domain/QualityAggregator.kt, T/RuleOperatorMatrixTest.kt, T/QualityAggregatorTest.kt; add contracts/examples/v0.2/quality-rule/smoke-case-outcome.yaml and required-issue-verified.yaml, registering versioned golden fixtures.
**Interfaces:** RuleAst is a sealed AST for every specified operator; FactBindings binds QualityValue and catalog v2. RuleEvaluator.evaluate(ast: RuleAst, facts: FactBindings): RuleOutcome; RuleOutcome stores status, matchedFacts, evidenceRefs and explanationCode/parameters. RuleStatus includes PASS/WARNING/BLOCK/ERROR/NOT_APPLICABLE. QualityAggregator.aggregate(statuses: List<RuleStatus>): String returns ERROR or a quality action; ERROR cannot fabricate a Quality Result.

- [ ] Build value/empty/missing/null/type-error cases for each operator from specification section 5, including and/or operand permutations; distinguish missing from explicit null.
- [ ] Write failing tests for catalog-local fields, error propagation, all rules inapplicable and evaluation-step limits.
- [ ] Implement pure evaluation/aggregation without network, files or current time, using catalog-directed BigDecimal/BigInteger comparisons.
- [ ] Cover Case PASS/FAIL/BLOCKED/SKIPPED/ERROR and required Issue false/true in both YAML rules; Case PASS can aggregate to BLOCK for an unverified required Issue. Reject empty published selectedCaseRefs in input validation without changing empty all semantics.
- [ ] Run matrix/golden tests and commit the pure engine segment without publishing rules or operating devices.

```kotlin
@Test
fun errorsDominateQualityActions() {
    assertEquals("ERROR", QualityAggregator.aggregate(
        listOf(RuleStatus.BLOCK, RuleStatus.ERROR)))
    assertEquals("ERROR", QualityAggregator.aggregate(emptyList()))
}
```

```powershell
./gradlew.bat test --tests '*RuleOperatorMatrixTest' --tests '*QualityAggregatorTest'
```

## Task 4: Rule version storage, review and publication APIs

**Files:** Create P/application/RulePublication.kt, P/adapter/JdbcQualityRepository.kt, P/adapter/RuleSetController.kt, T/RulePublicationIntegrationTest.kt and backend/src/main/resources/db/migration/V16__quality_rules.sql. Confirm the migration number is free at execution; if occupied, choose the next number and update this plan.
**Interfaces:** RulePublication.create(projectId: String, body: JsonNode, idempotencyKey: String): JsonNode; publish(projectId: String, id: String, version: Long, reason: String, idempotencyKey: String): JsonNode. JsonNode is only the contract DTO boundary; internal code uses Task 2/3 types. Read actors from existing authentication context, not client claims.

- [ ] Use existing PostgreSQL testing conventions for permissions, project isolation, authors/reviewers, same-key replay/conflict, If-Match conflicts and published UPDATE/DELETE rejection; add transaction rollback tests.
- [ ] Add tables retaining YAML, AST, catalog/engine/Rule Set versions, requiredIssueRefs/selectedCaseRefs, Git provenance, digests and review information; reuse the sole Audit/idempotency mechanism.
- [ ] Connect existing createRuleSet/publishRuleSet routes. Reject publication without permission or complete golden/type validation; simulated test publication is not permission to publish actual rules.
- [ ] Run contract, migration and integration tests, then commit; application rollback retains published history.

```sql
-- Integration test must assert rejection after publishing the fixture.
UPDATE quality_rule_set_versions SET content_digest = 'changed'
WHERE state = 'PUBLISHED';
```

```powershell
./gradlew.bat test --tests '*RulePublicationIntegrationTest'
```

## Task 5: Formal inputs, asynchronous evaluation, queries and replay

**Files:** Create P/application/QualitySourceReader.kt, QualityEvaluationService.kt, P/adapter/QualityEvaluationWorker.kt, QualityController.kt, T/QualityInputBindingTest.kt, QualityEvaluationIntegrationTest.kt, QualityReplayTest.kt; extend Task 4 repository and add V17__quality_evaluations.sql. Issue/Traceability/Test Management/Evidence provide read implementations in their own application packages; quality SQL must not duplicate source-module business validation.
**Interfaces:** QualitySourceReader.read(projectId: String, releaseId: String, request: JsonNode): JsonNode returns contract-typed pinned source content; QualityEvaluationService.request(projectId: String, releaseId: String, body: JsonNode, idempotencyKey: String): JsonNode; list(projectId: String, releaseId: String, cursor: String?): JsonNode. Workers use existing Job claim/fencing only; client-provided facts are forbidden.

- [ ] Write negative cases for the same APK in different Releases, mixed projects/Manifests, missing source fields, empty selectedCaseRefs, multiple Runs, absent Results, nonexistent required references, corrupt Evidence and appliesWhen=false. Fake readers are test-only, never production fallbacks.
- [ ] Pin source references/content in a short transaction; verify bytes through Evidence outside the transaction, then recheck source state/fencing before sealing the Input Snapshot. Failures record Evaluation ERROR without choosing new sources.
- [ ] After pure evaluation, atomically save all Rule Results, Quality Result, Audit and Job terminal state. Test rollback, reclaim, stale-lease writes, duplicate submission and uniqueness; failed Evaluations remain queryable.
- [ ] Save formal Case Resolution, selected Attempt, other Attempt history and exact rule/catalog/encoding/Engine versions. Missing interpreters reject replay instead of substituting newer ones.
- [ ] Replay complete fixed inputs in three new JVMs; compare canonical result digests despite changed time/request IDs. Separate current missing-Payload integrity checks from historical decision replay.
- [ ] Run unit/DB integration/permission/replay tests and commit, then verify restoration/replay using an independent DB+Payload copy without touching the original demo database.

```sql
-- Integration query: every committed evaluation has at most one final result.
SELECT evaluation_id FROM quality_results
GROUP BY evaluation_id HAVING COUNT(*) > 1;
-- Expected: zero rows, enforced by a database unique constraint.
```

```powershell
./gradlew.bat test --tests '*QualityInputBindingTest' --tests '*QualityEvaluationIntegrationTest' --tests '*QualityReplayTest'
```

## Task 6: Same-Release demo, read-only report and acceptance evidence

**Files:** Create backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/QualityDemoScenario.kt, scripts/demo/run-quality.ps1, docs/demo/quality-evaluation-runbook.md; modify scripts/demo/demo-report.mjs, render-report.mjs, scripts/tests/demo-report.test.mjs; add .github/workflows/quality-evaluation.yml. Reuse existing M2/M3 preparation and formal APIs without duplicating permissions or Evidence uploads.
**Interfaces:** The demo accepts only a controlled configuration path and creates new Release/Snapshot/Run through formal APIs. Reporting consumes exported quality queries and bound references without inferring quality from original M1/M2 files. Preserve existing m1/m2 parameter behavior; quality scope requires a strict input Schema and a fresh output file without overwrite.
**Evidence:** A bounded PASS example with normal Smoke and explicitly empty requiredIssueRefs; a deterministic FAIL yielding BLOCK; an unverified required Issue yielding BLOCK; missing required Evidence yielding ERROR. Distinguish synthetic Issue/Build provenance from real-device Evidence without claiming real Issue verification.

- [ ] First run four outcomes in CI fixtures plus invalid report inputs, HTML escaping and cross-Release report binding; existing report regressions must pass.
- [ ] Implement formal integration and read-only reporting; new Runs never overwrite historical selections. Preserve input/rule/execution versions, outcomes, uncovered scope and Evidence locators.
- [ ] CI runs contracts, full operator matrix, target tests/build, failures and three replays; shared-CI performance remains a shared-environment observation.
- [ ] Before device execution, confirm current authorization, configuration and environment, using new output/spool. Unexecuted checks remain UNKNOWN; old screenshots cannot stand in for a new run.
- [ ] Review the diff and complete engineering review; record Subjects/evidence and residual risks per A1–A8 for separate Owner acceptance, without self-assigning PASS to the entire matrix.

```powershell
node --test scripts/tests/demo-report.test.mjs
node scripts/contract-validator.mjs
./scripts/demo/run-quality.ps1 -Config D:/controlled/quality/config.json
```

The final command is only for subsequently authorized device execution; its configuration path is an example, not executed while writing this plan. run-quality.ps1 must expose nonzero exits and error summaries without silent fallback.

## Coverage, checks and next step

| Acceptance | Main tasks |
|---|---|
| A1 | 1, 5, 6: formal same-Release binding and negative cases |
| A2 | 3, 5, 6: rule outcomes, results and navigation |
| A3 | 1, 3, 4, 5: required provenance and preserved Verified |
| A4 | 2, 3, 5: invalid inputs, missing versions and no applicable rules |
| A5 | 1, 3, 5, 6: Missing/Empty/Null/UNKNOWN and uncovered scope |
| A6 | 2, 3, 5: guards, exact encoding, full matrix and three replays |
| A7 | 4, 5: permissions, immutability, idempotency, late writes, rollback and recovery |
| A8 | 6: read-only presentation, provenance and evidence acceptance |

Use each segment's targeted tests before affected builds/minimal smoke. Tool unavailability is not an expected red test. Before committing, run Markdown pairing, acceptance/contract checks and git diff --check; after pushing, verify exact remote commits and CI, reporting unfinished CI honestly.

Current result: six segments and A1–A8 coverage prepared; no task implemented. Git status: bilingual versioned plan commits identified through Git history. Next action: execute Task 1 after confirming TDR-026 and this plan. Prerequisites: Owner acceptance of catalog integration, required policy and Case actions; frozen changes require ADR first. Acceptance target: Task 1 preserves v1 and passes v2 positive/negative fixtures and API contract checks, with a separate commit and record.
