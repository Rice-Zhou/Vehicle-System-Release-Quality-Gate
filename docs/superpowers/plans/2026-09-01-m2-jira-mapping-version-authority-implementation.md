# M2.2 Jira Mapping Profile and Adapter Version Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Without changing the frozen V0.1 architecture, implement immutable Project/Issue Source-scoped Jira Mapping Profiles, code as the sole Adapter Version Authority, and deterministic version pinning with fail-closed execution for every Sync Run.

**Architecture:** In the existing Kotlin/Spring Boot modular monolith, PostgreSQL `issue_mapping_profile` is authoritative for mapping content and code `IssueSourceRuntimeDescriptor` is authoritative for Adapter Version. The authenticated activation transaction and `StartIssueSync` use the same Source row lock. The Worker creates an `IssueSourcePort` only after Descriptor, Run, and Profile Project/Source/Schema/digest/version all agree, while `RunIssueSync` continues to depend only on the Port.

**Tech Stack:** Kotlin 2.3, Java 21, Spring Boot 4, Spring JDBC/Transaction, PostgreSQL 17, Flyway, Jackson, RFC 8785 JCS, JUnit 5, AssertJ, Testcontainers, OpenAPI 3.1, PowerShell.

---

## Delivery Boundary and File Structure

This plan is based on approved `M2-KD-2026-09-01-01` and Accepted `TDR-015`. Plan approval authorizes only the tasks below. Real Jira queries, Jira writes, Company, Task 5, merging `main`/`release`, Tag, release, and production deployment still require independent authorization.

File responsibilities are fixed as follows:

- `IssueMappingProfile.kt`: Application data types, Codec/Repository ports, and fixed failure types.
- `JcsIssueMappingProfileCodec.kt`: strict structural validation, Unicode Token normalization, RFC 8785 canonicalization, SHA-256, and compiled mappings.
- `JdbcIssueMappingProfileRepository.kt`: Source row lock, immutable Profile write/read, and active selector.
- `ActivateIssueMappingProfile.kt`: Project authorization, Idempotency, transaction, Audit, and Outbox.
- `IssueMappingProfileController.kt`: the sole activation API and fixed 422 Problem Details.
- `JiraIssueMapper.kt`: Profile-pinned exact status/severity mapping.
- `IssueSourceRuntime.kt`: Descriptor, Factory, Registry, and pre-runtime version/integrity Gate.
- `JiraCliPilotAdapter.kt`: transport, parsing, and calls to the pinned Mapper only; no hard-coded Map.

Execution order is fixed as Task 1 through 8. Every task starts RED, reaches minimal GREEN, runs targeted regression, and creates one meaningful commit.

### Task 1: API Contract and `issue:configure` Permission

**Files:**
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/access/domain/Permission.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/access/PermissionMatrixTest.kt`
- Modify: `contracts/openapi/v0.2/openapi.json`
- Modify: `contracts/openapi/v0.2/compatibility-baseline.json`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/M2ApiContractTest.kt`

- [ ] **Step 1: Write failing Permission and Contract assertions**

Add the following permission assertion to `PermissionMatrixTest` and the new operation to `M2ApiContractTest.APPROVED_OPERATIONS`:

```kotlin
assertThat(Permission.ISSUE_CONFIGURE.isAllowedFor(role)).isEqualTo(
    role in setOf(ProjectRole.RELEASE_MANAGER, ProjectRole.ADMINISTRATOR),
)

ApprovedOperation(
    "post",
    "/api/v1/issue-sources/{sourceId}/mapping-profiles:activate",
    "issue:configure",
    write = true,
)
```

Add a test that exactly asserts request schema `additionalProperties=false`, all six required fields, no `definition` in the response, and explicit rejection of `mappingVersion` and `adapterVersion`.

- [ ] **Step 2: Run RED tests**

Run: `pnpm run test:contracts; ./backend/gradlew -p backend test --tests '*PermissionMatrixTest' --tests '*M2ApiContractTest'`

Expected: FAIL because `ISSUE_CONFIGURE`, the activation operation, and request/response schemas are missing.

- [ ] **Step 3: Add the minimal permission and OpenAPI Contract**

