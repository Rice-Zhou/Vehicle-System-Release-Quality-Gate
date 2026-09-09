# Minimal synthetic M2 demonstration implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reuse M1 to demonstrate actual input processing, Traceability and historical queries for two synthetic Issues.

**Architecture:** Use the existing demo source set and HTTP/transactions/Workers; inject Fixture inputs and the dedicated validator only in the isolated launcher. The demo does not write business results or add production endpoints.

**Tech Stack:** Kotlin/JDK 21, Spring Boot, PostgreSQL 17.11, existing PowerShell/Gradle/GitHub CI.

**Spec:** [TDR-022](../../v0.2/tdr/TDR-022-synthetic-m2-demonstration.md), also serving as this plan's design specification; both tasks have completed engineering verification; Owner review is PENDING.

## Global Constraints

- Do not modify frozen V0.1 semantics, Schema, Migrations, production APIs, permission rules, canonicalization or the Quality Engine.
- M2 first completes M1 in the same run and reuses its Release, Locked Manifest and payload digest.
- All results remain Verified=false; classification=SYNTHETIC_DEMO, proofKind=SYNTHETIC_FIXTURE.
- Add no service, software installation, Company/AWS resource, archive operation or frontend.
- Backend unit tests default to 60-second timeouts; without usable local Docker, exact-commit CI supplies actual PostgreSQL/HTTP evidence.
- Commit each task independently in both languages, pass Pair Gate and push; preserve existing workspace content. Record Owner acceptance separately; never approve on the Owner's behalf.

## Task 1: Isolated synthetic inputs and identities

**Files:**

- Create `backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/M2DemoInputs.kt`: Fixture factory, descriptor and fixed Mapping definition.
- Create `backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/M2DemoProvenanceValidator.kt`: bounded synthetic input matching.
- Modify `backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/M1DemoMain.kt`: explicit M2 option and bean injection.
- Modify `backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/M1DemoIdentity.kt`, `M1DemoBootstrap.kt` in the same directory: Engineer/Service and FIXTURE Source configuration.
- Create `backend/src/test/kotlin/com/ricezhou/vsrqg/demo/M2DemoInputsTest.kt`.
- Modify `backend/src/test/kotlin/com/ricezhou/vsrqg/demo/M1DemoPackagingTest.kt`.

**Interfaces:**

Retain `IssueSourceRuntimeFactory.open(profile: CompiledIssueMappingProfile): IssueSourcePort` and `BuildProvenanceValidatorPort.validate(provenance: CanonicalBuildProvenance): ProvenanceValidation`. Add `M2DemoProvenanceValidator(payloadSha256: String)`, `M2DemoInputs.mappingDefinition(): JsonNode`, and `M2DemoInputs.factory(observedAt: Instant): IssueSourceRuntimeFactory`. Add default-disabled `includeM2: Boolean = false` to existing start/environment; M2 start additionally receives the measured payload SHA and fails explicitly if absent. Append default parameters scopes, principalType and projectReference to Identity token, preserving M1 callers. Add a Bootstrap M2 initialization method returning Engineer/Service subjects and sourceId, used only in memory.

- [x] Write unit tests requiring no Docker, using the following fixed Mapping. The factory must use the compiled mappingVersion rather than override it:

```json
{
  "schemaVersion":"jira-mapping-profile/v1",
  "normalizationVersion":"unicode-nfc-trim-root-lower/v1",
  "unknownStatusPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
  "unknownSeverityPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
  "statusAliases":{"CLOSED":["Closed"]},
  "severityAliases":{"HIGH":["Major"]}
}
```

```kotlin
@Test
@Timeout(60)
fun fixtureUsesCompiledVersion() {
    val codec = JcsIssueMappingProfileCodec(jacksonObjectMapper())
    val profile = codec.compile(M2DemoInputs.mappingDefinition())
    val factory = M2DemoInputs.factory(Instant.parse("2026-09-08T00:00:00Z"))
    assertThat(factory.descriptor.sourceType).isEqualTo("FIXTURE")
    assertThat(factory.descriptor.adapterVersion).isEqualTo("m2-demo-fixture/v1")
    assertThat(factory.open(profile).fetchByIds(setOf("DEMO-1", "DEMO-2")).issues)
        .extracting<String> { it.mappingVersion }.containsOnly(profile.mappingVersion)
}
```

