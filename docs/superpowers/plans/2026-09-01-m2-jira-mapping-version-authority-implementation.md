# M2.2 Jira Mapping Profile 与 Adapter Version Authority Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变 V0.1 冻结架构的前提下，实现 Project/Issue Source scoped 的不可变 Jira Mapping Profile、代码唯一 Adapter Version Authority，以及每个 Sync Run 的确定性版本固定与 fail-closed 执行。

**Architecture:** 在现有 Kotlin/Spring Boot 模块化单体中，以 PostgreSQL `issue_mapping_profile` 作为映射内容权威，以代码 `IssueSourceRuntimeDescriptor` 作为 Adapter Version 权威。受鉴权激活事务和 `StartIssueSync` 使用同一 Source row lock；Worker 只在 Descriptor、Run 和 Profile 的 Project/Source/Schema/digest/version 全部一致后创建 `IssueSourcePort`，而 `RunIssueSync` 继续只依赖 Port。

**Tech Stack:** Kotlin 2.3、Java 21、Spring Boot 4、Spring JDBC/Transaction、PostgreSQL 17、Flyway、Jackson、RFC 8785 JCS、JUnit 5、AssertJ、Testcontainers、OpenAPI 3.1、PowerShell。

---

## 交付边界与文件结构

本计划基于已批准的 `M2-KD-2026-09-01-01` 与 Accepted `TDR-015`。计划批准只允许执行下列任务；真实 Jira 查询、Jira 写操作、Company、Task 5、合并 `main`/`release`、Tag、release 与 production deployment 仍需独立授权。

文件责任固定如下：

- `IssueMappingProfile.kt`：Application 数据类型、Codec/Repository ports 与固定失败类型。
- `JcsIssueMappingProfileCodec.kt`：严格结构验证、Unicode Token 规范化、RFC 8785 canonicalization、SHA-256 与编译映射。
- `JdbcIssueMappingProfileRepository.kt`：Source row lock、不可变 Profile 写入/读取和激活 selector。
- `ActivateIssueMappingProfile.kt`：Project authorization、Idempotency、事务、Audit 与 Outbox。
- `IssueMappingProfileController.kt`：唯一激活 API 与固定 422 Problem Details。
- `JiraIssueMapper.kt`：Profile-pinned status/severity 精确映射。
- `IssueSourceRuntime.kt`：Descriptor、Factory、Registry 与运行前版本/完整性 Gate。
- `JiraCliPilotAdapter.kt`：只保留 transport、解析和对已固定 Mapper 的调用，不保留硬编码 Map。

实施顺序固定为 Task 1～8；每项先 RED、再最小 GREEN、再目标回归并形成有意义提交。

### Task 1: API Contract 与 `issue:configure` Permission

**Files:**
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/access/domain/Permission.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/access/PermissionMatrixTest.kt`
- Modify: `contracts/openapi/v0.2/openapi.json`
- Modify: `contracts/openapi/v0.2/compatibility-baseline.json`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/M2ApiContractTest.kt`

- [ ] **Step 1: 写失败的 Permission 与 Contract 断言**

把下列权限断言加入 `PermissionMatrixTest`，并把新的 operation 加入 `M2ApiContractTest.APPROVED_OPERATIONS`：

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

增加测试，精确断言 request schema `additionalProperties=false`、六个必需字段、响应不含 `definition`，并明确拒绝 `mappingVersion` 与 `adapterVersion`。

- [ ] **Step 2: 运行 RED 测试**

Run: `pnpm run test:contracts; ./backend/gradlew -p backend test --tests '*PermissionMatrixTest' --tests '*M2ApiContractTest'`

Expected: FAIL，指出 `ISSUE_CONFIGURE`、activation operation 与 request/response schema 缺失。

- [ ] **Step 3: 添加最小权限与 OpenAPI Contract**

```kotlin
ISSUE_CONFIGURE(
    "issue:configure",
    setOf(ProjectRole.RELEASE_MANAGER, ProjectRole.ADMINISTRATOR),
),
```

在 OpenAPI 增加同步 `201` operation、`Idempotency-Key`、OIDC scope，以及严格 Schema：

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

