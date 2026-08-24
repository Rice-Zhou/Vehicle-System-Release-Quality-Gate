# M1 Release Identity and Manifest Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在四周内交付可运行、可测试、可恢复的 Release Identity 与 Manifest Authority，并形成 Owner 可独立验收的 M1 证据包。

**Architecture:** 单个 Spring Boot 可部署单元内部按 `access`、`release`、`manifest`、`shared` 包形成 Modular Monolith。Domain/Application 不依赖 Web/JDBC；PostgreSQL 是唯一结构化事实源，所有高风险写入将业务数据、Audit 与 Outbox 放在同一事务。

**Tech Stack:** Java 21 LTS、Kotlin 2.2.21、Spring Boot 3.5.16、Gradle 8.14.4、PostgreSQL 17.11、Flyway（由 Boot BOM 管理）、Testcontainers（由 Boot BOM 管理）、ArchUnit 1.4.2、networknt JSON Schema Validator 2.0.4、java-json-canonicalization 1.1、Node canonicalize 4.0.0。

---

## 0. 执行规则与文件结构

只在新的 M1 实施分支执行本计划；不得直接在 `main`、`release` 或设计规格分支写生产代码。每个 Task 一个可解释 Commit；验证脚本以实际 `gate` 和 `git rev-parse HEAD` 构造 Artifact 目录，`evidence.json` 只上传 CI Artifact，不提交到 Git。

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

版本依据：Spring Boot 3.5.16 支持 Java 17–25 和 Gradle 8.4+；Gradle 8.14.4 属于受支持 8.x；PostgreSQL 17 支持至 2029；RFC 8785 列出 Java 与 JavaScript 参考实现。版本升级必须单独提交并重跑完整 M1 Gate。

### Task 1: M1.0 工程骨架与可启动应用

