# Minimum Local M1 Demonstration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete Release → Manifest verification → Lock → export with actual synthetic files over real HTTP.

**Architecture:** Reuse the same Backend, JWT/RBAC, PostgreSQL, and Manifest persistence transaction. A demo source set supplies temporary identity, initialization, and HTTP demonstration; main only adds a replaceable file-verification interface.

**Tech Stack:** Existing Kotlin/JVM 21, Spring Boot/Security/Nimbus, PostgreSQL 17.11, Gradle, PowerShell, JUnit, and Testcontainers; no new services or libraries.

**Spec:** [TDR-021](../../v0.2/tdr/TDR-021-local-m1-demonstration.md), Proposed; this is a reviewable draft requiring approach confirmation before code implementation.

## Global Constraints

- Preserve frozen semantics, API/Schema, Migrations, history, and Owner status; do not start M2 integration, M3/M4, or a frontend.
- Ordinary Backend remains INCOMPLETE; only demo configures m1-local-payload/1, PILOT, NONE, loopback, and dedicated vsrqg_demo.
- Limit files to 1 MiB and 16 per invocation; reject empty/invalid digests. Missing/unreadable files yield INCOMPLETE; mismatches/escape/limits yield FAILED; all actual matches are required for VALID. FAILED takes precedence over INCOMPLETE. Retain fixed violations and exact array positions.
- Unit tests default to 60 seconds; end-to-end tests use real TCP HTTP, JWT signatures, and PostgreSQL without injected conclusions. Each task ends with paired commits, Pair Gate, push, and CI verification; final acceptance belongs to the Owner.

## Task 1: Integrate Actual File Verification

**Files:** Create backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/application/ArtifactPayloadVerifier.kt, manifest/adapter/LocalArtifactPayloadVerifier.kt, and manifest/adapter/ArtifactPayloadVerificationConfiguration.kt; modify manifest/application/ValidateManifest.kt under that same root; add backend/src/test/kotlin/com/ricezhou/vsrqg/manifest/ArtifactPayloadVerifierTest.kt. The Local implementation is an ordinary class, not automatically bound as a production Bean.

**Interfaces:** Define the single interface below; Task 2 constructs LocalArtifactPayloadVerifier(root: Path). Default configuration supplies unavailable capability only without an explicit verifier Bean and retains the original validator version; Local results use m1-local-payload/1.

```kotlin
interface ArtifactPayloadVerifier {
    fun verify(sha256Values: List<String>): PayloadVerification
}
data class PayloadVerification(
    val status: ValidationStatus,
    val violations: List<ManifestViolation>,
    val validatorVersion: String,
)
```

- [ ] Write RED: create a digest-named UTF-8 demo file in a temporary directory, then change its bytes without renaming it; assert the following, plus missing/empty/invalid/symlink/oversized/default-implementation cases.

```kotlin
val bytes = "demo".toByteArray()
val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }
Files.write(root.resolve(sha), bytes)
val verifier = LocalArtifactPayloadVerifier(root)
assertThat(verifier.verify(listOf(sha)).status).isEqualTo(ValidationStatus.VALID)
Files.writeString(root.resolve(sha), "changed")
assertThat(verifier.verify(listOf(sha)).status).isEqualTo(ValidationStatus.FAILED)
```

- [ ] Confirm missing interface/behavior causes RED: `./backend/gradlew -p backend test --tests '*ArtifactPayloadVerifierTest'`. Use gradlew.bat on Windows.
- [ ] Read files through bounded streams and recompute digests; forbid path inputs. Inject the verifier into ValidateManifest only when original failures are empty, mapping status, violations, and version into existing ValidationReport. Default configuration retains original INCOMPLETE; do not change LockManifest, RegisterManifest transactions, or historical reads in the validate API.
- [ ] Run target and existing Manifest/Lock tests, proving ordinary-profile behavior and historical digests unchanged. Run actual PostgreSQL tests with a container environment; initialization failures are not PASS.
- [ ] Review diff, synchronize both languages, and commit: `feat(manifest): verify local demonstration payload bytes`.

## Task 2: Isolated Launcher and Actual Authentication

**Files:** Modify backend/build.gradle.kts; create backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/M1DemoMain.kt, M1DemoIdentity.kt, M1DemoBootstrap.kt, and M1DemoScenario.kt; add backend/src/test/kotlin/com/ricezhou/vsrqg/demo/M1DemoIntegrationTest.kt and M1DemoPackagingTest.kt. Do not duplicate production SecurityConfig or Permission.

**Interfaces:** M1DemoMain provides m1Demo and explicitly imports original VsrqgApplication and demo configuration; M1DemoIdentity supplies the in-memory signer and real JwtDecoder Bean; M1DemoBootstrap initializes only three identity/project tables. M1DemoScenario.run(baseUri: URI, managerToken: String, viewerToken: String): DemoResult calls existing APIs. DemoResult contains runId, releaseId, manifestId, contentDigest, and scenarioStatuses, with output fixed in Task 3. Tokens never enter DemoResult.