Schema 为 status/severity Alias 数组设置 `maxItems=256`、Token `maxLength=120`、Profile request `maxProperties=6`；response 只包含 `profileId`、`sourceId`、`schemaVersion`、`mappingVersion`、`activatedAt`。

- [ ] **Step 4: 验证并提交**

Run: `pnpm run test:contracts; ./backend/gradlew -p backend test --tests '*PermissionMatrixTest' --tests '*M2ApiContractTest'`

Expected: contracts `operations=33`，目标 Gradle tests PASS。

Commit: `feat(m2): define mapping profile activation contract`

### Task 2: V5 PostgreSQL Immutable Mapping Authority

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__issue_mapping_profile.sql`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/M2MigrationConstraintTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/shared/IssueMappingProfileMigrationTest.kt`

- [ ] **Step 1: 写失败的 Migration tests**

测试必须断言：表与列精确存在；`UNIQUE(source_id, mapping_version)`；复合 Source/Project FK；`created_by` principal FK；digest CHECK；UPDATE/DELETE Trigger；跨 Project insert 拒绝；同一内容可分别属于两个 Source；V4→V5、空库和重复 migrate 可运行且历史行不变。

- [ ] **Step 2: 运行 RED 测试**

Run: `./backend/gradlew -p backend test --tests '*IssueMappingProfileMigrationTest' --tests '*M2MigrationConstraintTest'`

Expected: FAIL，首个缺失对象为 `issue_mapping_profile`，当前 Flyway version 为 `4`。

- [ ] **Step 3: 创建 forward-only V5**

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

不得 seed Profile、不得改写 `issue_source.mapping_version`、不得修改历史 Sync/Revision/Snapshot。

- [ ] **Step 4: 验证 Migration 并提交**

Run: `./backend/gradlew -p backend test --tests '*IssueMappingProfileMigrationTest' --tests '*M2MigrationConstraintTest'`

Expected: V5 clean/upgrade/repeat、constraint、immutability 与历史保护 tests PASS。

Commit: `feat(m2): add immutable mapping profile authority`

### Task 3: Deterministic Profile Codec 与 Mapper Contract

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueMappingProfile.kt`
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JcsIssueMappingProfileCodec.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueMappingProfileCodecTest.kt`

- [ ] **Step 1: 写 Profile/Codec 数据契约与失败测试**

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

测试覆盖：三次 digest 重放；对象字段顺序不影响 digest；NFC、Unicode 首尾空白、`Locale.ROOT`；规范化 Alias 冲突；`UNKNOWN` target；未知字段/Schema/policy；空 Token、控制字符、121 字符 Token、每类 257 Aliases、超过 64 KiB definition；regex/wildcard 不获得特殊语义；异常、message 与 violation 不包含输入 Token。

- [ ] **Step 2: 运行 RED 测试**

Run: `./backend/gradlew -p backend test --tests '*IssueMappingProfileCodecTest'`

Expected: FAIL，Profile 类型与 Codec 尚不存在。

- [ ] **Step 3: 实现严格 Codec**

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

Codec 只接受六个固定字段；只允许 `IssueStatus`/`IssueSeverity` 中非 `UNKNOWN` target；冲突时拒绝整个 Profile；不解释 regex、通配符、prefix、contains 或 fuzzy 语义。

- [ ] **Step 4: 验证并提交**

Run: `./backend/gradlew -p backend test --tests '*IssueMappingProfileCodecTest'`

Expected: 全部 deterministic、bound 与 redaction tests PASS。

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

- [ ] **Step 1: 写失败的 Application/HTTP/transaction tests**

覆盖 RELEASE_MANAGER/ADMINISTRATOR 成功、ENGINEER/`issue:sync` 拒绝、跨 Project 拒绝、请求版本字段注入固定 422、非法 Profile 固定 422、相同 Idempotency-Key 同请求重放、同 key 异请求 409、重复 Source Type Descriptor 启动失败、Profile insert + 两个 version selector + Audit + Outbox 原子提交，以及在 Audit/Outbox 故障时整体回滚并保留旧 selector。

- [ ] **Step 2: 运行 RED 测试**