```kotlin
ISSUE_CONFIGURE(
    "issue:configure",
    setOf(ProjectRole.RELEASE_MANAGER, ProjectRole.ADMINISTRATOR),
),
```

Add a synchronous `201` OpenAPI operation, `Idempotency-Key`, OIDC scope, and strict Schema:

```json
{
  "schemaVersion": "jira-mapping-profile/v1",
  "normalizationVersion": "unicode-nfc-trim-root-lower/v1",
  "unknownStatusPolicy": "MAP_TO_UNKNOWN_WITH_WARNING",
  "unknownSeverityPolicy": "MAP_TO_UNKNOWN_WITH_WARNING",
  "statusAliases": { "OPEN": ["synthetic-open"] },
  "severityAliases": { "HIGH": ["synthetic-high"] }
}
```

Set status/severity Alias arrays to `maxItems=256`, Tokens to `maxLength=120`, and the Profile request to `maxProperties=6`. The response contains only `profileId`, `sourceId`, `schemaVersion`, `mappingVersion`, and `activatedAt`.

- [ ] **Step 4: Verify and commit**

Run: `pnpm run test:contracts; ./backend/gradlew -p backend test --tests '*PermissionMatrixTest' --tests '*M2ApiContractTest'`

Expected: contracts `operations=33`; targeted Gradle tests PASS.

Commit: `feat(m2): define mapping profile activation contract`

### Task 2: V5 PostgreSQL Immutable Mapping Authority

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__issue_mapping_profile.sql`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/M2MigrationConstraintTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/IssueMappingProfileMigrationTest.kt`

- [ ] **Step 1: Write failing Migration tests**

Tests must assert the exact table and columns; `UNIQUE(source_id, mapping_version)`; composite Source/Project FK; `created_by` principal FK; digest CHECK; UPDATE/DELETE Trigger; cross-Project insert rejection; identical content belonging independently to two Sources; V4-to-V5, clean, and repeat migration; and no historical-row changes.

- [ ] **Step 2: Run RED tests**

Run: `./backend/gradlew -p backend test --tests '*IssueMappingProfileMigrationTest' --tests '*M2MigrationConstraintTest'`

Expected: FAIL with first missing object `issue_mapping_profile` and current Flyway version `4`.

- [ ] **Step 3: Create forward-only V5**

```sql
CREATE TABLE issue_mapping_profile (
    id varchar(40) PRIMARY KEY,
    project_id varchar(40) NOT NULL,
    source_id varchar(40) NOT NULL,
    schema_version varchar(80) NOT NULL,
    mapping_version varchar(80) NOT NULL,
    definition jsonb NOT NULL,
    created_by varchar(40) NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT uq_mapping_profile_source_version UNIQUE (source_id, mapping_version),
    CONSTRAINT fk_mapping_profile_source_project FOREIGN KEY (source_id, project_id)
        REFERENCES issue_source(id, project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_mapping_profile_creator FOREIGN KEY (created_by)
        REFERENCES principal(id) ON DELETE RESTRICT,
    CONSTRAINT ck_mapping_profile_version
        CHECK (mapping_version ~ '^sha256:[0-9a-f]{64}$'),
    CONSTRAINT ck_mapping_profile_definition_object
        CHECK (jsonb_typeof(definition) = 'object')
);

CREATE INDEX ix_mapping_profile_project_source_created
    ON issue_mapping_profile(project_id, source_id, created_at DESC);

CREATE TRIGGER immutable_issue_mapping_profile
    BEFORE UPDATE OR DELETE ON issue_mapping_profile
    FOR EACH ROW EXECUTE FUNCTION reject_immutable_write();
```

Do not seed Profiles, rewrite `issue_source.mapping_version`, or modify historical Syncs/Revisions/Snapshots.

- [ ] **Step 4: Verify Migration and commit**

Run: `./backend/gradlew -p backend test --tests '*IssueMappingProfileMigrationTest' --tests '*M2MigrationConstraintTest'`

Expected: V5 clean/upgrade/repeat, constraint, immutability, and history-protection tests PASS.

Commit: `feat(m2): add immutable mapping profile authority`

