# Single-device Smoke Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Install and launch the new demonstration APK on one explicitly selected Android device, producing formal Run/Result records and independently verifiable LOG/SCREENSHOT Evidence.

**Architecture:** Keep APK, host Agent, Backend Test Management and Evidence within existing boundaries. Deliver an independently buildable APK first, then identity, Run, Evidence, Result, Agent and integration in dependency order. This master plan gives every Task an independent test/review exit without prewriting successful results for another task.

**Tech Stack:** Backend Kotlin 2.2.21 / Spring Boot 3.5.16 / PostgreSQL / JVM 21; Agent Kotlin/JVM 21, JDK HTTP Client, Jackson/JCS; APK Java, AGP 8.7.3, Gradle 8.9, JDK 17, SDK 35. The Agent imports Backend Kotlin/BOM versions through the version catalog without Spring runtime.

**Spec:** [Accepted design](../specs/2026-09-09-single-device-smoke-design.md), fixed design Subjects 7a64d710b00c70dde3ff596c293d0fc9f3082c44 / e6f3f59b8705af9a92e6e8098cf6b6adee8863d2; [Owner record](../../governance/acceptance/records/2026-09-09-m3-smoke-design-review-001.md). Approval covers design and planning, not execution of this plan or M3 acceptance.

## Global Constraints

- APK package `com.ricezhou.vsrqg.smoke`, Activity `.SmokeActivity`; minSdk 26, compileSdk/targetSdk 35, Build Tools 34.0.0; fixed normal / assertion-failure modes with a UUID Attempt marker.
- Fixed Plan/Case: single-device-smoke / apk-launch-smoke; v1 normal, v2 explicit assertion failure; one required Case, maximum one Attempt, no automatic installation retry.
- Heartbeat 20 seconds, poll wait 20 seconds, lease 90 seconds; allocation 60 seconds, Case/Command 300 seconds, Run 600 seconds, recovery window 120 seconds. Recovery cannot exceed Case/Run deadlines; use Server time.
- LOG and UI XML inputs limited to 1 MiB; SCREENSHOT PNG to 8 MiB. Upload Sessions expire after 5 minutes and remain constrained by current leases/terminal state. Bound all process and HTTP operations by time and size.
- Separate Agent mTLS from user JWT. Reuse principal/project_assignment/Permission without duplicating role authority. Raw serials, private keys, tokens and Payload paths must not enter logs or Git.
- Reuse M1 verification of actual files; preserve ordinary Backend default INCOMPLETE. Evidence uses the TDR-025 demonstration directory without S3/Company or treating GitHub as the runtime Evidence API.
- Preserve the Run/Attempt/Result completion contract, cross-Release/Run FKs, terminal history and Verified=false. Task completion, one Case PASS or fixture execution never means Release PASS or full M3 completion.
- Backend unit tests default to `@Timeout(60)`; the real-device Case has a 300-second business deadline. Check every verification exit code, expose failures, and never mark unexecuted checks PASS.
- Reuse existing worktrees, translate Markdown and keep non-Markdown bytes identical. Independently review, pair-commit and push each Task. No merge, Tag, deployment, automatic device selection, uninstall, data clearing, flashing, power interruption or device reboot.

## Execution Rules and Interface Files

Before execution, read AGENTS.md, the design, TDR-024/025 and this plan; check HEAD/workspace and preserve uncommitted user changes. Task 1 is independent; execute 2→3→4→5→6→7 in order, with 7 also consuming 1. New implementation is disabled by default and enabled through explicit demo configuration. Reject missing dependencies explicitly, without temporary success adapters. Each file set includes tests, configuration and documentation; do not refactor unrelated modules.

The following application ports are the only cross-module interfaces introduced here. Assigned tasks own their complete definitions; consumers must not rename them or duplicate rules. JSON is limited to Schema-validated protocol boundaries and fixed context; domain states use explicit enums.

```kotlin
// Task 2: testmanagement/application/AgentAccess.kt
data class AgentActor(val principalId: String, val projectId: String,
    val agentId: String, val deviceId: String)
interface AgentAccess {
    fun requireAgent(certificateSha256: String, scope: String): AgentActor
}
// Task 3: testmanagement/application/AttemptAccess.kt
data class AttemptBinding(val attemptId: String, val runId: String,
    val releaseId: String, val projectId: String, val agentId: String,
    val deviceId: String, val leaseId: String, val fencingToken: Long)
interface AttemptAccess {
    // Caller transaction retains the attempt lock through the dependent write.
    fun lockWritable(actor: AgentActor, attemptId: String, now: Instant): AttemptBinding
    fun context(actor: AgentActor, attemptId: String): JsonNode
}
// Task 4: evidence/application/AttemptEvidence.kt
data class EvidenceResolution(val availableIds: Set<String>,
    val failedRequiredTypes: Set<String>)
interface AttemptEvidence {
    fun resolve(binding: AttemptBinding, evidenceIds: Set<String>): EvidenceResolution
    fun seal(binding: AttemptBinding, now: Instant)
}
```