Run: `./backend/gradlew -p backend test --tests '*IssueMappingProfileActivationIntegrationTest'`

Expected: FAIL，activation service、repository 与 controller 缺失。

- [ ] **Step 3: 实现 ports 与事务服务**

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

`IssueSourceRuntime.kt` 先定义 Descriptor 与只读 `IssueSourceDescriptorRegistry`；Jira 的唯一 Descriptor version 为 `jira-cli-pilot-adapter-v1`，重复 Source Type Descriptor 必须使 ApplicationContext 启动失败。`ActivateIssueMappingProfile.activate` 固定顺序：find Source → Project authorize `ISSUE_CONFIGURE` → compile → IdempotentExecutor → lock Source 并复核 Project → 按 Source Type 从代码 Registry 取得唯一 Descriptor → insert（`ON CONFLICT DO NOTHING` 后读取精确记录并比较 definition）→ 同时 update Adapter/Mapping selectors → append Audit → append Outbox → response。整个方法使用 `@Transactional`；Audit/Outbox/response 只包含 ID、Schema、Adapter Version 与 Mapping Version。

- [ ] **Step 4: 实现固定 422 与 Controller**

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

`ProblemHandler` 把 `MappingProfileInvalid` 映射为 `422 MAPPING_PROFILE_INVALID`，只输出固定 violation code，不输出 definition、Alias 或 Token。

- [ ] **Step 5: 验证并提交**

Run: `./backend/gradlew -p backend test --tests '*IssueMappingProfileActivationIntegrationTest' --tests '*PermissionMatrixTest'`

Expected: auth、idempotency、atomic rollback、redaction tests PASS。

Commit: `feat(m2): activate mapping profiles transactionally`

### Task 5: Profile-pinned `JiraIssueMapper` 与 Adapter Descriptor

**Files:**
- Create: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JiraIssueMapper.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSourceRuntime.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JiraCliPilotAdapter.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JiraCliPilotProperties.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/JiraCliPilotAdapterTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/JiraIssueMapperTest.kt`

- [ ] **Step 1: 写 Mapper 与 Descriptor RED tests**

测试精确匹配、未知 status/severity Warning、Profile mappingVersion 传播到 Issue/Page、已建立 Descriptor 的唯一版本 `jira-cli-pilot-adapter-v1`、支持 Schema `jira-mapping-profile/v1`，以及 Adapter 文件不再包含 `issue-mapping-v1` 或硬编码 status/severity Token。

- [ ] **Step 2: 运行 RED 测试**

Run: `./backend/gradlew -p backend test --tests '*JiraIssueMapperTest' --tests '*JiraCliPilotAdapterTest'`

Expected: FAIL，Mapper/Descriptor 缺失且旧硬编码 Map 仍存在。

- [ ] **Step 3: 实现 Mapper 与 Descriptor contract**

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

`JiraCliPilotRuntimeFactory.descriptor.adapterVersion` 是唯一常量；`open` 把 Profile 固定到新 `JiraIssueMapper` 后创建 Adapter。CLI executable version 仍作为 Transport Version，与 Adapter Version 分离。

- [ ] **Step 4: 删除 Adapter 硬编码 Map 并回归 transport**

`JiraCliPilotAdapter` 构造参数增加 `mapper` 和 `mappingVersion`；`parseOutput` 在调用 Mapper 前拒绝长度超过 120 的 raw status/severity；`normalize` 只调用 Mapper；`IssuePage.mappingVersion` 与 `NormalizedIssue.mappingVersion` 均取固定 Profile。保留原有 argv、五列、UTF-8、byte limit、timeout、process-tree cleanup 与只读测试。

- [ ] **Step 5: 验证并提交**

Run: `./backend/gradlew -p backend test --tests '*JiraIssueMapperTest' --tests '*JiraCliPilotAdapterTest' --tests '*IssueSourceContractTest'`

Expected: Mapper、Descriptor、transport 与共享 Contract tests PASS。

Commit: `refactor(m2): pin Jira adapter to mapping profile`

### Task 6: Runtime Registry 与 Pre-process Fail-closed Gate

**Files:**
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSourceRuntime.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueMappingProfile.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/IssueSyncJobWorker.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JiraCliPilotProperties.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSourceRuntimeRegistryTest.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSyncIntegrationTest.kt`