### Task 3: Deterministic Profile Codec and Mapper Contract

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueMappingProfile.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JcsIssueMappingProfileCodec.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueMappingProfileCodecTest.kt`

- [ ] **Step 1: Write Profile/Codec data contracts and failing tests**

```kotlin
data class CompiledIssueMappingProfile(
    val schemaVersion: String,
    val mappingVersion: String,
    val definition: JsonNode,
    val statusByToken: Map<String, IssueStatus>,
    val severityByToken: Map<String, IssueSeverity>,
)

fun interface IssueMappingProfileCodec {
    fun compile(definition: JsonNode): CompiledIssueMappingProfile
}

class MappingProfileInvalid(val violationCodes: List<String>) : RuntimeException("MAPPING_PROFILE_INVALID")
```

Cover three digest replays; object-field order not affecting digest; NFC, surrounding Unicode whitespace, and `Locale.ROOT`; normalized Alias conflicts; `UNKNOWN` targets; unknown fields/Schema/policy; blank Token, control characters, 121-character Token, 257 Aliases per category, and definition over 64 KiB; regex/wildcards receiving no special semantics; and no input Token in exception, message, or violations.

- [ ] **Step 2: Run RED tests**

Run: `./backend/gradlew -p backend test --tests '*IssueMappingProfileCodecTest'`

Expected: FAIL because Profile types and Codec do not exist.

- [ ] **Step 3: Implement the strict Codec**

```kotlin
internal fun normalizeMappingToken(raw: String): String {
    if (raw.isBlank() || raw.length > 120 || raw.any(Char::isISOControl)) {
        throw MappingProfileInvalid(listOf("TOKEN_INVALID"))
    }
    return Normalizer.normalize(raw, Normalizer.Form.NFC)
        .trim(Char::isWhitespace)
        .lowercase(Locale.ROOT)
        .ifBlank { throw MappingProfileInvalid(listOf("TOKEN_INVALID")) }
}

private fun digest(definition: JsonNode): Pair<ByteArray, String> {
    val serialized = objectMapper.writeValueAsBytes(definition)
    if (serialized.size > 64 * 1024) throw MappingProfileInvalid(listOf("PROFILE_TOO_LARGE"))
    val canonical = JsonCanonicalizer(serialized).encodedUTF8
    val hex = MessageDigest.getInstance("SHA-256").digest(canonical)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    return canonical to "sha256:$hex"
}
```

The Codec accepts only the six fixed fields, only non-`UNKNOWN` targets in `IssueStatus`/`IssueSeverity`, and rejects the entire Profile on collision. It assigns no regex, wildcard, prefix, contains, or fuzzy semantics.

- [ ] **Step 4: Verify and commit**

Run: `./backend/gradlew -p backend test --tests '*IssueMappingProfileCodecTest'`

Expected: all deterministic, boundary, and redaction tests PASS.

Commit: `feat(m2): compile deterministic issue mappings`

### Task 4: Authenticated Activation Transaction

**Files:**
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueMappingProfile.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/ActivateIssueMappingProfile.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSourceRuntime.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JdbcIssueMappingProfileRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueMappingProfileController.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/problem/ProblemHandler.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueMappingProfileActivationIntegrationTest.kt`

- [ ] **Step 1: Write failing Application/HTTP/transaction tests**

Cover RELEASE_MANAGER/ADMINISTRATOR success, ENGINEER/`issue:sync` rejection, cross-Project rejection, request-version-field injection returning fixed 422, invalid Profile returning fixed 422, same Idempotency-Key/same-request replay, same key/different-request 409, duplicate Source Type Descriptor startup failure, atomic Profile insert + both version selectors + Audit + Outbox, and complete rollback preserving the old selectors when Audit/Outbox fails.

- [ ] **Step 2: Run RED tests**

Run: `./backend/gradlew -p backend test --tests '*IssueMappingProfileActivationIntegrationTest'`

Expected: FAIL because the activation service, repository, and controller are absent.

- [ ] **Step 3: Implement ports and transaction service**