`Instant` means java.time.Instant; `JsonNode` means Jackson JsonNode. The Agent does not depend on Backend JVM types; it consumes the same machine contracts and golden JSON. The single `schemas/v0.2/agent-execution-context.schema.json` defines context: schemaVersion=1.0, attemptId, commandId, projectId, releaseId, manifestId, manifestDigest, deviceId, environment{bootSessionId,buildId,buildFingerprint}, apk{checksum,packageName,versionCode,signingCertificateSha256}, plan{planId,version}, case{caseId,version,mode,timeoutMs,requiredEvidence}. All fields are required with additionalProperties=false. Digests use the sha256: prefix, technical IDs retain existing length constraints, and APK/Case constants are limited to this design.

## Task 1: Independently Buildable Minimal Demonstration APK

**Files:** Create `demo/android-smoke/settings.gradle.kts`, `build.gradle.kts`, `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar`, `gradlew`, `gradlew.bat`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/com/ricezhou/vsrqg/smoke/SmokeMarker.java`, `SmokeActivity.java`, `app/src/test/java/com/ricezhou/vsrqg/smoke/SmokeMarkerTest.java`, `README.md`. Task 1 does not touch Backend or devices.

**Interfaces:** `SmokeMarker.render(String attemptId, String mode): String`; APK output `app/build/outputs/apk/debug/app-debug.apk`. Activity extras are fixed to attemptId/mode; no network permission or services. Match complete standard UUID strings, rejecting UUID.fromString abbreviations.

- [x] **Step 1:** Check JDK 17, SDK platform 35/Build Tools 34.0.0. Report missing prerequisites explicitly without device commands. Create an independent AGP 8.7.3/Gradle 8.9 build; obtain and pin Wrapper JAR/distribution verification through official generation, without unknown binaries. Use JUnit 4.13.2 for unit tests. Write the following test before the production class.

```java
@Test public void modesAndInvalidInputRemainDistinct() {
    String id = "01990000-0000-7000-8000-000000000001";
    assertEquals("VSRQG_SMOKE_READY:" + id, SmokeMarker.render(id, "normal"));
    assertEquals("VSRQG_SMOKE_NOT_READY:" + id,
        SmokeMarker.render(id, "assertion-failure"));
    assertThrows(IllegalArgumentException.class,
        () -> SmokeMarker.render("1-1-1-1-1", "normal"));
    assertThrows(IllegalArgumentException.class,
        () -> SmokeMarker.render(id, "anything"));
}
```

- [x] **Step 2:** From `demo/android-smoke`, run `./gradlew testDebugUnitTest` (gradlew.bat on Windows); expect RED because SmokeMarker is undefined. Record toolchain failures separately; they are not this regression's RED evidence.
- [x] **Step 3:** Implement the single input function, then call it from the Activity. Invalid input displays fixed `SMOKE_INPUT_INVALID` and finishes without READY. A platform TextView displays the complete marker and SYNTHETIC_DEMO. Revalidate onNewIntent and restore the same valid parameters across rotation/recreation.

```java
public static String render(String id, String mode) {
    if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
        throw new IllegalArgumentException("SMOKE_INPUT_INVALID");
    if ("normal".equals(mode)) return "VSRQG_SMOKE_READY:" + id;
    if ("assertion-failure".equals(mode)) return "VSRQG_SMOKE_NOT_READY:" + id;
    throw new IllegalArgumentException("SMOKE_INPUT_INVALID");
}
```

- [x] **Step 4:** Run testDebugUnitTest, lintDebug and assembleDebug. Inspect the Manifest for only the target Activity and no permissions/services/extra components. Use Build Tools apksigner to verify the signature and record certificate digest, APK SHA-256/size/version. Keep private keys and local.properties outside Git. Task 7 verifies actual UI behavior on a device; unit tests do not replace that check.
- [x] **Step 5:** Complete bilingual directory READMEs covering toolchain, build, inputs/outputs and absence of device execution. Review diff, run Pair Gate, pair-commit `feat(demo): add minimal Android smoke APK`, and push.

## Task 2: Agent Identity, Registration and Context Machine Contract

**Files:** Create `backend/src/main/resources/db/migration/V12__agent_device_identity.sql`; `backend/src/main/kotlin/com/ricezhou/vsrqg/testmanagement/application/AgentAccess.kt`, `RegisterAgent.kt`; `backend/src/main/kotlin/com/ricezhou/vsrqg/testmanagement/adapter/AgentSecurityConfiguration.kt`, `JdbcAgentAccess.kt`, `AgentRegistrationController.kt`; `backend/src/test/kotlin/com/ricezhou/vsrqg/testmanagement/AgentIdentityIntegrationTest.kt`, `AgentTlsIntegrationTest.kt`; `schemas/v0.2/agent-execution-context.schema.json`; `contracts/examples/v0.2/agent/execution-context.json`, `invalid-execution-context.json`. Modify `access/domain/Permission.kt`, `access/adapter/SecurityConfig.kt`, `contracts/openapi/v0.2/openapi.json`, `contracts/examples/v0.2/validation-cases.json`, `scripts/contract-validator.mjs`, `docs/v0.2/03-api-design.md`, `08-test-agent-protocol.md`, `09-evidence-design.md`. Short filenames inherit their group's first file directory. Subsequent domain/application/adapter paths use backend/src/main/kotlin/com/ricezhou/vsrqg/, SQL uses backend/src/main/resources/db/migration/, and tests use the matching module under backend/src/test/kotlin/com/ricezhou/vsrqg/.

**Interfaces:** Produce AgentAccess/AgentActor, registration response `{protocolVersion,agentId,heartbeatIntervalSeconds:20,leaseDurationSeconds:90}` and the context Schema. Task 3 implements the Context endpoint; Task 4 implements the Payload endpoint. Update the two new OpenAPI paths/local-download Profile without extending strict old Command Payloads or weakening HIGH download controls without authorization.

- [x] **Step 1:** Follow PostgresIntegrationTest for identity fixtures: project, SERVICE principal, ENGINEER assignment, explicit Device, preregistered Agent and certificate DER SHA-256 binding. Never fill Run/Result. Test repeat registration returning the same agentId, rejection of reassignment to another Device/project, disabled/revoked identities, and 426 for no common protocol. Contract tests reject missing/unknown context fields.

```kotlin
@Test @Timeout(60)
fun `certificate request attribute cannot be replaced by a header`() {
    mockMvc.perform(post("/agent-api/v1/agents:register")
        .header("X-Client-Cert", "untrusted")
        .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isUnauthorized)
}
```

- [x] **Step 2:** Run `backend/gradlew -p backend test --tests '*AgentIdentityIntegrationTest' --tests '*AgentTlsIntegrationTest'` and `node scripts/contract-validator.mjs`; record expected RED. Blanket 401 responses do not prove successful registration is implemented.
- [x] **Step 3:** Fix agent→principal/project/device links and unique certificate fingerprints while reusing principal/project_assignment. Extend the single Permission catalog with test:execute/test:read, Evidence permissions and Agent scopes. User execution allows ENGINEER/RELEASE_MANAGER/ADMINISTRATOR; reading allows all existing project roles; sensitive Payload only QUALITY_OWNER/ADMINISTRATOR. Agent scopes require a bound SERVICE identity validated through the dedicated certificate chain. Reuse ProjectAuthorizer; no second role table.

```kotlin
// Configure this extractor in the ordered /agent-api/** X509 security chain.
class CertificateFingerprintExtractor : X509PrincipalExtractor {
    override fun extractPrincipal(certificate: X509Certificate): Any =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded))
}
// JdbcAgentAccess resolves binding, revocation and ProjectAuthorizer.require.
```

X509PrincipalExtractor uses Spring Security's x509 package interface; X509Certificate, HexFormat and MessageDigest are JDK types. Match /agent-api/** first with a stateless authenticated Agent chain and configure the DER fingerprint extractor above, not CN/DN identity. Keep the user JWT chain separate; credentials cannot be exchanged between entry points. TLS terminates at Backend; client-auth=want permits user routes without client certificates, but Agent routes require trusted certificates. Never trust proxy headers as certificates.
- [x] **Step 4:** Start an actual random-port HTTPS test server with test-only temporary CA/client certificates. Cover trusted/untrusted/expired certificates, wrong project, revocation, JWT-only Agent requests and certificate-only user requests. Assert both success and rejection, beyond MockMvc certificate injection. Run affected access unit tests and contract checks.
- [x] **Step 5:** Document both new endpoints, TDR-025 demo exception, Agent permission domain and development certificate-path configuration. Verify, pair-commit `feat(agent): register certificate-bound device agents`, preserve machine evidence and push.

Task 2 engineering verification and implementation Subjects are in the [record](../../m3/agent-identity-registration-verification.md). Local PostgreSQL initialization failure was not treated as behavioral RED. New permission and contract behavior produced RED; exact-commit CI verified database behavior.

## Task 3: Run, Attempt, Scheduling and Leases

**Files:** Create `V13__test_run_attempt_authority.sql`; `testmanagement/domain/TestStates.kt`, `LeaseWindow.kt`, `AttemptIds.kt`; `testmanagement/application/CreateTestRun.kt`, `AttemptAccess.kt`, `ClaimCommand.kt`, `AcknowledgeCommand.kt`, `HeartbeatAgent.kt`, `AdvanceTestDeadlines.kt`, `CancelTestRun.kt`; `testmanagement/adapter/JdbcTestRunRepository.kt`, `TestRunController.kt`, `AgentExecutionController.kt`, `TestDeadlineWorker.kt`; tests `TestRunIntegrationTest.kt`, `AgentLeaseIntegrationTest.kt`, `LeaseWindowTest.kt`. Kotlin/SQL/test relative paths use Task 2's Backend roots.

**Interfaces:** Produce AttemptAccess; implement Create/Cancel Run and heartbeat/poll/ACK/context. `LeaseWindow.writable(now:Instant, expiresAt:Instant, supplied:Long, current:Long, terminal:Boolean):Boolean` is the single lease predicate. Task 5 handles Results and Events. V13 also creates empty Test Result structures for deadline/cancel and Task 4 FKs, never prewritten successes.

- [x] **Step 1:** Write pure expiry-boundary tests and concurrent integration scenarios: reject unlocked input, wrong Plan and insufficient capabilities; replay identical requests; at most one active Run per Device; no duplicate Commands/Attempts on repeated poll/ACK; context only for the assigned Agent.

```kotlin
@Test @Timeout(60)
fun `lease expiry is an exclusive boundary`() {
    val expiry = Instant.parse("2026-09-09T00:01:30Z")
    assertFalse(LeaseWindow.writable(expiry, expiry, 4, 4, false))
    assertTrue(LeaseWindow.writable(expiry.minusNanos(1), expiry, 4, 4, false))
    assertFalse(LeaseWindow.writable(expiry.minusSeconds(1), expiry, 3, 4, false))
    assertFalse(LeaseWindow.writable(expiry.minusSeconds(1), expiry, 4, 4, true))
}
```

- [x] **Step 2:** Run `backend/gradlew -p backend test --tests '*TestRunIntegrationTest' --tests '*AgentLeaseIntegrationTest' --tests '*LeaseWindowTest'`; confirm RED caused by missing target behavior.
- [x] **Step 3:** Follow the existing ER for immutable Plan/Case Versions, Environment, Run, Attempt, Command/Event, Result and their FKs/unique keys. Create Run parses actual Locked Manifest content, limited to one APK and configuration scope; reject other required Artifacts. Environment bytes must match the verified CONFIG checksum; never accept an arbitrary environment-matched boolean. Fix Context under its Schema, calculate its JCS digest and transactionally persist Run, Environment, Audit/Outbox.

```kotlin
fun writable(now: Instant, expiresAt: Instant,
    supplied: Long, current: Long, terminal: Boolean): Boolean =
    !terminal && supplied == current && now.isBefore(expiresAt)