- [ ] **Step 1: 写五类固定失败与零 Process 调用 tests**

分别制造无 Profile、digest tamper、unsupported Schema、Adapter Version mismatch、Mapping Version mismatch。每个场景断言固定 code、Run/Job `FAILED`、Jira Process Runner 调用次数为零、successful Cursor 不变、没有硬编码 fallback。

- [ ] **Step 2: 运行 RED 测试**

Run: `./backend/gradlew -p backend test --tests '*IssueSourceRuntimeRegistryTest' --tests '*IssueSyncIntegrationTest'`

Expected: FAIL，Worker 仍从全局 `ObjectProvider<IssueSourcePort>` 选择 Port。

- [ ] **Step 3: 实现 Registry Gate**

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

Registry 固定验证顺序：按 Run sourceId 读取 Source 并复核 Project → 按 `sourceType` 唯一选择 Factory → Descriptor adapterVersion 对 Run → 按 `(sourceId, mappingVersion)` 读取 Profile → Profile Project/Source 对 Run → Codec 重算 digest → Schema support → compiled mappingVersion 对 Run → `factory.open(profile)`。任何失败在 `open` 返回前发生。

- [ ] **Step 4: Worker 改用 Run-pinned Registry**

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

移除 Worker 的 `ObjectProvider<IssueSourcePort>` 与 `ADAPTER_NOT_CONFIGURED` 多义 fallback。

- [ ] **Step 5: 验证并提交**

Run: `./backend/gradlew -p backend test --tests '*IssueSourceRuntimeRegistryTest' --tests '*IssueSyncIntegrationTest' --tests '*JiraCliPilotAdapterTest'`

Expected: 五类固定诊断、零 Process call、Cursor 保持与成功路径 tests PASS。

Commit: `feat(m2): enforce runtime version authority`

### Task 7: Source Lock、Run Version Pinning 与 A/B Race

