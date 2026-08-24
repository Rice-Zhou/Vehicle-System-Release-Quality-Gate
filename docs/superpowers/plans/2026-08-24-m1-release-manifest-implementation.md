# M1 Release Identity and Manifest Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver runnable, testable, and recoverable Release Identity and Manifest Authority in four weeks, with an M1 evidence package the Owner can accept independently.

**Architecture:** One Spring Boot deployable unit forms a Modular Monolith through the `access`, `release`, `manifest`, and `shared` packages. Domain/Application code does not depend on Web/JDBC. PostgreSQL is the only structured source of truth, and every high-risk write commits business data, Audit, and Outbox in one transaction.

**Tech Stack:** Java 21 LTS, Kotlin 2.2.21, Spring Boot 3.5.16, Gradle 8.14.4, PostgreSQL 17.11, Flyway (Boot BOM managed), Testcontainers (Boot BOM managed), ArchUnit 1.4.2, networknt JSON Schema Validator 2.0.4, java-json-canonicalization 1.1, and Node canonicalize 4.0.0.

---

## 0. Execution Rules and File Structure

Execute only on a new M1 implementation branch. Never write production code directly on `main`, `release`, or the design-specification branches. Each Task produces one explainable Commit. The verification script constructs its Artifact directory from the actual `gate` and `git rev-parse HEAD`; upload `evidence.json` only as a CI Artifact and never commit it.

```text
backend/
  build.gradle.kts
  settings.gradle.kts
  gradle/libs.versions.toml
  gradle/wrapper/*
  src/main/kotlin/com/ricezhou/vsrqg/
    VsrqgApplication.kt
    shared/{id,problem,time,web}/
    access/{domain,application,adapter}/
    release/{domain,application,adapter}/
    manifest/{domain,application,adapter}/
  src/main/resources/{application.yml,application-dev.yml,db/migration/}
  src/test/kotlin/com/ricezhou/vsrqg/
  src/test/resources/
deploy/dev/compose.yml
scripts/m1/{verify.ps1,export-schema.ps1,acceptance-smoke.ps1}
docs/m1/{runbook.md,acceptance-checklist.md,evidence-index.md}
```

Version basis: Spring Boot 3.5.16 supports Java 17–25 and Gradle 8.4+; Gradle 8.14.4 is in the supported 8.x line; PostgreSQL 17 is supported through 2029; RFC 8785 lists Java and JavaScript reference implementations. A version upgrade is a separate Commit and re-runs the full M1 Gate.

### Task 1: M1.0 Engineering Skeleton and Bootable Application

**Files:**
- Create: `backend/settings.gradle.kts`
- Create: `backend/build.gradle.kts`
- Create: `backend/gradle/libs.versions.toml`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/ApplicationContextTest.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/VsrqgApplication.kt`
- Create: `backend/src/main/resources/application.yml`

- [ ] **Step 1: Write the failing Context Test**

```kotlin
@SpringBootTest
class ApplicationContextTest {
    @Test fun `context loads`() = Unit
}
```

- [ ] **Step 2: Create pinned build files and prove red**

```toml
# backend/gradle/libs.versions.toml
[versions]
kotlin = "2.2.21"
spring-boot = "3.5.16"
archunit = "1.4.2"

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-spring = { id = "org.jetbrains.kotlin.plugin.spring", version.ref = "kotlin" }
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
```

```kotlin
// backend/settings.gradle.kts
pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "vsrqg-backend"
```

```kotlin
// backend/build.gradle.kts
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
tasks.test { useJUnitPlatform() }
```

Run: `cd backend; ./gradlew test --tests ApplicationContextTest`
Expected: FAIL because `VsrqgApplication` does not exist.

- [ ] **Step 3: Add the minimum application and secure defaults**

```kotlin
@SpringBootApplication
class VsrqgApplication
fun main(args: Array<String>) = runApplication<VsrqgApplication>(*args)
```

```yaml
spring:
  application.name: vsrqg
  profiles.default: prod