```kotlin
data class DemoResult(
    val runId: String,
    val releaseId: String,
    val manifestId: String,
    val contentDigest: String,
    val scenarioStatuses: Map<String, String>,
)
```

- [ ] Write RED using Testcontainers with dedicated vsrqg_demo, real ports, and HttpClient: POST without Authorization returns 401, VIEWER with write scope returns 403, and incorrect signatures/expiration/issuer/audience are rejected. Never use MockMvc jwt.
- [ ] Run `./backend/gradlew -p backend test --tests '*M1DemoIntegrationTest' --tests '*M1DemoPackagingTest'`, confirming missing launcher and isolation behavior.
- [ ] Configure demo source set to reuse main output/dependencies, include demo output on test classpath, and use demo runtimeClasspath for m1Demo. Production bootJar excludes demo; packaging tests enumerate JAR entries and reject com/ricezhou/vsrqg/demo/. Use existing toolchain 21.
- [ ] Register the temporary decoder and Local verifier explicitly, then start the same application. Enforce loopback/PILOT/NONE/dedicated database and disabled Workers; validate JWT claims as specified. Initialize only project/principal/project_assignment through parameterized INSERTs with separate per-run IDs and explicit collision failure. Do not add demonstration switches to production application.yml.
- [ ] Over real HTTP, create Release, build CONFIG Manifest dynamically, register, validate, Lock, and export. Corrupt a working file before registration under a separate Release. Check duplicate keys return original responses without modifying validation. Close the owned context in finally without stopping an external database.
- [ ] After GREEN, run `./backend/gradlew -p backend bootJar`, inspect package contents and ordinary-profile regression, then commit both languages: `feat(demo): run isolated m1 http demonstration`.

## Task 3: Single Command, Results, and Full Verification

**Files:** Create scripts/demo/run-m1.ps1, scripts/tests/m1-demo.tests.ps1, demo/m1/sample-config.txt, and docs/m1/demo-runbook.md; extend M1DemoScenario.kt result output; modify .github/workflows/m1-backend.yml to run the demonstration target after existing gates and upload sanitized results. Leave deploy/dev/compose.yml unchanged.

**Interfaces:** run-m1.ps1 uses a separate Compose project, port 55432, and vsrqg_demo, invoking `./backend/gradlew -p backend m1Demo`. Supply an initial new-volume password through child-process environment; reusing a volume requires the matching password, without deleting the volume or automatically changing credentials on failure. Stop only services started by this invocation. Results reside in backend/build/demo/m1/<runId>/.

- [ ] Write script RED for missing dependencies, failed database connection, nonzero child exit, leaving preexisting services running, and retaining data on password mismatch. Do not install software or print child environments/secrets.
- [ ] Run `pwsh -NoProfile -File scripts/tests/m1-demo.tests.ps1`, confirming the missing entry/behavior fails.
- [ ] Preserve the fixed synthetic file and create digest-named working copies as specified. Separate valid and corrupted flows without changing Git samples. Build summaries from explicit fields, excluding raw exceptions/HTTP headers/tokens/identities; failure reports retain nonzero exit.

```json
{
  "classification": "SYNTHETIC_DEMO",
  "status": "PASS",
  "scenarioStatuses": {
    "validFileLockExport": "PASS",
    "corruptFileRejected": "PASS",
    "unauthenticatedRejected": "PASS",
    "viewerWriteRejected": "PASS",
    "idempotentReplay": "PASS"
  }
}
```

- [ ] This example shows status fields only; actual output must include runId, code commit, actual Release/Manifest IDs, digests, and HTTP statuses. Generate values from execution, never copy example PASS values into results. Any failed scenario makes overall status FAILED.
- [ ] With available PostgreSQL/JDK, run `pwsh -NoProfile -File scripts/demo/run-m1.ps1`; verify the full TDR matrix and retention of volume/reports after controlled shutdown. Existing CI executes actual scenarios rather than relying on unverified local state.
- [ ] Document startup/password reuse/result viewing/retention/stopping, distinguishing synthetic M1 from unimplemented M2 integration and real-device capability. Run original M1/M2 regressions, contracts, acceptance records, and Pair Gate; commit `feat(demo): package reproducible m1 walkthrough`.

## Self-Review and Execution Handoff

Coverage: file-verification P0→Task 1; startup/identity P0→Tasks 2/3; real demonstration verification and readable output→Tasks 2/3. Interface names, return types, default behavior, and output boundaries agree. This plan introduces no database-state bypass or Company prerequisite. Only the draft is complete; all execution boxes remain unchecked.

Next action: after approach confirmation, implement Task 1 sequentially without creating another research/archive package. Prerequisite: TDR-021 approach confirmation; Task 1 unit tests need no Docker, while the full demonstration still requires existing PostgreSQL/JDK runtime conditions. Acceptance target: Task 1 actual-file positive/negative cases and ordinary-profile regression pass with recorded commit evidence; this does not substitute for final Owner acceptance.