- [x] Run `./gradlew test --tests '*M2DemoInputsTest' --tests '*M1DemoPackagingTest'` from backend with JDK 21 and record RED. Missing new types should fail; environmental failures are not RED.
- [x] Implement one terminal FixturePage containing two CLOSED/HIGH NormalizedIssues with sourceVersion=1, sourceReference=SYNTHETIC_DEMO and observedAt/mappingVersion from the inputs. Reuse FixtureIssueSourceAdapter fetch/size behavior.
- [x] Implement the bounded validator below without copying the canonicalizer. Each pair binds Build, revision and Issue together; cross-pair combinations are invalid:

```kotlin
val pairMatches = when (e.buildId) {
    "1" -> e.sourceRevision == "a".repeat(40) && e.sourceIssueIds == listOf("DEMO-1")
    "2" -> e.sourceRevision == "b".repeat(40) && e.sourceIssueIds == listOf("DEMO-2")
    else -> false
}
val matches = pairMatches && e.provider.value == "github-actions" &&
    e.repository == "vsrqg-synthetic/demo" && e.pipeline == "synthetic-m2" &&
    e.buildAttempt == 1 &&
    e.workflowReference == "vsrqg-synthetic/demo/.github/workflows/demo.yml@synthetic" &&
    e.proofReference == "https://github.com/vsrqg-synthetic/demo/actions/runs/${e.buildId}/attempts/1" &&
    e.artifactSha256s == listOf(payloadSha256) &&
    e.proofDigest == provenance.recomputedProofDigest
return ProvenanceValidation(
    if (matches) VerificationStatus.VALID else VerificationStatus.INVALID,
    Confidence.LOW, "m2-demo-fixture-provenance/v1",
    if (matches) "SYNTHETIC_FIXTURE_MATCHED" else "SYNTHETIC_FIXTURE_MISMATCH",
)
```

Here e=provenance.normalized. Assert VALID/INVALID, LOW and the dedicated version for matching input, every mismatched constant and a mismatched digest. Existing application checks retain project/Snapshot/Artifact authority. BuildProvenanceTransaction persists INVALID facts; do not incorrectly expect ingestion 422. Rejecting INVALID verification input is the existing Traceability boundary.

- [x] Register dedicated primary validator/descriptor beans and the factory in the M2 launcher, preserving the default runtime registry and canonicalizer. No component annotation or inclusion in main scanning/production packaging. Enable existing flags/Workers in M2. Use the existing parameterized Bootstrap transaction for Engineer, Service, assignments and Source with credential_reference=NULL; never Sync before activation. Preserve Manager/Viewer.
- [x] Obtain GREEN target tests, `compileDemoKotlin bootJar` and packaging checks. M1 mode has no M2 factory/validator; production JAR has no new demo classes; existing JWT wrong-signature/issuer/audience/expiry tests still pass.
- [x] Review the diff and commit paired code and task notes at `docs/m2/2026-09-08-synthetic-demo-inputs.md`; synchronize non-Markdown files, pass Pair Gate and push. Record observed checks without claiming the integrated flow is complete.

## Task 2: Actual composition, single entry point and results

**Files:**

- Create `backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/M2DemoScenario.kt`, `M2DemoReport.kt`.
- Modify `backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/M1DemoMain.kt`: invoke M2 after M1 and combine exit results.
- Modify `scripts/demo/run-m1.ps1`, `scripts/tests/m1-demo.tests.ps1`, `scripts/tests/m1-demo-ci.tests.ps1`, `scripts/tests/fixtures/m1-demo-command.ps1`.
- Create `backend/src/test/kotlin/com/ricezhou/vsrqg/demo/M2DemoIntegrationTest.kt`, `M2DemoReportTest.kt`.
- Modify `.github/workflows/m1-backend.yml`, `docs/m1/demo-runbook.md`; create `docs/m2/synthetic-demo-runbook.md`.

**Interfaces:**

Add `M2DemoScenario.run(baseUri: URI, managerToken: String, engineerToken: String, serviceToken: String, sourceId: String, m1: DemoResult, payloadSha256: String): Unit`, with M2DemoReport constructor injection. Report consumes actual HTTP projections and writes m2-summary.json; never construct business results from expectations. Reuse DemoResult and the same run's DemoReport.payloadSha256. Map `-IncludeM2` to strict boolean environment value `VSRQG_DEMO_INCLUDE_M2`; add no second Gradle/Compose launcher.