// AttemptIds.fromGenerated consumes IdGenerator.nextId("att_") once.
fun fromGenerated(value: String): String {
    require(Regex("att_[0-9a-f]{32}").matches(value))
    val h = value.removePrefix("att_")
    return "${h.take(8)}-${h.substring(8,12)}-${h.substring(12,16)}-${h.substring(16,20)}-${h.substring(20)}"
}
```

Canonical Attempt UUID is the sole persisted/API value. Formatting reuses existing UUID v7 generator output without retaining another att_ alias. Other entities keep IdGenerator's prefixed format. All references, APK markers and Results use the same Attempt UUID.
- [x] **Step 4:** Use transactional row locks and a partial unique index for device exclusivity. Poll holds no DB transaction while waiting; return null when empty and at most one command even for maxCommands>1. Lock Run→Attempt→Evidence; Heartbeat renews only the current generation. Advance test time through TimeProvider; make the worker repeatable using CAS/row locks. Deadline/cancel fences the Attempt, writes one Server terminal Result and Audit/Outbox, releases the device and closes Run in one transaction. Keep startedAt null if not started. Recovery expiry is TIMEOUT; unrecoverable identity/environment change is ERROR. Never resume installation based on possible success.
- [x] **Step 5:** Verify concurrent repeated claims, Server restart deadline reconstruction, current/expired leases, cross-project context, and Result counts/history for cancellation/timeout. Queries return existing facts without fabricated completion. Verify, pair-commit `feat(test): persist single-device runs and leases`, and push.

## Task 4: Local Evidence Upload, Download and Recovery

**Files:** Create `V14__local_evidence_sessions.sql`; `evidence/domain/EvidenceState.kt`; `evidence/application/AttemptEvidence.kt`, `EvidenceUploadService.kt`, `EvidenceDownloadService.kt`, `EvidenceReconciler.kt`; `evidence/adapter/ControlledPayloadStore.kt`, `JdbcEvidenceRepository.kt`, `EvidenceUploadController.kt`, `EvidenceQueryController.kt`, `LocalEvidenceConfiguration.kt`; tests `EvidenceUploadIntegrationTest.kt`, `ControlledPayloadStoreTest.kt`, `EvidenceDownloadIntegrationTest.kt`, `EvidenceRecoveryIntegrationTest.kt`; use Task 2 roots. Modify `docs/v0.2/09-evidence-design.md`, `03-api-design.md` and OpenAPI local-download Profile text.

**Interfaces:** Implement AttemptEvidence. `ControlledPayloadStore.write(sessionId:String, input:InputStream, limit:Long):StoredPayload`; `StoredPayload(size:Long, sha256:String)` exposes no path. `verify(sessionId:String, expected:StoredPayload):StoredPayload`; reads/writes accept only server-generated IDs. Define `class PayloadLimitExceeded : RuntimeException("PAYLOAD_LIMIT_EXCEEDED")` in the same file. Upload Create returns `{uploadId,evidenceId,uploadUrl,expiresAt}` pointing to same-Backend mTLS PUT, not a Bearer/S3 URL. Complete returns fixed Evidence Metadata.

- [x] **Step 1:** Write streaming-size and actual-file preservation tests. Integration fixtures create real Run/Attempt records then use the API without directly setting AVAILABLE. Cover cross-Agent/Run access, wrong size/hash/mediaType, empty/oversized input, identical retransmission, conflicting bytes, expired leases and cancellation/Complete races.

```kotlin
@Test @Timeout(60)
fun `an oversized stream never replaces a completed payload`() {
    val id = "upload_test_01"
    val store = ControlledPayloadStore(tempDir)
    val first = store.write(id, ByteArrayInputStream(byteArrayOf(1)), 1)
    assertThrows<PayloadLimitExceeded> {
        store.write(id, ByteArrayInputStream(byteArrayOf(1, 2)), 1)
    }
    assertEquals(first, store.verify(id, first))
}
```

- [x] **Step 2:** Run `backend/gradlew -p backend test --tests '*Evidence*IntegrationTest' --tests '*ControlledPayloadStoreTest'`; confirm RED. Blanket authorization rejection does not replace the successful complete path.
- [x] **Step 3:** Implement the existing upload state machine and composite FKs. Validate sessionId; keep roots outside repository/static directories; reject traversal, links and special files. Stream to exclusive temporary files without unbounded readAllBytes. Interrupted streams leave retryable Sessions without AVAILABLE. Finalize only after complete size/hash/type validation; compare retransmissions without overwriting different content. Complete retains the Attempt lock through Metadata/Audit/Outbox commit to exclude terminal races.

```kotlin
val digest = MessageDigest.getInstance("SHA-256")
// input/limit are write parameters; output is the exclusively opened owned temporary file stream.
var size = 0L
val buffer = ByteArray(64 * 1024)
while (true) {
    val count = input.read(buffer)
    if (count == -1) break
    size += count
    if (size > limit) throw PayloadLimitExceeded()
    digest.update(buffer, 0, count)
    output.write(buffer, 0, count)
}
```

Require strict UTF-8/text/plain LOG and the fixed PNG signature with image/png. Server-generated paths own temporary/final Payloads. File finalization followed by DB failure preserves an orphan; same-Session retry verifies original bytes before completion without claiming a distributed transaction. Expose integrity state for missing/corrupt files behind Metadata. Closed Runs receive only appended integrity observations/diagnostics; original Result/Evidence facts and digests remain unchanged.
- [x] **Step 4:** Add `evidence_download_grant` as a short-lived download-request record, not another role authority. Bind actor/project/evidence/purpose with 60-second expiry. Same-key POST :download replay is valid only before expiry; reject expired keys and require a new key. Return an authenticated Backend URL with opaque grant ID. Every GET checks current principal/project permissions, grant owner/purpose/expiry and retention/legal hold. HIGH additionally requires evidence:read:sensitive; URLs copied to other users still return 403. Document conditional permissions in OpenAPI while retaining HIGH constraints. Use no-store, Audit before streaming, no redirects/path leakage, and reject Range; this slice has no partial downloads.
- [x] **Step 5:** Verify positive/negative downloads, fail-closed Audit failures, file permissions/links, DB rollback retries, paired Metadata+Payload backup/restore and individual hash checks. Reconciliation does not scan arbitrary directories or automatically delete unowned files; output fixed IDs and diagnostics. Pair-commit `feat(evidence): store and verify bounded local payloads` and push.

## Task 5: Event, Result and Run Completion Contract

**Files:** Create `testmanagement/application/AppendCommandEvent.kt`, `SubmitAttemptResult.kt`, `GetTestRunResults.kt`; `testmanagement/domain/ResultCanonicalizer.kt`; `testmanagement/adapter/AgentResultController.kt`; tests `AttemptResultIntegrationTest.kt`, `TestRunCompletionIntegrationTest.kt`, `ResultCanonicalizerTest.kt`; `contracts/examples/v0.2/agent/result-canonical-input.json`, `result-canonical-expected.json`. Modify `JdbcTestRunRepository.kt`, `TestRunController.kt` and concrete OpenAPI Results response fields.

**Interfaces:** ResultCanonicalizer is an object; both it and its test define `private val mapper = ObjectMapper()`. `ResultCanonicalizer.digest(request:JsonNode):String`; `SubmitAttemptResult.submit(actor:AgentActor, request:JsonNode, idempotencyKey:String, requestId:String):JsonNode`. Consume AttemptAccess/AttemptEvidence. Queries return `{runId,releaseId,manifestId,manifestDigest,plan,environment,status,attempts:[{attemptId,status,result,evidenceRequirements}],inputDigest}`, distinguishing run status from Test Result status without Quality Result.

- [x] **Step 1:** Fix golden JSON from validated requests with resultDigest removed and evidenceIds sorted/deduplicated. Test equal digests for different field/Evidence order, changed digests for changed content and unchanged input, followed by repeated/conflicting/late Events/Results and required Evidence scenarios.

```kotlin
@Test @Timeout(60)
fun `digest ignores the supplied digest and preserves the request`() {
    val request = mapper.readTree("""{"attemptId":"a","evidenceIds":["b","a"],"resultDigest":"ignored"}""")
    val before = request.deepCopy<JsonNode>()
    val digest = ResultCanonicalizer.digest(request)
    val reordered = mapper.readTree("""{"evidenceIds":["a","b"],"attemptId":"a"}""")
    assertEquals(digest, ResultCanonicalizer.digest(reordered))
    assertEquals(before, request)
}
```

- [x] **Step 2:** Run `backend/gradlew -p backend test --tests '*AttemptResultIntegrationTest' --tests '*TestRunCompletionIntegrationTest' --tests '*ResultCanonicalizerTest'`; record RED. This canonical unit test covers only the function. API requests still require strict resultRequest Schema validation; the minimal function example never relaxes the API.

Execution note: the local host has no PostgreSQL container runtime, so the PG-containing command above was not run locally. Local canonical, completion-predicate and boundary regressions retain separate RED/GREEN evidence. Exact-commit CI executed real PG scenarios, preserving two failed rounds and final passing Artifacts. Task 5's engineering record gives actual commands, counts and double boundaries; compilation is not substituted for a PG pass.

- [x] **Step 3:** Reuse existing JCS: copy input, remove resultDigest, normalize evidenceIds and calculate SHA-256. The server recalculates and rejects mismatched submitted digests. Lock Run→Attempt in one transaction; validate certificate binding/fencing, terminal Result, Event sequence and Evidence set; write one Result, terminal Attempt and Audit/Outbox. Seal Sessions before Run aggregation.

```kotlin
val canonicalInput = request.deepCopy<ObjectNode>().also { node ->
    node.remove("resultDigest")
    val ids = node.withArray("evidenceIds").map { it.textValue() }.toSortedSet()
    node.putArray("evidenceIds").also { array -> ids.forEach(array::add) }
}
val bytes = JsonCanonicalizer(mapper.writeValueAsBytes(canonicalInput)).encodedUTF8
val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
```

- [x] **Step 4:** An identical terminal digest retried by the still-authorized original Agent returns the old acknowledgement without new side effects. Different digests/new writes from stale generations return 409 LATE_EVENT_CONFLICT/STALE_LEASE. Closed Runs accept no new valid Evidence. COMPLETED requires all Case Resolutions, terminal Attempts, and required Evidence AVAILABLE or explicitly failed. Preserve Agent ERROR/partial Evidence; reject PASS without required Evidence. Cancellation/deadline paths produce one Result. Test optional Cases in the general predicate under the original contract, although this slice publishes only one required Case.
- [x] **Step 5:** Inject Audit/Outbox/DB failures and verify no partial terminal state, no late writes under Complete/Cancel races, unchanged historical digests/sets and no cross-principal shared-key access. Records cite only executed checks. Pair-commit `feat(test): finalize attempts with verified evidence` and push.

## Task 6: Host Agent, ADB and Two Collectors

**Files:** Create `agent/settings.gradle.kts`, `build.gradle.kts`, Gradle wrapper files, `src/main/kotlin/com/ricezhou/vsrqg/agent/AgentMain.kt`, `AgentClient.kt`, `ExecutionJournal.kt`, `AgentLoop.kt`, `AdbExecutor.kt`, `SmokeAssertions.kt`, `CollectorPlugin.kt`, `LogCollector.kt`, `ScreenshotCollector.kt`, `ResultDigest.kt`; tests `AgentRecoveryTest.kt`, `AdbExecutorTest.kt`, `SmokeAssertionsTest.kt`, `AgentClientIntegrationTest.kt`, `ResultDigestContractTest.kt`. Reuse Backend's pinned Gradle 8.14.4 wrapper, import its catalog/BOM versions and use the same JCS library.

**Interfaces:** `AgentClient.call(method:String,path:String,body:JsonNode?,key:String?):JsonNode` permits fixed paths/same-origin HTTPS without redirects. `AgentClient.putPayload(path:String,file:Path):Unit` uploads bounded binary streams with the same response-code/origin checks. `AdbExecutor.run(arguments:List<String>,timeout:Duration,stdoutLimit:Long):CommandOutput(exitCode:Int,stdout:ByteArray,stderr:ByteArray)`; `ExecutionJournal.load(attemptId:String):JournalEntry?`, `save(entry:JournalEntry)`. JournalEntry fields attemptId/commandId/leaseId/bootSessionId are String, fencingToken/lastSequence Long, phase Phase, evidenceIds Set<String>, resultDigest String?. Phases: RECEIVED, ACKED, INSTALL_INTENT, INSTALLED, LAUNCH_INTENT, OBSERVED, UPLOADED, RESULT_ACKED. Corruption is an explicit error, never unexecuted work.

CollectorPlugin reuses designed descriptor/start/mark/collect/stop/health operations. Here `collect` returns local `EvidenceCandidate(type:String,mediaType:String,size:Long,checksum:String,capturedAt:Instant,collectorVersion:String,localFile:Path)`; localFile stays within the host, never in protocol/digests. Collectors define no quality thresholds.

- [x] **Step 1:** Write UI assertion, hostile/oversized input and journal recovery tests. Use actual bounded subprocesses for timeout, stderr and binary stdout rather than in-memory mocks replacing process boundaries. A JVM HTTPS test server verifies mTLS, same-origin URLs, upload retries and refusal to automatically follow redirects.

```kotlin
@Test fun `uncertain installation is never replayed`() {
    assertEquals(RecoveryAction.WAIT_FOR_DEADLINE,
        RecoveryPolicy.decide(Phase.INSTALL_INTENT, leaseValid = true, sameBoot = true))
    assertEquals(RecoveryAction.REPORT_ONLY,
        RecoveryPolicy.decide(Phase.UPLOADED, leaseValid = true, sameBoot = true))
    assertEquals(RecoveryAction.DIAGNOSTICS_ONLY,
        RecoveryPolicy.decide(Phase.UPLOADED, leaseValid = false, sameBoot = true))
}
```

Place `RecoveryPolicy.decide(phase:Phase,leaseValid:Boolean,sameBoot:Boolean):RecoveryAction`, Phase and RecoveryAction in AgentLoop.kt. Actions are START, WAIT_FOR_DEADLINE, REPORT_ONLY, DIAGNOSTICS_ONLY and DONE. RESULT_ACKED returns DONE; INSTALLED resumes only the launch stage whose LAUNCH_INTENT has not been written. Expired generations/changed boot permit diagnostics only. Uncertain INSTALL_INTENT/LAUNCH_INTENT stages wait for deadlines without replay. OBSERVED/UPLOADED may continue existing-data upload/report only. RECEIVED/ACKED can start actions only without an action intent. Follow Server deadlines without independently renewing an invalid lease.
- [x] **Step 2:** From agent run `./gradlew test` and confirm RED. Client validation consumes the same repository source for Task 2 Schema and Task 5 golden JSON through explicit Gradle resources, without duplicated wire definitions.
- [x] **Step 3:** Require server, controlled certificate/trust configuration references, explicit Device reference and ADB-selector config file, APK and spool in the host CLI. Never expand credential contents into logs/command lines. Reject first-device selection, unknown arguments, non-HTTPS, missing certificates and symlinked configuration/output. Pass ProcessBuilder argument lists and check exit codes. A read-limit breach terminates that owned process and reports failure, never truncated successful logs.

```kotlin
val process = ProcessBuilder(adbExecutable.toString(), "-s", selectedDevice,
    "shell", "am", "start", "-W", "-n", "com.ricezhou.vsrqg.smoke/.SmokeActivity",
    "--es", "attemptId", validatedAttemptId, "--es", "mode", validatedMode).start()