```kotlin
data class IssueSourceRuntimeDescriptor(
    val sourceType: String,
    val adapterId: String,
    val adapterVersion: String,
    val supportedMappingSchemas: Set<String>,
    val supportedTransportRange: String,
)

fun interface IssueSourceDescriptorRegistry {
    fun require(sourceType: String): IssueSourceRuntimeDescriptor
}

internal val JIRA_CLI_PILOT_DESCRIPTOR = IssueSourceRuntimeDescriptor(
    sourceType = "JIRA",
    adapterId = "jira-cli-pilot",
    adapterVersion = "jira-cli-pilot-adapter-v1",
    supportedMappingSchemas = setOf("jira-mapping-profile/v1"),
    supportedTransportRange = "jira-cli/1.7.x",
)

@Component
class FixedIssueSourceDescriptorRegistry : IssueSourceDescriptorRegistry {
    private val descriptors = listOf(JIRA_CLI_PILOT_DESCRIPTOR)
    private val bySourceType = descriptors.associateBy { it.sourceType }

    init {
        require(bySourceType.size == descriptors.size) { "DUPLICATE_ISSUE_SOURCE_DESCRIPTOR" }
    }

    override fun require(sourceType: String): IssueSourceRuntimeDescriptor =
        bySourceType[sourceType] ?: throw ResourceConflict(
            "ADAPTER_NOT_CONFIGURED",
            "Issue source adapter is not configured",
            "No adapter descriptor is configured for this source type",
        )
}
```

```kotlin
data class ActivateIssueMappingProfileCommand(
    val principal: Principal,
    val sourceId: String,
    val idempotencyKey: String,
    val definition: JsonNode,
    val requestId: String,
)

data class ActivateIssueMappingProfileResult(
    val profileId: String,
    val sourceId: String,
    val schemaVersion: String,
    val mappingVersion: String,
    val activatedAt: Instant,
)

interface IssueMappingProfileRepository {
    fun findSource(sourceId: String): IssueSourceRecord?
    fun lockSource(sourceId: String): IssueSourceRecord?
    fun insert(profile: IssueMappingProfileRecord)
    fun activate(sourceId: String, adapterVersion: String, mappingVersion: String, activatedAt: Instant)
    fun find(sourceId: String, mappingVersion: String): IssueMappingProfileRecord?
}
```

`IssueSourceRuntime.kt` first defines Descriptor and a read-only `IssueSourceDescriptorRegistry`; the sole Jira Descriptor version is `jira-cli-pilot-adapter-v1`, and duplicate Source Type Descriptors must fail ApplicationContext startup. `ActivateIssueMappingProfile.activate` uses this fixed order: find Source → Project authorize `ISSUE_CONFIGURE` → compile → IdempotentExecutor → lock Source and recheck Project → obtain the sole code Descriptor by Source Type → insert (`ON CONFLICT DO NOTHING`, then read exact record and compare definition) → update Adapter/Mapping selectors together → append Audit → append Outbox → response. The whole method uses `@Transactional`; Audit/Outbox/response contain only ID, Schema, Adapter Version, and Mapping Version.

- [ ] **Step 4: Implement fixed 422 and Controller**

```kotlin
@PostMapping("/api/v1/issue-sources/{sourceId}/mapping-profiles:activate")
@PreAuthorize("hasAuthority('SCOPE_issue:configure')")
fun activate(
    @AuthenticationPrincipal jwt: Jwt,
    @PathVariable @Size(min = 1, max = 40) sourceId: String,
    @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) idempotencyKey: String,
    @RequestBody definition: JsonNode,
    request: HttpServletRequest,
): ResponseEntity<ActivateIssueMappingProfileResult>
```

`ProblemHandler` maps `MappingProfileInvalid` to `422 MAPPING_PROFILE_INVALID`, emitting only fixed violation codes and no definition, Alias, or Token.

- [ ] **Step 5: Verify and commit**

Run: `./backend/gradlew -p backend test --tests '*IssueMappingProfileActivationIntegrationTest' --tests '*PermissionMatrixTest'`

Expected: authorization, idempotency, atomic rollback, and redaction tests PASS.

Commit: `feat(m2): activate mapping profiles transactionally`