management.endpoints.web.exposure.include: health,info
management.endpoint.health.probes.enabled: true
```

- [ ] **Step 4: Generate Wrapper and prove green**

Run: `cd backend; gradle wrapper --gradle-version 8.14.4; ./gradlew clean test bootJar`
Expected: PASS and produce `backend/build/libs/*.jar`.

- [ ] **Step 5: Commit**

```powershell
git add backend
git commit -m "build(m1): establish backend skeleton"
```

### Task 2: M1.0 Module Boundaries and CI Gate

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/{access,release,manifest,shared}/PackageMarker.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/ArchitectureTest.kt`
- Create: `.github/workflows/m1-backend.yml`

- [ ] **Step 1: Write the failing ArchUnit Test**

```kotlin
@AnalyzeClasses(packages = ["com.ricezhou.vsrqg"])
class ArchitectureTest {
    @ArchTest val domainIsFrameworkFree = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "..adapter..")
}
```

Run: `cd backend; ./gradlew test --tests ArchitectureTest`
Expected: FAIL because the target packages contain no imported classes.

- [ ] **Step 2: Add four PackageMarker files and pin dependencies**

```kotlin
internal object PackageMarker
```

Pin `archunit = "1.4.2"` in `libs.versions.toml` and add `testImplementation("com.tngtech.archunit:archunit-junit5:${libs.versions.archunit.get()}")`.

- [ ] **Step 3: Add CI commands**

```yaml
steps:
  - uses: actions/checkout@v4
  - uses: actions/setup-java@v4
    with: {distribution: temurin, java-version: "21", cache: gradle}
  - run: pnpm install --frozen-lockfile
  - run: ./scripts/tests/verify-contracts.tests.ps1
    shell: pwsh
  - run: ./backend/gradlew -p backend clean test bootJar
```

- [ ] **Step 4: Verify and Commit**

Run: `cd backend; ./gradlew clean test`
Expected: PASS with a non-empty ArchitectureTest and no violations.

```powershell
git add backend .github/workflows/m1-backend.yml
git commit -m "test(m1): enforce module and CI boundaries"
```

### Task 3: M1.1 PostgreSQL Migration and Constraints

**Files:**
- Create: `deploy/dev/compose.yml`
- Modify: `backend/build.gradle.kts`
- Create: `backend/src/main/resources/db/migration/V1__m1_authority_baseline.sql`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/PostgresIntegrationTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/MigrationConstraintTest.kt`

- [ ] **Step 1: Write the failing real-PostgreSQL Migration Test**

```kotlin
@Testcontainers
@SpringBootTest
class MigrationConstraintTest {
    companion object {
        @Container val postgres = PostgreSQLContainer("postgres:17.11")
        @JvmStatic @DynamicPropertySource fun db(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url", postgres::getJdbcUrl)
            r.add("spring.datasource.username", postgres::getUsername)
            r.add("spring.datasource.password", postgres::getPassword)
        }
    }
    @Autowired lateinit var jdbc: JdbcClient
    @Test fun `flyway creates all M1 tables`() {
        val count = jdbc.sql("select count(*) from information_schema.tables where table_schema='public' and table_name in ('project','principal','project_assignment','release_record','release_state_history','manifest_revision','artifact','manifest_artifact','manifest_validation','audit_event','idempotency_record','outbox_event')").query(Int::class.java).single()
        assertThat(count).isEqualTo(12)
    }
}
```

Run: `cd backend; ./gradlew test --tests MigrationConstraintTest`
Expected: FAIL with a table count of 0.

- [ ] **Step 2: Add JDBC/Flyway/Testcontainers and V1 Migration**

The Migration implements the PK/FK/Cardinality for these 12 tables from [02-database-design.md](../../v0.2/02-database-design.md). All IDs use `varchar(40)`, times use `timestamptz`, Manifest source/report uses `jsonb`, and canonical bytes use `bytea`.

| Table | Required keys and constraints |
|---|---|
| `project` | PK `id`; UK `project_key` |
| `principal` | PK `id`; UK `(issuer, subject)`; `principal_type`, `disabled` |
| `project_assignment` | PK `(project_id, principal_id)`; both columns are FKs; `role` |
| `release_record` | PK `id`; FK `project_id`; UK `(project_id, vehicle, platform, system_version, build_id)`; status, `locked_manifest_id`, optimistic version |
| `release_state_history` | PK `id`; FK `release_id`; from/to state, actor, reason, timestamp; append-only |
| `manifest_revision` | PK `id`; FK `release_id`; UK `(release_id, revision)`; digest, source JSON, canonical bytes, schema version, state, optimistic version |
| `artifact` | PK `id`; UK `identity_digest`; type, locator, checksum; identity is never updated in place |
| `manifest_artifact` | PK `(manifest_id, artifact_id)`; both columns are FKs; UK `(manifest_id, ordinal)` |
| `manifest_validation` | PK `id`; FK `manifest_id`; status, content digest, schema version, report JSON, validated time |
| `audit_event` | PK `id`; FK `project_id`; actor, action, aggregate, before/after JSON, correlation, timestamp; trigger rejects UPDATE/DELETE |
| `idempotency_record` | PK `id`; UK `(scope, principal_id, idempotency_key)`; request hash, response, expiry |
| `outbox_event` | PK `id`; UK `event_id`; aggregate, event type, payload, created/published time |

All business FKs use `ON DELETE RESTRICT`; history, audit, and locked manifests never use cascade delete. The DDL must also add:

```sql
alter table release_record add constraint fk_release_locked_manifest
  foreign key (locked_manifest_id) references manifest_revision(id)
  deferrable initially deferred;
create unique index uq_one_locked_manifest_per_release
  on manifest_revision(release_id) where state = 'LOCKED';
create trigger immutable_audit before update or delete on audit_event
  for each row execute function reject_immutable_write();
create trigger immutable_locked_manifest before update or delete on manifest_revision
  for each row execute function reject_locked_manifest_write();
```

- [ ] **Step 3: Add negative Constraint Tests**

```kotlin
@Test fun `database rejects duplicate release identity and audit mutation`() {
    jdbc.sql("insert into project(id,project_key,name,created_at) values ('prj_1','vehicle-x','Vehicle X',now())").update()
    assertThatThrownBy { jdbc.sql("insert into project(id,project_key,name,created_at) values ('prj_2','vehicle-x','Duplicate',now())").update() }.isInstanceOf(DataAccessException::class.java)
    assertThatThrownBy { jdbc.sql("delete from audit_event").update() }.isInstanceOf(DataAccessException::class.java)
}
```

- [ ] **Step 4: Verify Migration and Commit**

Run: `cd backend; ./gradlew test --tests MigrationConstraintTest`
Expected: PASS, logging `postgres:17.11`, with no H2.

```powershell
git add backend deploy/dev/compose.yml
git commit -m "feat(database): add M1 authority schema"
```

### Task 4: M1.2 OIDC and RBAC Boundary

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/access/domain/Principal.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/access/domain/Permission.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/access/application/ProjectAuthorizer.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/access/adapter/SecurityConfig.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/access/adapter/JdbcProjectAuthorizer.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/access/SecurityAcceptanceTest.kt`

- [ ] **Step 1: Write 401/403/cross-Project failing tests**

```kotlin
@Test fun `unauthenticated request is 401`() =
    mockMvc.get("/api/v1/releases/rel_missing").andExpect { status { isUnauthorized() } }
@Test fun `cross project principal is 403`() =
    mockMvc.get("/api/v1/releases/rel_project_b") { with(jwt().jwt { it.subject("user-a") }.authorities(SimpleGrantedAuthority("SCOPE_release:read"))) }
        .andExpect { status { isForbidden() } }
```

- [ ] **Step 2: Implement fixed permissions and ProjectAuthorizer**

```kotlin
enum class Permission(val scope: String) { RELEASE_CREATE("release:create"), RELEASE_READ("release:read"), MANIFEST_WRITE("manifest:write"), MANIFEST_LOCK("manifest:lock") }
interface ProjectAuthorizer { fun require(principal: Principal, projectId: String, permission: Permission) }
data class Principal(val issuer: String, val subject: String, val service: Boolean)
```

`SecurityConfig` uses `oauth2ResourceServer { jwt {} }`, default `authenticated`, and stateless sessions. It maps issuer+subject to Principal and provides no anonymous or default Admin Profile.

- [ ] **Step 3: Verify Token failures**

Add issuer/audience/expiry/signature rejection tests. Task 6 verifies Audit failure rollback against a real Release transaction.

- [ ] **Step 4: Verify and Commit**

Run: `cd backend; ./gradlew test --tests "*SecurityAcceptanceTest"`
Expected: PASS for 401, 403, cross-Project, and four JWT failures.

```powershell
git add backend
git commit -m "feat(access): enforce OIDC RBAC and audit boundary"
```

### Task 5: M1.2 Transactional Audit, Outbox, and Idempotency

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/id/IdGenerator.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/time/TimeProvider.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/IdempotentExecutor.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/JdbcGovernanceStore.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/IdempotencyIntegrationTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/TestConcurrency.kt`

- [ ] **Step 1: Write the concurrent-idempotency failing test**

```kotlin
@Test fun `same key and digest executes once while different digest conflicts`() {
    val results = runConcurrently(2) { executor.execute("release:create", principalId, "key-1", "digest-a") { counter.incrementAndGet(); response } }
    assertThat(results).allMatch { it == response }
    assertThat(counter).hasValue(1)
    assertThatThrownBy { executor.execute("release:create", principalId, "key-1", "digest-b") { response } }.isInstanceOf(IdempotencyConflict::class.java)
}
```

- [ ] **Step 2: Implement acquire/replay/conflict**

```kotlin
interface IdempotentExecutor {
    fun <T : Any> execute(scope: String, principalId: String, key: String, requestDigest: String, responseType: Class<T>, action: () -> T): T
}
```

```kotlin
fun <T> runConcurrently(count: Int, action: () -> T): List<T> {
    val pool = Executors.newFixedThreadPool(count)
    val start = CountDownLatch(1)
    return try {
        val futures = (1..count).map { pool.submit<T> { start.await(); action() } }
        start.countDown()
        futures.map { it.get(10, TimeUnit.SECONDS) }
    } finally {
        pool.shutdownNow()
    }
}
```

The JDBC algorithm runs `insert ... on conflict do nothing` → `select ... for update` → digest comparison → business action → status/body persistence in one transaction. It uses no in-memory lock or second data source.

- [ ] **Step 3: Implement transactional Audit/Outbox Ports**

```kotlin
interface GovernanceStore {
    fun appendAudit(actorId: String, action: String, resourceType: String, resourceId: String, requestId: String, reason: String?)
    fun appendOutbox(eventType: String, aggregateId: String, payload: JsonNode)
}
```

- [ ] **Step 4: Verify and Commit**

Run: `cd backend; ./gradlew test --tests "*IdempotencyIntegrationTest"`
Expected: PASS with one execution and a conflict source that maps to 409.

```powershell
git add backend
git commit -m "feat(platform): add transactional governance primitives"
```

### Task 6: M1.3 Release Identity Domain and Persistence

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/release/domain/Release.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/release/application/CreateRelease.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/release/application/ReleaseRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/release/adapter/JdbcReleaseRepository.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/release/CreateReleaseIntegrationTest.kt`

- [ ] **Step 1: Write the transaction-completeness failing test**

```kotlin
@Test fun `create persists release history audit and outbox atomically`() {
    val created = useCase.create(command)
    assertThat(rowCount("release_record", created.releaseId)).isEqualTo(1)
    assertThat(rowCount("release_state_history", created.releaseId)).isEqualTo(1)
    assertThat(rowCount("audit_event", created.releaseId)).isEqualTo(1)
    assertThat(rowCount("outbox_event", created.releaseId)).isEqualTo(1)
}
```

- [ ] **Step 2: Implement immutable Domain and Port**

```kotlin
data class Release(val id: String, val projectId: String, val vehicle: String, val platform: String, val systemVersion: String, val declaredBuildId: String, val status: ReleaseStatus, val createdAt: Instant, val version: Long)
enum class ReleaseStatus { DRAFT, MANIFEST_LOCKED }
interface ReleaseRepository { fun insert(release: Release); fun find(id: String): Release? }
```

- [ ] **Step 3: Implement one-transaction Use Case**

`CreateRelease` executes ProjectAuthorizer → IdempotentExecutor → Release/History → Audit → Outbox, with one `@Transactional` Application Service owning the transaction.

- [ ] **Step 4: Negative tests and Commit**

Inject Audit/Outbox failure and assert all four tables remain empty. Change an external Build Fixture and assert the existing Release row and version do not change.

Run: `cd backend; ./gradlew test --tests "*CreateReleaseIntegrationTest"`
Expected: PASS.

```powershell
git add backend
git commit -m "feat(release): implement stable release identity"
```

### Task 7: M1.3 Release API and Problem Details

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/release/adapter/ReleaseController.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/problem/ApiProblem.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/problem/ProblemHandler.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/web/RequestIdFilter.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/release/ReleaseApiContractTest.kt`

- [ ] **Step 1: Write the frozen-API failing test**

```kotlin
mockMvc.post("/api/v1/releases") {
    with(jwt().authorities(SimpleGrantedAuthority("SCOPE_release:create")))
    header("Idempotency-Key", "create-1")
    contentType = MediaType.APPLICATION_JSON
    content = validCreateReleaseJson
}.andExpect {
    status { isCreated() }
    header { exists("X-Request-Id") }
    jsonPath("$.releaseId") { value(startsWith("rel_")) }
    jsonPath("$.status") { value("DRAFT") }
}
```

- [ ] **Step 2: Implement DTO/Controller/RequestId**

Requests reject unknown fields. Responses contain exactly `releaseId,status,manifestId,createdAt,version`. All failures map through `ApiProblem(type,title,status,code,detail,instance,requestId,violations)` to `application/problem+json`.

- [ ] **Step 3: Verify idempotency and error codes**

Repeated requests return the same body/status. A different digest returns `IDEMPOTENCY_KEY_REUSED`. Missing and invisible resources both return 404.

- [ ] **Step 4: Verify and Commit**

Run: `cd backend; ./gradlew test --tests "*ReleaseApiContractTest"`
Expected: PASS.

```powershell
git add backend
git commit -m "feat(api): expose release identity endpoints"
```

### Task 8: M1.4 Manifest Schema, JCS, and Digest

**Files:**
- Modify: `backend/build.gradle.kts`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/domain/ManifestDocument.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/application/ManifestValidator.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/adapter/NetworkntManifestValidator.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/adapter/JcsCanonicalizer.kt`
- Create: `backend/src/main/resources/contracts/release-manifest-v0.2.schema.json`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/manifest/ManifestContractTest.kt`
- Create: `scripts/m1/verify-jcs.mjs`

- [ ] **Step 1: Write Schema/JCS failing tests**

```kotlin
@Test fun `property order is ignored but artifact array order changes digest`() {
    assertThat(service.digest(jsonA)).isEqualTo(service.digest(jsonAReorderedProperties))
    assertThat(service.digest(jsonA)).isNotEqualTo(service.digest(jsonAReorderedArtifacts))
}
@Test fun `missing required and non NFC are rejected`() {
    assertThat(service.validate(invalidMissingRequired)).isNotEmpty()
    assertThat(service.validate(nonNfcFixture)).extracting("code").contains("MANIFEST_NOT_NFC")
}
```

- [ ] **Step 2: Pin compatible dependencies and implement**

Add `com.networknt:json-schema-validator:2.0.4` and `io.github.erdtman:java-json-canonicalization:1.1`. Copy `schemas/v0.2/release-manifest.schema.json` byte-for-byte into the application resource and compare SHA-256 in a test. The Validator rejects non-NFC strings, emits UTF-8 without BOM, and requires the digest to match `^sha256:[0-9a-f]{64}$`.

- [ ] **Step 3: Add independent Node verification**

```javascript
import canonicalize from "canonicalize";
import {createHash} from "node:crypto";
const bytes = Buffer.from(canonicalize(JSON.parse(process.argv[2])), "utf8");
process.stdout.write(`sha256:${createHash("sha256").update(bytes).digest("hex")}`);
```

Pin `canonicalize: "4.0.0"` in root `package.json`. JVM and Node must produce the same output for one Fixture.

- [ ] **Step 4: Verify and Commit**

Run: `pnpm install --frozen-lockfile; cd backend; ./gradlew test --tests "*ManifestContractTest"`
Expected: PASS for positive/negative cases and cross-implementation digests.

```powershell
git add backend scripts/m1 package.json pnpm-lock.yaml
git commit -m "feat(manifest): validate and canonicalize V0.2 manifests"
```

### Task 9: M1.4 Manifest Revision Registration and Validation Report

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/application/RegisterManifest.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/application/ValidateManifest.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/application/ManifestRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/adapter/JdbcManifestRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/adapter/ManifestController.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/manifest/ManifestRegistrationIntegrationTest.kt`

- [ ] **Step 1: Write registration/validation failing tests**

```kotlin
@Test fun `same idempotent registration returns one immutable revision`() {
    val first = register(validManifest, "key-1")
    val second = register(validManifest, "key-1")
    assertThat(second).isEqualTo(first)
    assertThat(count("manifest_revision")).isEqualTo(1)
}
```

- [ ] **Step 2: Implement Revision and Artifact persistence**

The registration transaction requires Manifest `releaseId/project/vehicle/platform/systemVersion/buildId` to match the Release exactly. Persist `manifest_artifact` by array ordinal. Deduplicate Artifact by identity digest and never update it in place.

- [ ] **Step 3: Implement Validate Endpoint**

Validation Report fields are `validationId,manifestId,status,contentDigest,schemaVersion,violations,validatedAt`. A registration Schema error returns 422 and creates no Revision. Identity/checksum semantic errors on a registered Revision produce a persisted `FAILED` report and return 422. Never claim checksum Payload verification when no Payload exists.

- [ ] **Step 4: Verify and Commit**

Run: `cd backend; ./gradlew test --tests "*ManifestRegistrationIntegrationTest"`
Expected: PASS with one row for a repeated request and a persisted failed report for invalid input.

```powershell
git add backend
git commit -m "feat(manifest): register and validate immutable revisions"
```

### Task 10: M1.4 Concurrent Lock and Immutable Export

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/application/LockManifest.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/application/ExportManifest.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/adapter/ManifestController.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/manifest/ManifestLockConcurrencyTest.kt`

- [ ] **Step 1: Write the two-operator concurrent-Lock failing test**

```kotlin
@Test fun `exactly one concurrent lock succeeds`() {
    val outcomes = runConcurrently(2) { lock(manifestId, etag = "1") }
    assertThat(outcomes.count { it.status == 200 }).isEqualTo(1)
    assertThat(outcomes.count { it.status == 409 }).isEqualTo(1)
    assertThat(countLocked(releaseId)).isEqualTo(1)
}
```

- [ ] **Step 2: Implement one-transaction Lock**

Use `select release_record ... for update`; revalidate the latest successful Validation and digest; commit Manifest `VALIDATED→LOCKED`, Release `DRAFT→MANIFEST_LOCKED`, State History, Audit, and Outbox. If-Match mismatch or an already Locked Release returns 409.

- [ ] **Step 3: Implement export and immutability negative tests**

Export source Manifest, canonical-bytes digest, Validation Report, and lockedAt. After Lock, UPDATE/DELETE, source JSON, and external Build Fixture changes must not change the exported digest.

- [ ] **Step 4: Verify and Commit**

Run: `cd backend; ./gradlew test --tests "*ManifestLockConcurrencyTest"`
Expected: PASS with exactly one 200 and one 409.

```powershell
git add backend
git commit -m "feat(manifest): lock and export authoritative manifest"
```

### Task 11: M1.5 Recovery, Smoke, and Acceptance Evidence

**Files:**
- Create: `scripts/m1/{verify.ps1,export-schema.ps1,acceptance-smoke.ps1}`
- Create: `docs/m1/{runbook.md,acceptance-checklist.md,evidence-index.md}`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/M1EndToEndTest.kt`

- [ ] **Step 1: Write the end-to-end failing test**

```kotlin
@Test fun `create register validate lock export survives restore`() {
    val result = scenario.run(validManifest)
    assertThat(result.lockedDigest).isEqualTo(result.exportedDigest)
    assertThat(result.auditActions).containsExactly("RELEASE_CREATED", "MANIFEST_REGISTERED", "MANIFEST_VALIDATED", "MANIFEST_LOCKED")
}
```

- [ ] **Step 2: Implement one verification entry point**

```powershell
$ErrorActionPreference = "Stop"
pnpm install --frozen-lockfile
./scripts/tests/verify-contracts.tests.ps1
./backend/gradlew -p backend clean test bootJar
./scripts/m1/acceptance-smoke.ps1
Write-Output "PASS M1 gates=contract,build,test,security,concurrency,smoke,recovery"
```

`acceptance-smoke.ps1` starts clean PostgreSQL 17.11, executes the API chain, uses `pg_dump`/restore into a second instance, and exports the same Locked Manifest digest. Every failure exits nonzero immediately.

- [ ] **Step 3: Write the recovery runbook and static Evidence Index**

`runbook.md` defines startup, backup, restore, migration rollback commands, and ownership; `acceptance-checklist.md` maps M1.0-M1.5 acceptance items to gates; `evidence-index.md` records only gate names, runner scripts, and CI Artifact paths, with no commit SHA or PASS status.

- [ ] **Step 4: Commit the candidate implementation**

```powershell
git add scripts/m1 docs/m1 backend
git commit -m "test(m1): add integrated acceptance and recovery gate"
```

- [ ] **Step 5: Run all gates against the committed SHA and generate machine evidence**

```powershell
$sourceCommit = (git rev-parse HEAD).Trim()
$evidence = [ordered]@{
  milestone = "M1"; status = "CANDIDATE"; commit = $sourceCommit
  gates = @(@{name="contract";exitCode=0}, @{name="postgres-restore";exitCode=0})
  ownerDecision = "PENDING"
}
$evidence | ConvertTo-Json -Depth 5
```

The script writes the real Commit, time, command, exit code, and report SHA-256 dynamically. Documentation never prefills a fabricated PASS.

Run: `./scripts/m1/verify.ps1`
Expected: `PASS M1 gates=contract,build,test,security,concurrency,smoke,recovery`.

The verification script asserts a clean worktree, then uploads `evidence.json` as a CI Artifact; its `commit` field must equal the candidate SHA just committed. If a gate fails, fix it in a new meaningful commit and rerun against the new SHA; never overwrite or disguise an earlier result.

## 12. M1 Owner Gate and Stop Conditions

After implementation, push Chinese/English candidate branches and run Pair Verification. Do not merge or create an M1 Tag automatically. Submit to the Owner:

1. M1.0–M1.5 batch Commits and test reports.
2. Schema Export, Migration/Constraint, OIDC/RBAC/Audit, Idempotency, and concurrent-Lock reports.
3. Locked Manifest, Validation Report, Audit Timeline, and backup-restore digest.
4. Known limitations and residual risks.

Stop immediately and raise an ADR or Finding if implementation requires a Core Contract change, changes Manifest Authority, cannot keep Audit/Outbox transactional, conflicts with real PostgreSQL constraints, or can meet four-week capacity only by deleting a protected item. Merge separately into `main`/`release` and create new paired M1 milestone Tags only after explicit Owner approval.