// Consume stdout/stderr concurrently with separate limits before bounded waitFor.
// On timeout terminate only this owned process; never adb kill-server.
```

- [x] **Step 4:** Verify APK bytes/signature/version against Context and check boot/build/fingerprint; do not uninstall an existing package with a different signature. After installation retrieve/reverify the sole base APK; split/unreadable installations are explicitly BLOCKED. Check the fixed foreground component. uiautomator writes/reads only this Attempt's `/data/local/tmp/vsrqg-smoke-<uuid>.xml`, never external paths. Disable external entities, cap XML at 1 MiB and exactly match current READY text. Scope logcat to the test package without clearing whole-device logs; for screenshots, first confirm foreground, use fixed `shell screencap -p` to write this Attempt's `/data/local/tmp/vsrqg-smoke-<uuid>.png`, then read bounded binary bytes (8 MiB) with `exec-out cat`; no stdout fallback, unchanged PNG validation, and exact finally cleanup preserving capture and cleanup failures. Clean only the individual temporary device file this Attempt created after complete UUID validation; no recursive deletion. All steps/Collector results retain the same Attempt.
- [x] **Step 5:** Persist the journal using an exclusive lock and temporary-file atomic replacement before ACK/execution. Allow one host Agent to operate this device. Heartbeat runs independently of the maximum 300-second Case; stop side effects on invalid Server lease. Preserve spool across network loss, disconnection, timeout and uncertain stages. Keep required data until uploads and Result are acknowledged. Process restart must not reinstall an APK in an uncertain stage.
- [x] **Step 6:** Run all Agent tests, compile/build, shared canonical vectors and actual subprocess negative cases. Runtime emits no quality thresholds or sensitive raw content; expose recovery/corrupt journals. Pair-commit `feat(agent): execute bounded Android smoke commands` and push, without claiming real-device success yet.

## Task 7: Integration, CI and Real-device Delivery

**Files:** Create `scripts/demo/run-m3.ps1`, `scripts/tests/m3-demo.tests.ps1`, `scripts/tests/fixtures/m3-demo-command.ps1`, `backend/src/demo/kotlin/com/ricezhou/vsrqg/demo/M3DemoBootstrap.kt`, `M3DemoScenario.kt`, `M3DemoReport.kt`, `docs/m3/single-device-smoke-runbook.md`, `docs/m3/single-device-smoke-verification.md`, `.github/workflows/m3-smoke.yml`. Modify `backend/build.gradle.kts` only for an independent M3 demo entry; preserve run-m1/M1DemoMain behavior.

**Interfaces:** `run-m3.ps1 -Config <path>` reads strict JSON requiring server, identityConfig, apk, deviceConfig, payloadRoot, spool, outputRoot and planVersion; reject unknown fields. Identity config contains controlled-file references, not copied credentials. `summary.json` includes schemaVersion, SYNTHETIC_DEMO, source commit/dirty, actual execution mode (CI_FIXTURE or REAL_DEVICE), Release/Manifest, Run/Attempt/Case, Test Result, Evidence IDs/size/checksum, scenario outcome and exclusions. Omit raw serials, accounts, private keys, URL tokens and local absolute paths. Report-generation success is separate from source test status.

- [x] **Step 1:** Test missing parameters, nonzero subprocess exits, absent output, existing/owned services, timeout and cleanup scope first. Reuse m1-demo.tests.ps1 native PowerShell assertions and command injection; fixtures cannot enter production Agent defaults. Missing-config checks nonzero exit, fixed CONFIG_INVALID diagnostic and absence of a report. Failures after scenario entry instead check the failure summary. Execute the same test source locally and in CI without adding a Pester compatibility layer.

```powershell
$diagnostic = & $pwsh -NoProfile -File $script -Config $invalidConfig 2>&1
if ($LASTEXITCODE -eq 0) { throw 'Expected nonzero exit' }
if (($diagnostic -join "\n") -notmatch 'CONFIG_INVALID') { throw 'Missing CONFIG_INVALID' }
if (Test-Path -LiteralPath $reportPath) { throw 'Unexpected report' }
# valid fixture scenario separately asserts Run ID, Case status and both Evidence hashes
```

Resolve pwsh through Get-Command during test initialization. Explicitly bind other variables in m3-demo.tests.ps1 initialization/per-case setup to this script and files in an owned temporary directory, never user configuration or real devices. Verify absolute cleanup targets remain inside that directory.
- [x] **Step 2:** Bootstrap only project/identity/Agent/Device/Published Plan v1/v2 definitions, separating user/Agent service identities. Use the existing actual-file verifier for APK+CONFIG; register, validate and Lock through HTTP before Run creation. Expected Case v2 FAIL counts as scenario success while the summary retains original FAIL. Never directly seed Result, Evidence AVAILABLE or change Traceability Snapshot.
- [x] **Step 3:** Reuse GitHub Actions to build APK/Agent and run targeted Backend tests/controlled protocol fixtures only. SDK/JDK preflight fails with explicit missing-prerequisite diagnostics, never silently skips. Reuse repository-pinned checkout/setup-java and other actions. Upload same-run APK digests, test XML, fixture summary and Payload samples; preserve failed-run material. Never label CI_FIXTURE as REAL_DEVICE. No self-hosted device Runner, Company or new storage services required.
- [ ] **Step 4:** After implementation instruction and before first device action, verify explicitly selected device, API Level≥26, ADB authorization and permitted install/launch scope. Owner selects among multiple devices; never choose the first automatically. Execute normal and deterministic FAIL once each. Query Run→Result→LOG/PNG, download every Payload and recompute SHA-256. Use task-owned processes/directories only; do not stop existing services, delete old results or automatically uninstall the app. Continue independent CI if device unavailable, but mark real-device checks UNKNOWN and leave delivery unchecked.
- [ ] **Step 5:** Exercise connection interruption and Agent-process restart within supported scope, without automatic device power interruption/reboot. Preserve preconditions, injection method, timing, old/new leases, terminal states and recovered bytes. Confirm no repeated installation, late writes or false PASS. Restore Server data and a Payload copy and actually verify manifests/results. Explicitly exclude unimplemented full M3 Crash/ANR/power-loss exits.
- [ ] **Step 6:** Obtain independent engineering review and verify bilingual CI/Artifacts/real-device material against exact implementation commits. New acceptance records bind new fixed implementation Subjects with initial PENDING status; this design APPROVE cannot be reused. Separate product/record commits. Pass contract/acceptance validation and Pair Gate, atomically push and verify remote HEADs. Record only executed checks without claiming full M3/Company completion.

## Plan Self-review and Next Step

Coverage: APK/Identity/input validation→1/2/6; fixed Release/Plan/Environment and Lease/Recovery→3; Evidence upload/download/backup→4; Result digest/idempotency/Run completion→5; actual processes/logs/screenshots→6; CI/real-device distinction, integration and independent acceptance→7. Interface sections/assigned Tasks define cross-task types. Self-review checks for temporary success adapters or extra business authority.

Engineering checks for Tasks 1 and 2 are recorded in [build verification](../../m3/minimal-apk-build-verification.md) and [identity and registration verification](../../m3/agent-identity-registration-verification.md). Task 3 implementation and evidence are in [Run and lease verification](../../m3/run-lease-verification.md). Task 4 implementation, independent reviews and exact-commit CI are complete; actual evidence is in the [local Evidence engineering record](../../m3/local-evidence-verification.md). Task 5 implementation, independent reviews and exact-commit CI/Artifact verification are complete; actual evidence is in the [Event and Result engineering record](../../m3/attempt-result-verification.md). Task 6 host Agent implementation, tests/build and independent reviews are complete; exact delivery status is in the [host Agent engineering record](../../m3/host-agent-verification.md). See the [integration engineering record](../../m3/single-device-smoke-verification.md) for actual Task 7 integration, exact CI and real-device delivery status. Corresponding Tasks perform real-device checks, which server or document checks cannot replace. Failed platform assumptions are reported explicitly without weakening identity or success conditions.

The current result, Git status, sole next action, prerequisites and acceptance target are maintained in the [current engineering record](../../m3/single-device-smoke-verification.md). Original design approval does not replace subsequent implementation instructions or Owner acceptance.