### Task 5: Profile-pinned `JiraIssueMapper` and Adapter Descriptor

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JiraIssueMapper.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSourceRuntime.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JiraCliPilotAdapter.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JiraCliPilotProperties.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/JiraCliPilotAdapterTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/JiraIssueMapperTest.kt`

- [ ] **Step 1: Write Mapper and Descriptor RED tests**

Test exact matching, unknown status/severity Warnings, Profile mappingVersion propagation into Issue/Page, the established Descriptor's sole version `jira-cli-pilot-adapter-v1`, supported Schema `jira-mapping-profile/v1`, and absence of `issue-mapping-v1` or hard-coded status/severity Tokens from the Adapter file.

- [ ] **Step 2: Run RED tests**

Run: `./backend/gradlew -p backend test --tests '*JiraIssueMapperTest' --tests '*JiraCliPilotAdapterTest'`

Expected: FAIL because Mapper/Descriptor are missing and the old hard-coded Map remains.

- [ ] **Step 3: Implement Mapper and Descriptor contract**

```kotlin
interface IssueSourceRuntimeFactory {
    val descriptor: IssueSourceRuntimeDescriptor
    fun open(profile: CompiledIssueMappingProfile): IssueSourcePort
}

class JiraIssueMapper(private val profile: CompiledIssueMappingProfile) {
    fun status(raw: String): Pair<IssueStatus, IssueMappingWarning?> =
        profile.statusByToken[normalizeMappingToken(raw)]?.let { it to null }
            ?: (IssueStatus.UNKNOWN to IssueMappingWarning.UNKNOWN_STATUS)

    fun severity(raw: String): Pair<IssueSeverity, IssueMappingWarning?> =
        profile.severityByToken[normalizeMappingToken(raw)]?.let { it to null }
            ?: (IssueSeverity.UNKNOWN to IssueMappingWarning.UNKNOWN_SEVERITY)
}

class JiraCliPilotRuntimeFactory(
    private val properties: JiraCliPilotProperties,
    private val processRunner: JiraProcessRunner,
) : IssueSourceRuntimeFactory {
    override val descriptor = JIRA_CLI_PILOT_DESCRIPTOR

    override fun open(profile: CompiledIssueMappingProfile): IssueSourcePort =
        JiraCliPilotAdapter(properties, processRunner, JiraIssueMapper(profile), profile.mappingVersion)
}
```

`JiraCliPilotRuntimeFactory.descriptor.adapterVersion` is the sole constant. `open` pins a new `JiraIssueMapper` to the Profile before creating the Adapter. The CLI executable version remains Transport Version, separate from Adapter Version.

- [ ] **Step 4: Remove hard-coded Adapter Map and regress transport**

Add `mapper` and `mappingVersion` constructor parameters to `JiraCliPilotAdapter`; `parseOutput` rejects raw status/severity over 120 characters before calling the Mapper; `normalize` only calls the Mapper; `IssuePage.mappingVersion` and `NormalizedIssue.mappingVersion` both use the pinned Profile. Retain existing argv, five-column, UTF-8, byte-limit, timeout, process-tree cleanup, and read-only tests.

- [ ] **Step 5: Verify and commit**

Run: `./backend/gradlew -p backend test --tests '*JiraIssueMapperTest' --tests '*JiraCliPilotAdapterTest' --tests '*IssueSourceContractTest'`

Expected: Mapper, Descriptor, transport, and shared Contract tests PASS.

Commit: `refactor(m2): pin Jira adapter to mapping profile`

### Task 6: Runtime Registry and Pre-process Fail-closed Gate

**Files:**
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSourceRuntime.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueMappingProfile.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSyncJobWorker.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JiraCliPilotProperties.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSourceRuntimeRegistryTest.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSyncIntegrationTest.kt`

- [ ] **Step 1: Write five fixed-failure and zero-Process-call tests**

Create missing Profile, digest tampering, unsupported Schema, Adapter Version mismatch, and Mapping Version mismatch separately. Every case asserts a fixed code, Run/Job `FAILED`, zero Jira Process Runner calls, unchanged successful Cursor, and no hard-coded fallback.

- [ ] **Step 2: Run RED tests**

Run: `./backend/gradlew -p backend test --tests '*IssueSourceRuntimeRegistryTest' --tests '*IssueSyncIntegrationTest'`

Expected: FAIL because the Worker still selects a Port from global `ObjectProvider<IssueSourcePort>`.

- [ ] **Step 3: Implement the Registry Gate**