**Files:**
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/IssueSyncRepository.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JdbcIssueSyncRepository.kt`
- Modify: `backend/src/main/kotlin/com/ricezhou/vsrqg/issue/application/StartIssueSync.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSyncIntegrationTest.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueMappingProfileActivationIntegrationTest.kt`

- [ ] **Step 1: 写并发 RED tests**

用两个独立事务和 latch 证明：activation 持有 Source lock 时 `StartIssueSync` 等待；Run A 固定 Profile A 后激活 B 不改变 Run A；新 Run B 固定 Profile B；Adapter Version 始终来自 Descriptor 对应的 Source snapshot；旧 `normalized_issue` digest 不变。

- [ ] **Step 2: 运行 RED 测试**

Run: `./backend/gradlew -p backend test --tests '*IssueSyncIntegrationTest' --tests '*IssueMappingProfileActivationIntegrationTest'`

Expected: FAIL，`StartIssueSync` 仍用无锁 `findSource` 的旧 snapshot。

- [ ] **Step 3: 在创建 Run 的事务内固定 Source**

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

`createAuthorized` 在生成 ID 和插入 Run 之前调用 `lockAuthorizedSource`，其余 Audit、Outbox 与 Job 写入保持当前单一路径；构造现有 `IssueSyncRunRecord` 时从锁定 Source 复制 `adapterVersion`/`mappingVersion`。`JdbcIssueSyncRepository.lockSource` 使用 `SELECT ... FROM issue_source WHERE id=:sourceId FOR UPDATE`。初始无锁读取只用于确定 Project authorization。

- [ ] **Step 4: 验证竞态、历史与回归**

Run: `./backend/gradlew -p backend test --tests '*IssueSyncIntegrationTest' --tests '*IssueMappingProfileActivationIntegrationTest' --tests '*M2MigrationConstraintTest'`

Expected: A/B race、事务原子性、历史 digest 与旧 M2 tests PASS。

Commit: `fix(m2): pin sync versions under source lock`

### Task 8: Security Regression、完整 Gate 与验收候选

**Files:**
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueSourceContractTest.kt`
- Modify: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/JiraCliPilotAdapterTest.kt`
- Create: `backend/src/test/kotlin/com/ricezhou/vsrqg/issue/IssueMappingSecurityTest.kt`
- Create after fixed implementation commits: `docs/governance/acceptance/records/2026-09-01-m2-2-mapping-authority-001.md`

- [ ] **Step 1: 补齐安全与 Fixture regression tests**

Fixture 只使用 `synthetic-open`、`synthetic-high` 等合成 Alias。安全测试收集 exception、Problem Details、Audit、Outbox、Job result 和 application logs，断言不存在 definition、Alias Token、Issue title、URL、CLI path、stdout/stderr 或 Credential；只允许 profile ID、Schema、Mapping Version 与固定 diagnostics。

- [ ] **Step 2: 运行目标与完整 Backend Gate**

Run: `pnpm run test:contracts; pnpm run test:acceptance; pnpm run verify:acceptance; ./backend/gradlew -p backend clean test bootJar`

Expected: contracts `operations=33`；acceptance tests/records PASS；全部 Gradle tests 与 `bootJar` PASS。

- [ ] **Step 3: 运行治理、敏感扫描与 diff review**

Run: `./scripts/verify-design-governance.ps1; git diff --check; rg -n 'jira-cli-pilot-v1|issue-mapping-v1|synthetic-open|synthetic-high' backend/src/main backend/src/main/resources`

Expected: governance PASS；diff clean；旧人工版本名与旧硬编码 mapping 无命中；合成 Alias 只允许存在于 tests，不得存在于 main/resources。

- [ ] **Step 4: 提交实现候选**

Commit: `test(m2): gate mapping profile authority`

记录中文与英文固定 implementation head：`git rev-parse HEAD`；推送后对两个固定 SHA 运行 `scripts/verify-language-branches.ps1 -Mode Pair`。只有 Pair Gate 和两条 GitHub Actions 成功，才复制 `docs/governance/acceptance/template.md` 创建 `M2-2-MAPPING-AUTHORITY-001` PENDING 记录；Subject Commit 使用刚解析的精确 SHA，不使用缩写或自引用。

- [ ] **Step 5: 提交验收候选但不预填 Owner 决定**

验收记录必须列出：八个实现提交、Contract/Gradle summary、V5 Migration、五类零 Process call、A/B race、security scan、Pair Gate、两条 CI Run/Artifact locator 与残余风险。metadata 固定为 `status: PENDING`、`owner: PENDING`、`decisionAt: PENDING`；不得把实现完成直接写为 `APPROVE`。

Commit: `docs(acceptance): submit M2.2 mapping authority gate`

## Spec 覆盖矩阵

| Spec requirement | Implementation tasks | Verification |
|---|---|---|
| 不可变 Project/Source Profile Authority | Task 2、4 | V5 constraint 与 activation integration tests |
| RFC 8785、SHA-256、Unicode 确定性 | Task 3 | Codec replay/bound/conflict tests |
| Adapter Version 代码单一权威 | Task 4、5、6 | Descriptor、selector 与 mismatch tests |
| 受鉴权、幂等、原子激活 | Task 1、4 | Permission、HTTP、Audit/Outbox rollback tests |
| Run 版本固定与 A/B race | Task 6、7 | Registry zero-call 与并发 integration tests |
| 未知值可见且无成功 fallback | Task 3、5、6 | Mapper Warning 与 fixed diagnostic tests |
| Migration、回滚与历史保护 | Task 2、7 | V4→V5、immutable 与 digest regression tests |
| 安全、部署与验收证据 | Task 8 | full Gate、scan、Pair Gate、CI 与 PENDING record |

## 实施结束边界

计划执行完成只表示 `M2-2-MAPPING-AUTHORITY-001` 候选可提交 Owner 验收。它不自动关闭 `M2-2-JIRA-E2E-SMOKE-001`：关闭仍需 Owner 另行授权单项目、最多 20 条、只读真实 Jira 复测，并且 status Mapping Warning 为零。若出现新未知状态，保持 `CONDITIONAL` 并新增 Profile 版本，不修改旧 Profile 或历史 Run。