**Files:**
- Create: `backend/settings.gradle.kts`
- Create: `backend/build.gradle.kts`
- Create: `backend/gradle/libs.versions.toml`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/ApplicationContextTest.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/VsrqgApplication.kt`
- Create: `backend/src/main/resources/application.yml`

- [ ] **Step 1: 先写失败的 Context Test**

```kotlin
@SpringBootTest
class ApplicationContextTest {
    @Test fun `context loads`() = Unit
}
```

- [ ] **Step 2: 创建固定版本构建文件并验证红灯**

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
Expected: FAIL，因为 `VsrqgApplication` 尚不存在。

- [ ] **Step 3: 添加最小应用与安全默认配置**

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

- [ ] **Step 4: 生成 Wrapper 并验证绿灯**

Run: `cd backend; gradle wrapper --gradle-version 8.14.4; ./gradlew clean test bootJar`
Expected: PASS，产生 `backend/build/libs/*.jar`。

- [ ] **Step 5: Commit**

```powershell
git add backend
git commit -m "build(m1): establish backend skeleton"
```

### Task 2: M1.0 模块边界与 CI Gate

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/{access,release,manifest,shared}/PackageMarker.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/ArchitectureTest.kt`
- Create: `.github/workflows/m1-backend.yml`

- [ ] **Step 1: 写失败的 ArchUnit Test**

```kotlin
@AnalyzeClasses(packages = ["com.ricezhou.vsrqg"])
class ArchitectureTest {
    @ArchTest val domainIsFrameworkFree = noClasses()
        .that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "..adapter..")
}
```

Run: `cd backend; ./gradlew test --tests ArchitectureTest`
Expected: FAIL，因为目标包尚无可导入类。

- [ ] **Step 2: 添加四个 PackageMarker 并固定依赖**

```kotlin
internal object PackageMarker
```

在 `libs.versions.toml` 固定 `archunit = "1.4.2"`，增加 `testImplementation("com.tngtech.archunit:archunit-junit5:${libs.versions.archunit.get()}")`。

- [ ] **Step 3: 增加 CI 命令**

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

- [ ] **Step 4: 验证并 Commit**

Run: `cd backend; ./gradlew clean test`
Expected: PASS，ArchitectureTest 非空且无违规。

```powershell
git add backend .github/workflows/m1-backend.yml
git commit -m "test(m1): enforce module and CI boundaries"
```

### Task 3: M1.1 PostgreSQL Migration 与约束

**Files:**
- Create: `deploy/dev/compose.yml`
- Modify: `backend/build.gradle.kts`
- Create: `backend/src/main/resources/db/migration/V1__m1_authority_baseline.sql`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/PostgresIntegrationTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/MigrationConstraintTest.kt`

- [ ] **Step 1: 写失败的真实 PostgreSQL Migration Test**

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
Expected: FAIL，表数量为 0。

- [ ] **Step 2: 增加 JDBC/Flyway/Testcontainers 依赖和 V1 Migration**

Migration 必须精确实现 [02-database-design.md](../../v0.2/02-database-design.md) 中上述 12 表的 PK/FK/Cardinality；所有 ID 为 `varchar(40)`，时间为 `timestamptz`，Manifest 原文/报告为 `jsonb`，canonical bytes 为 `bytea`。增加：

| 表 | 必须实现的键与约束 |
|---|---|
| `project` | PK `id`；UK `project_key` |
| `principal` | PK `id`；UK `(issuer, subject)`；`principal_type`、`disabled` |
| `project_assignment` | PK `(project_id, principal_id)`；两列 FK；`role` |
| `release_record` | PK `id`；FK `project_id`；UK `(project_id, vehicle, platform, system_version, build_id)`；状态、`locked_manifest_id`、乐观锁版本 |
| `release_state_history` | PK `id`；FK `release_id`；前后状态、actor、reason、时间；仅追加 |
| `manifest_revision` | PK `id`；FK `release_id`；UK `(release_id, revision)`；digest、原始 JSON、canonical bytes、schema version、state、乐观锁版本 |
| `artifact` | PK `id`；UK `identity_digest`；类型、定位符、checksum；禁止原地改写 identity |
| `manifest_artifact` | PK `(manifest_id, artifact_id)`；两列 FK；UK `(manifest_id, ordinal)` |
| `manifest_validation` | PK `id`；FK `manifest_id`；status、content digest、schema version、report JSON、validated time |
| `audit_event` | PK `id`；FK `project_id`；actor、action、aggregate、before/after JSON、correlation、时间；UPDATE/DELETE 被 trigger 拒绝 |
| `idempotency_record` | PK `id`；UK `(scope, principal_id, idempotency_key)`；request hash、response、expiry |
| `outbox_event` | PK `id`；UK `event_id`；aggregate、event type、payload、created/published time |

所有业务 FK 使用 `ON DELETE RESTRICT`；历史、Audit、Locked Manifest 不使用级联删除。DDL 还必须包含：

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

- [ ] **Step 3: 增加负向 Constraint Test**

```kotlin
@Test fun `database rejects duplicate release identity and audit mutation`() {
    jdbc.sql("insert into project(id,project_key,name,created_at) values ('prj_1','vehicle-x','Vehicle X',now())").update()
    assertThatThrownBy { jdbc.sql("insert into project(id,project_key,name,created_at) values ('prj_2','vehicle-x','Duplicate',now())").update() }.isInstanceOf(DataAccessException::class.java)
    assertThatThrownBy { jdbc.sql("delete from audit_event").update() }.isInstanceOf(DataAccessException::class.java)
}
```

- [ ] **Step 4: 验证 Migration 与 Commit**

Run: `cd backend; ./gradlew test --tests MigrationConstraintTest`
Expected: PASS，测试日志显示 `postgres:17.11`，不使用 H2。

```powershell
git add backend deploy/dev/compose.yml
git commit -m "feat(database): add M1 authority schema"
```

### Task 4: M1.2 OIDC 与 RBAC 边界

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/access/domain/Principal.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/access/domain/Permission.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/access/application/ProjectAuthorizer.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/access/adapter/SecurityConfig.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/access/adapter/JdbcProjectAuthorizer.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/access/SecurityAcceptanceTest.kt`

- [ ] **Step 1: 写 401/403/跨 Project 红灯测试**

```kotlin
@Test fun `unauthenticated request is 401`() =
    mockMvc.get("/api/v1/releases/rel_missing").andExpect { status { isUnauthorized() } }
@Test fun `cross project principal is 403`() =
    mockMvc.get("/api/v1/releases/rel_project_b") { with(jwt().jwt { it.subject("user-a") }.authorities(SimpleGrantedAuthority("SCOPE_release:read"))) }
        .andExpect { status { isForbidden() } }
```

- [ ] **Step 2: 实现固定权限与 ProjectAuthorizer**

```kotlin
enum class Permission(val scope: String) { RELEASE_CREATE("release:create"), RELEASE_READ("release:read"), MANIFEST_WRITE("manifest:write"), MANIFEST_LOCK("manifest:lock") }
interface ProjectAuthorizer { fun require(principal: Principal, projectId: String, permission: Permission) }
data class Principal(val issuer: String, val subject: String, val service: Boolean)
```

`SecurityConfig` 必须 `oauth2ResourceServer { jwt {} }`、默认 `authenticated`、关闭 session，并用 issuer+subject 映射 Principal；不能提供匿名或默认 Admin Profile。

- [ ] **Step 3: 验证 Token 失败**

增加 issuer/audience/expiry/signature 拒绝测试；Audit 失败回滚在 Task 6 的真实 Release 事务中验证。

- [ ] **Step 4: 验证并 Commit**

Run: `cd backend; ./gradlew test --tests "*SecurityAcceptanceTest"`
Expected: PASS，覆盖 401、403、跨 Project 和四种 JWT 失败。

```powershell
git add backend
git commit -m "feat(access): enforce OIDC RBAC and audit boundary"
```

### Task 5: M1.2 事务型 Audit、Outbox 与 Idempotency

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/id/IdGenerator.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/time/TimeProvider.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/application/IdempotentExecutor.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/adapter/JdbcGovernanceStore.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/IdempotencyIntegrationTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/TestConcurrency.kt`

- [ ] **Step 1: 写并发幂等红灯测试**

```kotlin
@Test fun `same key and digest executes once while different digest conflicts`() {
    val results = runConcurrently(2) { executor.execute("release:create", principalId, "key-1", "digest-a") { counter.incrementAndGet(); response } }
    assertThat(results).allMatch { it == response }
    assertThat(counter).hasValue(1)
    assertThatThrownBy { executor.execute("release:create", principalId, "key-1", "digest-b") { response } }.isInstanceOf(IdempotencyConflict::class.java)
}
```

- [ ] **Step 2: 实现 acquire/replay/conflict**

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

JDBC 算法必须在同一事务执行 `insert ... on conflict do nothing` → `select ... for update` → 比对 digest → 执行业务 → 保存 status/body；不得用内存锁或第二数据源。

- [ ] **Step 3: 实现 Audit/Outbox 同事务 Port**

```kotlin
interface GovernanceStore {
    fun appendAudit(actorId: String, action: String, resourceType: String, resourceId: String, requestId: String, reason: String?)
    fun appendOutbox(eventType: String, aggregateId: String, payload: JsonNode)
}
```

- [ ] **Step 4: 验证并 Commit**

Run: `cd backend; ./gradlew test --tests "*IdempotencyIntegrationTest"`
Expected: PASS，并发执行计数为 1，冲突为 409 映射源异常。

```powershell
git add backend
git commit -m "feat(platform): add transactional governance primitives"
```

### Task 6: M1.3 Release Identity Domain 与 Persistence

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/release/domain/Release.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/release/application/CreateRelease.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/release/application/ReleaseRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/release/adapter/JdbcReleaseRepository.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/release/CreateReleaseIntegrationTest.kt`

- [ ] **Step 1: 写事务完整性红灯测试**

```kotlin
@Test fun `create persists release history audit and outbox atomically`() {
    val created = useCase.create(command)
    assertThat(rowCount("release_record", created.releaseId)).isEqualTo(1)
    assertThat(rowCount("release_state_history", created.releaseId)).isEqualTo(1)
    assertThat(rowCount("audit_event", created.releaseId)).isEqualTo(1)
    assertThat(rowCount("outbox_event", created.releaseId)).isEqualTo(1)
}
```

- [ ] **Step 2: 实现不可变 Domain 和 Port**

```kotlin
data class Release(val id: String, val projectId: String, val vehicle: String, val platform: String, val systemVersion: String, val declaredBuildId: String, val status: ReleaseStatus, val createdAt: Instant, val version: Long)
enum class ReleaseStatus { DRAFT, MANIFEST_LOCKED }
interface ReleaseRepository { fun insert(release: Release); fun find(id: String): Release? }
```

- [ ] **Step 3: 实现 Use Case 单事务**

`CreateRelease` 必须按 ProjectAuthorizer → IdempotentExecutor → Release/History → Audit → Outbox 顺序执行，并由一个 `@Transactional` Application Service 拥有事务。

- [ ] **Step 4: 负向测试与 Commit**

注入 Audit/Outbox 失败，断言四表均为 0；改变外部 Build Fixture，断言已创建 Release 行和版本不变。

Run: `cd backend; ./gradlew test --tests "*CreateReleaseIntegrationTest"`
Expected: PASS。

```powershell
git add backend
git commit -m "feat(release): implement stable release identity"
```

### Task 7: M1.3 Release API 与 Problem Details

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/release/adapter/ReleaseController.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/problem/ApiProblem.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/problem/ProblemHandler.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/shared/web/RequestIdFilter.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/release/ReleaseApiContractTest.kt`

- [ ] **Step 1: 写冻结 API 红灯测试**

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

- [ ] **Step 2: 实现 DTO/Controller/RequestId**

请求严格拒绝未知字段；响应固定 `releaseId,status,manifestId,createdAt,version`。所有异常通过 `ApiProblem(type,title,status,code,detail,instance,requestId,violations)` 映射为 `application/problem+json`。

- [ ] **Step 3: 验证幂等与错误码**

重复请求响应 body/status 相同；不同 digest 返回 `IDEMPOTENCY_KEY_REUSED`；不存在/不可见统一为 404。

- [ ] **Step 4: 验证并 Commit**

Run: `cd backend; ./gradlew test --tests "*ReleaseApiContractTest"`
Expected: PASS。

```powershell
git add backend
git commit -m "feat(api): expose release identity endpoints"
```

### Task 8: M1.4 Manifest Schema、JCS 与 Digest

**Files:**
- Modify: `backend/build.gradle.kts`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/domain/ManifestDocument.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/application/ManifestValidator.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/adapter/NetworkntManifestValidator.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/adapter/JcsCanonicalizer.kt`
- Create: `backend/src/main/resources/contracts/release-manifest-v0.2.schema.json`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/manifest/ManifestContractTest.kt`
- Create: `scripts/m1/verify-jcs.mjs`

- [ ] **Step 1: 写 Schema/JCS 红灯测试**

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

- [ ] **Step 2: 固定兼容依赖并实现**

增加 `com.networknt:json-schema-validator:2.0.4` 与 `io.github.erdtman:java-json-canonicalization:1.1`。将仓库 `schemas/v0.2/release-manifest.schema.json` 原字节复制到应用 resource，并用测试比较 SHA-256；Validator 拒绝非 NFC string，Canonicalizer 输出 UTF-8 无 BOM，digest 必须匹配 `^sha256:[0-9a-f]{64}$`。

- [ ] **Step 3: 增加独立 Node 校验**

```javascript
import canonicalize from "canonicalize";
import {createHash} from "node:crypto";
const bytes = Buffer.from(canonicalize(JSON.parse(process.argv[2])), "utf8");
process.stdout.write(`sha256:${createHash("sha256").update(bytes).digest("hex")}`);
```

在根 `package.json` 固定 `canonicalize: "4.0.0"`；JVM 与 Node 对同一 Fixture 输出必须相同。

- [ ] **Step 4: 验证并 Commit**

Run: `pnpm install --frozen-lockfile; cd backend; ./gradlew test --tests "*ManifestContractTest"`
Expected: PASS，正反例和跨实现 digest 一致。

```powershell
git add backend scripts/m1 package.json pnpm-lock.yaml
git commit -m "feat(manifest): validate and canonicalize V0.2 manifests"
```

### Task 9: M1.4 Manifest Revision 注册与 Validation Report

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/application/RegisterManifest.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/application/ValidateManifest.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/application/ManifestRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/adapter/JdbcManifestRepository.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/adapter/ManifestController.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/manifest/ManifestRegistrationIntegrationTest.kt`

- [ ] **Step 1: 写注册/验证红灯测试**

```kotlin
@Test fun `same idempotent registration returns one immutable revision`() {
    val first = register(validManifest, "key-1")
    val second = register(validManifest, "key-1")
    assertThat(second).isEqualTo(first)
    assertThat(count("manifest_revision")).isEqualTo(1)
}
```

- [ ] **Step 2: 实现 Revision 与 Artifact 持久化**

注册事务校验 Manifest `releaseId/project/vehicle/platform/systemVersion/buildId` 与 Release 完全一致；以数组 ordinal 保存 `manifest_artifact`；Artifact 由 identity digest 去重但不允许原地更新。

- [ ] **Step 3: 实现 Validate Endpoint**

Validation Report 固定字段：`validationId,manifestId,status,contentDigest,schemaVersion,violations,validatedAt`。注册时 Schema 错误直接返回 422 且不创建 Revision；已注册 Revision 的 identity/checksum 语义错误产生 `FAILED` 报告并返回 422；不得把未验证 Payload 宣称为 checksum 已复验。

- [ ] **Step 4: 验证并 Commit**

Run: `cd backend; ./gradlew test --tests "*ManifestRegistrationIntegrationTest"`
Expected: PASS，重复请求一行，错误输入有持久化失败报告。

```powershell
git add backend
git commit -m "feat(manifest): register and validate immutable revisions"
```

### Task 10: M1.4 并发 Lock 与不可变导出

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/application/LockManifest.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/application/ExportManifest.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/manifest/adapter/ManifestController.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/manifest/ManifestLockConcurrencyTest.kt`

- [ ] **Step 1: 写两个操作者并发 Lock 红灯测试**

```kotlin
@Test fun `exactly one concurrent lock succeeds`() {
    val outcomes = runConcurrently(2) { lock(manifestId, etag = "1") }
    assertThat(outcomes.count { it.status == 200 }).isEqualTo(1)
    assertThat(outcomes.count { it.status == 409 }).isEqualTo(1)
    assertThat(countLocked(releaseId)).isEqualTo(1)
}
```

- [ ] **Step 2: 实现 Lock 单事务**

使用 `select release_record ... for update`，复验最新成功 Validation 和 digest，执行 Manifest `VALIDATED→LOCKED`、Release `DRAFT→MANIFEST_LOCKED`、State History、Audit、Outbox；If-Match 不符或已 Lock 返回 409。

- [ ] **Step 3: 实现导出和不可变负向测试**

导出返回原始 Manifest、canonical bytes digest、Validation Report、lockedAt；Lock 后任何 UPDATE/DELETE、源 JSON/外部 Build Fixture 变化均不改变导出 digest。

- [ ] **Step 4: 验证并 Commit**

Run: `cd backend; ./gradlew test --tests "*ManifestLockConcurrencyTest"`
Expected: PASS，恰好一个 200、一个 409。

```powershell
git add backend
git commit -m "feat(manifest): lock and export authoritative manifest"
```

### Task 11: M1.5 恢复、Smoke 与验收证据

**Files:**
- Create: `scripts/m1/{verify.ps1,export-schema.ps1,acceptance-smoke.ps1}`
- Create: `docs/m1/{runbook.md,acceptance-checklist.md,evidence-index.md}`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/M1EndToEndTest.kt`

- [ ] **Step 1: 写端到端红灯测试**

```kotlin
@Test fun `create register validate lock export survives restore`() {
    val result = scenario.run(validManifest)
    assertThat(result.lockedDigest).isEqualTo(result.exportedDigest)
    assertThat(result.auditActions).containsExactly("RELEASE_CREATED", "MANIFEST_REGISTERED", "MANIFEST_VALIDATED", "MANIFEST_LOCKED")
}
```

- [ ] **Step 2: 实现单一验证入口**

```powershell
$ErrorActionPreference = "Stop"
pnpm install --frozen-lockfile
./scripts/tests/verify-contracts.tests.ps1
./backend/gradlew -p backend clean test bootJar
./scripts/m1/acceptance-smoke.ps1
Write-Output "PASS M1 gates=contract,build,test,security,concurrency,smoke,recovery"
```

`acceptance-smoke.ps1` 必须启动干净 PostgreSQL 17.11，执行 API 全链，使用 `pg_dump`/restore 到第二实例，再导出相同 Locked Manifest digest；任一步失败立即非零退出。

- [ ] **Step 3: 编写恢复手册与静态 Evidence 索引**

`runbook.md` 写明启动、备份、恢复、回滚 Migration 的命令和负责人；`acceptance-checklist.md` 映射 M1.0～M1.5 验收项到 Gate；`evidence-index.md` 只记录 Gate 名称、执行脚本和 CI Artifact 路径，不写 Commit SHA 或 PASS 状态。

- [ ] **Step 4: 提交候选实现**

```powershell
git add scripts/m1 docs/m1 backend
git commit -m "test(m1): add integrated acceptance and recovery gate"
```

- [ ] **Step 5: 对已提交 SHA 运行全量 Gate 并生成机器证据**

```powershell
$sourceCommit = (git rev-parse HEAD).Trim()
$evidence = [ordered]@{
  milestone = "M1"; status = "CANDIDATE"; commit = $sourceCommit
  gates = @(@{name="contract";exitCode=0}, @{name="postgres-restore";exitCode=0})
  ownerDecision = "PENDING"
}
$evidence | ConvertTo-Json -Depth 5
```

脚本必须动态写入真实 Commit、时间、命令、退出码和报告 SHA-256；文档不得预填伪造 PASS。

Run: `./scripts/m1/verify.ps1`
Expected: `PASS M1 gates=contract,build,test,security,concurrency,smoke,recovery`。

验证脚本确认工作树 clean，随后把 `evidence.json` 作为 CI Artifact 上传；其 `commit` 字段必须等于刚提交的候选 SHA。若 Gate 失败，修复后创建新的有意义 Commit，再对新 SHA 重跑，不覆写或伪装旧结果。

## 12. M1 Owner Gate 与停止条件

实施完成后先推送中文/英文候选分支并执行 Pair Verification，不自动合并、不创建 M1 Tag。向 Owner 提交：

1. M1.0～M1.5 每批 Commit 与测试报告。
2. Schema Export、Migration/Constraint、OIDC/RBAC/Audit、Idempotency、并发 Lock 报告。
3. Locked Manifest、Validation Report、Audit Timeline、备份恢复 digest。
4. 已知限制与残余风险。

出现以下情况立即停止并提出 ADR 或 Finding：需要修改 Core Contract；需要改变 Manifest Authority；无法做到 Audit/Outbox 同事务；真实 PostgreSQL 约束与设计冲突；四周容量必须删除任何不可削弱项。只有 Owner 明确批准 M1 后，才分别合并到 `main`/`release` 并创建新的配对 M1 里程碑标签。