```kotlin
enum class IssueRuntimeFailureCode {
    MAPPING_PROFILE_NOT_CONFIGURED,
    MAPPING_PROFILE_INTEGRITY_FAILED,
    MAPPING_SCHEMA_UNSUPPORTED,
    ADAPTER_VERSION_MISMATCH,
    MAPPING_VERSION_MISMATCH,
}

class IssueRuntimeConfigurationException(val code: IssueRuntimeFailureCode) : RuntimeException(code.name)

interface IssueSourceRuntimeRegistry {
    fun open(run: IssueSyncRunRecord): IssueSourcePort
}
```

Registry validation order is fixed: load Source by Run sourceId and verify Project → select exactly one Factory by `sourceType` → compare Descriptor adapterVersion with Run → load Profile by `(sourceId, mappingVersion)` → compare Profile Project/Source with Run → recompute digest through Codec → check Schema support → compare compiled mappingVersion with Run → `factory.open(profile)`. Every failure occurs before `open` returns.

- [ ] **Step 4: Make Worker use the Run-pinned Registry**

```kotlin
val run = repository.findRun(job.syncRunId)
    ?: throw IllegalStateException("ISSUE_SYNC_RUN_NOT_FOUND")
val source = try {
    runtimeRegistry.open(run)
} catch (failure: IssueRuntimeConfigurationException) {
    repository.markFailed(job.syncRunId, failure.code.name)
    repository.markJobFailed(job.jobId, failure.code.name)
    return true
}
val result = runIssueSync.run(job.syncRunId, source)
```

Remove Worker `ObjectProvider<IssueSourcePort>` and the ambiguous `ADAPTER_NOT_CONFIGURED` fallback.

- [ ] **Step 5: Verify and commit**

Run: `./backend/gradlew -p backend test --tests '*IssueSourceRuntimeRegistryTest' --tests '*IssueSyncIntegrationTest' --tests '*JiraCliPilotAdapterTest'`

Expected: five fixed diagnostics, zero Process calls, Cursor preservation, and success-path tests PASS.

Commit: `feat(m2): enforce runtime version authority`

### Task 7: Source Lock, Run Version Pinning, and A/B Race

**Files:**
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueSyncRepository.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JdbcIssueSyncRepository.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/StartIssueSync.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSyncIntegrationTest.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueMappingProfileActivationIntegrationTest.kt`

- [ ] **Step 1: Write concurrent RED tests**

Use two independent transactions and latches to prove that `StartIssueSync` waits while activation holds the Source lock; Run A remains pinned to Profile A after B activates; new Run B pins Profile B; Adapter Version always comes from the Source snapshot corresponding to the Descriptor; and old `normalized_issue` digest remains unchanged.

- [ ] **Step 2: Run RED tests**

Run: `./backend/gradlew -p backend test --tests '*IssueSyncIntegrationTest' --tests '*IssueMappingProfileActivationIntegrationTest'`

Expected: FAIL because `StartIssueSync` still uses an unlocked `findSource` snapshot.

- [ ] **Step 3: Pin Source inside the Run-creation transaction**

```kotlin
fun lockSource(sourceId: String): IssueSourceRecord?

private fun lockAuthorizedSource(command: StartIssueSyncCommand, authorizedProjectId: String): IssueSourceRecord {
    val source = repository.lockSource(command.sourceId)
        ?: throw sourceNotFound(command.sourceId)
    if (!source.enabled) throw sourceDisabled(command.sourceId)
    if (source.projectId != authorizedProjectId) throw AccessDeniedException("ACCESS_DENIED")
    return source
}
```

`createAuthorized` calls `lockAuthorizedSource` before generating IDs and inserting the Run; existing Audit, Outbox, and Job writes remain on their current single path. Construction of the existing `IssueSyncRunRecord` copies `adapterVersion`/`mappingVersion` from the locked Source. `JdbcIssueSyncRepository.lockSource` uses `SELECT ... FROM issue_source WHERE id=:sourceId FOR UPDATE`. The initial unlocked read exists only to determine Project authorization.

- [ ] **Step 4: Verify race, history, and regression**

Run: `./backend/gradlew -p backend test --tests '*IssueSyncIntegrationTest' --tests '*IssueMappingProfileActivationIntegrationTest' --tests '*M2MigrationConstraintTest'`

Expected: A/B race, transaction atomicity, historical digest, and old M2 tests PASS.

Commit: `fix(m2): pin sync versions under source lock`

### Task 8: Security Regression, Full Gate, and Acceptance Candidate

**Files:**
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSourceContractTest.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/JiraCliPilotAdapterTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueMappingSecurityTest.kt`
- Create after fixed implementation commits: `docs/governance/acceptance/records/2026-09-01-m2-2-mapping-authority-001.md`