- [x] Write real PostgreSQL integration tests invoking the launcher and both scenarios. Bootstrap only basic identities/Source; never use the existing Traceability test seeder. Use these assertions, where a, aAgain and b are actual HTTP bytes parsed with the existing Jackson mapper:

```kotlin
assertThat(aAgain).isEqualTo(a)
val first = mapper.readTree(a).path("issues").associateBy { it.path("sourceIssueId").asText() }
check(first.getValue("DEMO-1").path("fixed").asBoolean())
check(first.getValue("DEMO-1").path("included").asBoolean())
check(!first.getValue("DEMO-2").path("included").asBoolean())
check(first.values.none { it.path("verified").asBoolean() })
check(mapper.readTree(b).path("issues").all { it.path("included").asBoolean() })
check(mapper.readTree(b).path("issues").none { it.path("verified").asBoolean() })
```

Also assert four-edge/empty paths, exact Gap codes, distinct A/B IDs, latest=B, unchanged A contentDigest and identical same-key ingestion/verify bytes. USER ingestion with scope returns 403. Use a separate Project for invalid facts: ingestion persists INVALID, then verify returns 422 TRACEABILITY_INPUT_NOT_VALID without contaminating the successful scenario. Poll timeouts, FAILED Runs and invalid output fields must fail the overall result.

- [x] Run `./gradlew test --tests '*M2DemoIntegrationTest' --tests '*M2DemoReportTest'` and record RED. Without Docker, do not fabricate integration RED/GREEN; run available report unit tests first and supplement in existing CI.
- [x] Call existing HTTP endpoints in TDR table order. Compute proofDigest with the existing canonicalizer: construct an envelope with a valid placeholder sha256 format, then copy(proofDigest=recomputedProofDigest). HTTP uses project and GITHUB_ACTIONS. Make no external calls. Both Builds bind the actual Issue Snapshot and sample SHA. Poll the returned same-origin statusUrl for at most 30 seconds, with 5-second requests and 250 ms intervals.
- [x] Report extracts only the TDR allowlist and prints actual Issue paths/Gaps and historical comparisons; raw requests stay in memory. Failure after M1 means overall FAILED and nonzero exit even if M1 summary.json is PASS. Report unit tests reject missing fields, unknown states, unexpected Verified=true and incomplete scenarios; output contains no Token/locator/connection information.
- [x] Add IncludeM2 and clear usage text to the entry point; preserve the 20 existing default-M1 fixture contracts. Extend the CI harness with two M2 runs on the same volume, unique runIds/Projects, checking three outputs, stable history, service ownership and retained volume. Reuse existing PostgreSQL/Compose without software installation or down/delete/recreate.
- [x] Run target tests, shell tests, compilation/bootJar, existing M1/M2 regression and paired Pair Gate. Upload m2-summary.json through the existing workflow. Bind the acceptance packet to exact Subjects, CI Runs, observed test counts, failures/skips and Artifact expiry; documentation commits are not implementation Subjects.
- [x] Review the diff, commit and push both languages. Record observed results/limitations in `docs/m2/2026-09-08-synthetic-demo-walkthrough.md`. Create a pending Owner acceptance record only after implementation and evidence are complete; never prefill APPROVE.

## Self-review and delivery status

Task 1 covers Source, Mapping, synthetic Build validation, identities and packaging. Task 2 covers real HTTP/Workers, complete/missing chains, later facts, history, negative paths, output and reuse. Checked ingestion HTTP 200, Snapshot selectedCount, Manager/Engineer permission differences and persistence of INVALID facts to avoid tests based on false assumptions. Design self-review does not mean tests ran.

Current result: both TDR-022 tasks have completed implementation, independent review, and bilingual CI verification; the [Owner review record](../../governance/acceptance/records/2026-09-09-tdr-022-m2-demo-review-001.md) is PENDING. Git status: implementation Subjects 8d5354d / db98f89 were pushed as a pair; current records are versioned under bilingual governance. Next action: the Owner reviews TDR-022-M2-DEMO-REVIEW-001 and decides on the fixed Subjects. Prerequisites: an explicit Owner decision; report review requires no new environment. Acceptance target: confirm the synthetic walkthrough meets the current demonstration goal, or state specific conditions/adjustments, and record the decision under existing governance.