- [ ] **Step 1: Complete security and Fixture regression tests**

Fixtures use only synthetic Aliases such as `synthetic-open` and `synthetic-high`. Security tests collect exceptions, Problem Details, Audit, Outbox, Job result, and application logs and assert the absence of definition, Alias Token, Issue title, URL, CLI path, stdout/stderr, and Credential. Only profile ID, Schema, Mapping Version, and fixed diagnostics are allowed.

- [ ] **Step 2: Run targeted and full Backend Gate**

Run: `pnpm run test:contracts; pnpm run test:acceptance; pnpm run verify:acceptance; ./backend/gradlew -p backend clean test bootJar`

Expected: contracts `operations=33`; acceptance tests/records PASS; all Gradle tests and `bootJar` PASS.

- [ ] **Step 3: Run governance, sensitive scan, and diff review**

Run: `./scripts/verify-design-governance.ps1; git diff --check; rg -n 'jira-cli-pilot-v1|issue-mapping-v1|synthetic-open|synthetic-high' backend/src/main backend/src/main/resources`

Expected: governance PASS; clean diff; no old manually named version or old hard-coded mapping; synthetic Aliases appear only in tests and never in main/resources.

- [ ] **Step 4: Commit the implementation candidate**

Commit: `test(m2): gate mapping profile authority`

Record the fixed Chinese and English implementation heads with `git rev-parse HEAD`. After pushing, run `scripts/verify-language-branches.ps1 -Mode Pair` against both exact SHAs. Only after Pair Gate and both GitHub Actions succeed, copy `docs/governance/acceptance/template.md` to create the PENDING `M2-2-MAPPING-AUTHORITY-001` record. Subject Commit uses the newly resolved exact SHA, never an abbreviation or self-reference.

- [ ] **Step 5: Submit the acceptance candidate without prefilling Owner decision**

The acceptance record lists all eight implementation commits, Contract/Gradle summary, V5 Migration, five zero-Process-call failures, A/B race, security scan, Pair Gate, both CI Run/Artifact locators, and residual risks. Metadata remains exactly `status: PENDING`, `owner: PENDING`, and `decisionAt: PENDING`; implementation completion is never written directly as `APPROVE`.

Commit: `docs(acceptance): submit M2.2 mapping authority gate`

## Spec Coverage Matrix

| Spec requirement | Implementation tasks | Verification |
|---|---|---|
| Immutable Project/Source Profile Authority | Task 2, 4 | V5 constraint and activation integration tests |
| RFC 8785, SHA-256, and Unicode determinism | Task 3 | Codec replay/bound/conflict tests |
| Code as sole Adapter Version authority | Task 4, 5, 6 | Descriptor, selector, and mismatch tests |
| Authenticated, idempotent, atomic activation | Task 1, 4 | Permission, HTTP, and Audit/Outbox rollback tests |
| Run version pinning and A/B race | Task 6, 7 | Registry zero-call and concurrent integration tests |
| Visible unknowns and no success fallback | Task 3, 5, 6 | Mapper Warning and fixed diagnostic tests |
| Migration, rollback, and history protection | Task 2, 7 | V4-to-V5, immutable, and digest regression tests |
| Security, deployment, and acceptance Evidence | Task 8 | full Gate, scan, Pair Gate, CI, and PENDING record |

## Implementation Completion Boundary

Completing this plan only makes the `M2-2-MAPPING-AUTHORITY-001` candidate ready for Owner acceptance. It does not automatically close `M2-2-JIRA-E2E-SMOKE-001`. Closure still requires separately authorized, single-project, read-only real Jira retest of at most 20 records with zero status Mapping Warnings. Any new unknown status keeps the result `CONDITIONAL` and creates a new Profile version without modifying old Profiles or historical Runs.
