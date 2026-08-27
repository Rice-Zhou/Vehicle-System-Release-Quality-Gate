package com.ricezhou.vsrqg.shared.archive

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ricezhou.vsrqg.shared.adapter.archive.ArchiveCapabilityHealthIndicator
import com.ricezhou.vsrqg.shared.adapter.archive.NioS3ArchiveFileOperations
import com.ricezhou.vsrqg.shared.adapter.archive.ObjectProtectionSnapshot
import com.ricezhou.vsrqg.shared.adapter.archive.S3ArchiveAdapter
import com.ricezhou.vsrqg.shared.adapter.archive.S3ArchiveFileOperations
import com.ricezhou.vsrqg.shared.adapter.archive.S3ControlSnapshot
import com.ricezhou.vsrqg.shared.adapter.archive.S3Gateway
import com.ricezhou.vsrqg.shared.adapter.archive.canonicalDailyControlRecordBytes
import com.ricezhou.vsrqg.shared.application.archive.ArchiveAuthorization
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCapabilityReport
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCapabilityState
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCommand
import com.ricezhou.vsrqg.shared.application.archive.ArchiveIntegrityFailure
import com.ricezhou.vsrqg.shared.application.archive.ArchivePolicy
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.ArchiveUnavailable
import com.ricezhou.vsrqg.shared.application.archive.CapabilityCheck
import com.ricezhou.vsrqg.shared.application.archive.CapabilityProbeContext
import com.ricezhou.vsrqg.shared.application.archive.DailyControlRecord
import com.ricezhou.vsrqg.shared.application.archive.DailyControlSnapshot
import com.ricezhou.vsrqg.shared.application.archive.DeploymentMode
import com.ricezhou.vsrqg.shared.application.archive.EvaluateArchiveCapability
import com.ricezhou.vsrqg.shared.application.archive.MutationCheckResult
import com.ricezhou.vsrqg.shared.application.archive.RuntimeIdentityRef
import com.ricezhou.vsrqg.shared.application.archive.StoredObjectRef
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.Collections
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.actuate.health.Status

class S3ArchiveAdapterTest {
    @TempDir
    lateinit var tempDirectory: Path

    private val mapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `incomplete configuration returns fixed failed checks without provider calls`() {
        listOf(
            policy().copy(bucket = null),
            policy().copy(bucket = " "),
            policy().copy(accessOwner = null),
            policy().copy(accessOwner = " "),
            policy().copy(retentionPeriod = null),
            policy().copy(retentionPeriod = Duration.ZERO),
            policy().copy(retentionPeriod = Duration.ofSeconds(-1)),
        ).forEach { incomplete ->
            val gateway = FakeS3Gateway(mapper)

            val checks = adapter(gateway).probe(incomplete, context())

            assertFixedChecks(checks)
            assertThat(checks).allMatch { !it.passed }
            assertThat(gateway.identityTimeouts).isEmpty()
            assertThat(gateway.controlCalls).isEmpty()
        }
    }

    @Test
    fun `prefix that cannot fit every generated key fails probe without gateway calls`() {
        val gateway = FakeS3Gateway(mapper)
        val controlCompatibleButPayloadOneByteTooLong = "a".repeat(753) + "/"
        val generatedPayload = fixedWidthPayloadKey(controlCompatibleButPayloadOneByteTooLong)

        assertThat(generatedPayload.toByteArray(Charsets.UTF_8)).hasSize(1025)

        val checks = adapter(gateway).probe(
            policy().copy(objectPrefix = controlCompatibleButPayloadOneByteTooLong),
            context(),
        )

        assertFixedChecks(checks)
        assertThat(checks).allMatch { !it.passed }
        assertThat(gateway.identityTimeouts).isEmpty()
        assertThat(gateway.controlCalls).isEmpty()
    }

    @Test
    fun `identity failure is secret free and prevents controls`() {
        val secret = "arn:aws:iam::123456789012:role/private-role"
        val gateway = FakeS3Gateway(mapper).apply {
            identityFailure = ArchiveUnavailable("provider failure $secret")
        }

        val checks = adapter(gateway).probe(policy(), context())

        assertFixedChecks(checks)
        assertThat(checks.single { it.name == "identity" }.passed).isFalse()
        assertThat(checks.drop(1)).allMatch { !it.passed }
        assertThat(checks.map { it.detail }).containsOnly("not verified")
        assertThat(checks.toString()).doesNotContain(secret, POLICY_FINGERPRINT, PRINCIPAL_FINGERPRINT)
        assertThat(gateway.identityTimeouts).containsExactly(PROBE_TIMEOUT)
        assertThat(gateway.controlCalls).isEmpty()
    }

    @Test
    fun `invalid provider identity fails before controls`() {
        listOf(
            RuntimeIdentityRef(ArchiveProvider.FILESYSTEM_STAGING, PRINCIPAL_FINGERPRINT),
            RuntimeIdentityRef(ArchiveProvider.S3_COMPATIBLE, "A".repeat(64)),
            RuntimeIdentityRef(ArchiveProvider.S3_COMPATIBLE, "short"),
        ).forEach { invalidIdentity ->
            val gateway = FakeS3Gateway(mapper).apply { identity = invalidIdentity }

            val checks = adapter(gateway).probe(policy(), context())

            assertThat(checks.single { it.name == "identity" }.passed).isFalse()
            assertThat(gateway.controlCalls).isEmpty()
        }
    }

    @Test
    fun `probe passes exact identity bound keys UTC bounds and timeout to gateway`() {
        val gateway = FakeS3Gateway(mapper)

        val checks = adapter(gateway).probe(policy(), context())

        assertThat(checks).allMatch { it.passed }
        assertThat(gateway.identityTimeouts).containsExactly(PROBE_TIMEOUT)
        val call = gateway.controlCalls.single()
        assertThat(call.bucket).isEqualTo(BUCKET)
        assertThat(call.targetKey).isEqualTo(TARGET_KEY)
        assertThat(call.resultKey).isEqualTo(RESULT_KEY)
        assertThat(call.policyFingerprint).isEqualTo(POLICY_FINGERPRINT)
        assertThat(call.identity).isEqualTo(IDENTITY)
        assertThat(call.utcDate).isEqualTo(LocalDate.parse("2026-08-26"))
        assertThat(call.validUntil).isEqualTo(Instant.parse("2026-08-27T00:00:00Z"))
        assertThat(call.requiredRetainUntil).isEqualTo(Instant.parse("2026-08-28T12:00:00Z"))
        assertThat(call.timeout).isEqualTo(PROBE_TIMEOUT)
    }

    @Test
    fun `company HTTP endpoint with server side encryption remains unverified and down`() {
        val gateway = FakeS3Gateway(mapper)
        val adapter = adapter(gateway)
        val companyPolicy = policy().copy(
            mode = DeploymentMode.COMPANY,
            endpoint = URI("http://s3.internal"),
        )
        val evaluator = EvaluateArchiveCapability(listOf(adapter), TimeProvider { CHECKED_AT })

        val report = evaluator.evaluateReadiness(companyPolicy)
        val health = ArchiveCapabilityHealthIndicator(companyPolicy, evaluator).health()

        assertThat(report.state).isEqualTo(ArchiveCapabilityState.EXTERNAL_UNVERIFIED)
        assertThat(report.checks.single { it.name == "encryption" }.passed).isFalse()
        assertThat(health.status).isEqualTo(Status.DOWN)
        assertThat(gateway.controlCalls).hasSize(2)
    }

    @Test
    fun `archive rejects HTTP endpoint before payload upload when encryption is required`() {
        val gateway = FakeS3Gateway(mapper)
        val httpPolicy = policy().copy(endpoint = URI("http://s3.internal"))

        assertThatThrownBy { adapter(gateway).archive(command(), httpPolicy, authorization()) }
            .isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("S3 archive control is not verified")
        assertThat(gateway.filePuts).isEmpty()
        assertThat(gateway.jsonPuts).isEmpty()
    }

    @Test
    fun `HTTPS and native AWS transports can pass encryption check`() {
        listOf(null, URI("https://s3.internal")).forEach { endpoint ->
            val gateway = FakeS3Gateway(mapper)

            val checks = adapter(gateway).probe(policy().copy(endpoint = endpoint), context())

            assertThat(checks.single { it.name == "encryption" }.passed).isTrue()
            assertThat(checks).allMatch { it.passed }
        }
    }

    @Test
    fun `HTTP transport remains optional when encryption is not required`() {
        val gateway = FakeS3Gateway(mapper).apply {
            snapshotMutation = { it.copy(encrypted = false) }
        }

        val checks = adapter(gateway).probe(
            policy().copy(
                encryptionRequired = false,
                endpoint = URI("http://s3.internal"),
            ),
            context(),
        )

        assertThat(checks.single { it.name == "encryption" }.passed).isTrue()
        assertThat(checks).allMatch { it.passed }
    }

    @Test
    fun `control transport failure maps to generic failed checks`() {
        val gateway = FakeS3Gateway(mapper).apply {
            controlsFailure = ArchiveUnavailable("timeout at https://secret.internal")
        }

        val checks = adapter(gateway).probe(policy(), context())

        assertThat(checks.single { it.name == "identity" }.passed).isTrue()
        assertThat(checks.drop(1)).allMatch { !it.passed }
        assertThat(checks.map { it.detail }).containsOnly("verified", "not verified")
        assertThat(checks.toString()).doesNotContain("secret.internal", PRINCIPAL_FINGERPRINT)
    }

    @Test
    fun `each provider flag fails only its fixed capability check`() {
        val cases = listOf<Pair<String, (S3ControlSnapshot) -> S3ControlSnapshot>>(
            "connection" to { it.copy(reachable = false) },
            "encryption" to { it.copy(encrypted = false) },
            "privateAccess" to { it.copy(privateAccess = false) },
            "versioning" to { it.copy(versioningEnabled = false) },
        )

        cases.forEach { (failedName, mutation) ->
            val gateway = FakeS3Gateway(mapper).apply { snapshotMutation = mutation }
            val checks = adapter(gateway).probe(policy(), context())

            assertThat(checks.single { it.name == failedName }.passed).isFalse()
            assertThat(checks.filterNot { it.name == failedName }).allMatch { it.passed }
        }
    }

    @Test
    fun `bucket object lock flag alone cannot pass immutability`() {
        val gateway = FakeS3Gateway(mapper).apply {
            snapshotMutation = {
                it.copy(
                    objectLockEnabled = true,
                    controlObjectProtection = null,
                    dailyControl = null,
                )
            }
        }

        val checks = adapter(gateway).probe(policy(), context())

        assertThat(checks.single { it.name == "immutability" }.passed).isFalse()
        assertThat(checks.single { it.name == "retention" }.passed).isFalse()
    }

    @Test
    fun `probe accepts only exact COMPLIANCE object protection mode`() {
        listOf("UNKNOWN", "NOT_LOCKED", "GOVERNANCE", "compliance").forEach { unsupportedMode ->
            val gateway = FakeS3Gateway(mapper).apply {
                snapshotMutation = {
                    it.copy(
                        controlObjectProtection = ObjectProtectionSnapshot(
                            unsupportedMode,
                            it.controlObjectProtection?.retainUntil,
                        ),
                    )
                }
            }

            val checks = adapter(gateway).probe(policy(), context())

            assertThat(checks.single { it.name == "immutability" }.passed).isFalse()
            assertThat(checks.single { it.name == "retention" }.passed).isFalse()
        }
    }

    @Test
    fun `payload and receipt both using an unapproved protection mode cannot produce long term receipt`() {
        listOf("UNKNOWN", "NOT_LOCKED", "GOVERNANCE", "compliance").forEach { unsupportedMode ->
            val gateway = FakeS3Gateway(mapper).apply { protectionMode = unsupportedMode }

            assertThatThrownBy { adapter(gateway).archive(command(), policy(), authorization()) }
                .isInstanceOf(ArchiveUnavailable::class.java)
            assertThat(gateway.filePuts).hasSize(1)
            assertThat(gateway.jsonPuts).isEmpty()
        }
    }

    @Test
    fun `receipt exact protection rejects every unapproved mode after create only put`() {
        listOf("UNKNOWN", "NOT_LOCKED", "GOVERNANCE", "compliance").forEach { unsupportedMode ->
            val storage = FakeS3Gateway(mapper)
            val gateway = object : S3Gateway by storage {
                override fun headProtection(
                    source: StoredObjectRef,
                    timeout: Duration,
                ): ObjectProtectionSnapshot {
                    val actual = storage.headProtection(source, timeout)
                    return if (source.key.startsWith("acceptance/receipt/")) {
                        actual.copy(actualMode = unsupportedMode)
                    } else {
                        actual
                    }
                }
            }

            assertThatThrownBy { adapter(gateway).archive(command(), policy(), authorization()) }
                .isInstanceOf(ArchiveUnavailable::class.java)
            assertThat(storage.jsonPuts).hasSize(1)
            assertThat(storage.objects.keys).contains(storage.filePuts.single().key, storage.jsonPuts.single().key)
        }
    }

    @Test
    fun `retention rounds partial days upward and requires sufficient bucket default`() {
        val enough = FakeS3Gateway(mapper).apply { defaultRetentionDays = 2 }
        val short = FakeS3Gateway(mapper).apply { defaultRetentionDays = 1 }

        val enoughChecks = adapter(enough).probe(policy(), context())
        val shortChecks = adapter(short).probe(policy(), context())

        assertThat(enoughChecks.single { it.name == "retention" }.passed).isTrue()
        assertThat(shortChecks.single { it.name == "retention" }.passed).isFalse()
        assertThat(shortChecks.single { it.name == "immutability" }.passed).isTrue()
    }

    @Test
    fun `false policy flags cannot make missing or allowed physical controls pass probe`() {
        val mutations = listOf<(S3ControlSnapshot) -> S3ControlSnapshot>(
            {
                it.copy(
                    objectLockEnabled = false,
                    defaultRetentionDays = null,
                    controlObjectProtection = null,
                    dailyControl = null,
                )
            },
            { snapshot -> snapshot.withRecord { it.copy(overwrite = MutationCheckResult.ALLOWED) } },
            { snapshot -> snapshot.withRecord { it.copy(delete = MutationCheckResult.ALLOWED) } },
            { snapshot -> snapshot.withRecord { it.copy(bypass = MutationCheckResult.ALLOWED) } },
        )
        mutations.forEach { mutation ->
            val gateway = FakeS3Gateway(mapper).apply { snapshotMutation = mutation }
            val optionalPolicy = policy().copy(
                immutabilityRequired = false,
                retentionPolicyRequired = false,
            )

            val checks = adapter(gateway).probe(optionalPolicy, context())

            assertThat(checks.single { it.name == "immutability" }.passed).isFalse()
            assertThat(checks).anyMatch { !it.passed }
        }
    }

    @Test
    fun `false policy flags cannot authorize archive with missing or allowed daily controls`() {
        val mutations = listOf<(S3ControlSnapshot) -> S3ControlSnapshot>(
            { it.copy(dailyControl = null) },
            { snapshot -> snapshot.withRecord { it.copy(overwrite = MutationCheckResult.ALLOWED) } },
            { snapshot -> snapshot.withRecord { it.copy(delete = MutationCheckResult.ALLOWED) } },
            { snapshot -> snapshot.withRecord { it.copy(bypass = MutationCheckResult.ALLOWED) } },
        )
        mutations.forEach { mutation ->
            val gateway = FakeS3Gateway(mapper).apply { snapshotMutation = mutation }
            val optionalPolicy = policy().copy(
                immutabilityRequired = false,
                retentionPolicyRequired = false,
            )

            assertThatThrownBy { adapter(gateway).archive(command(), optionalPolicy, authorization()) }
                .isInstanceOf(ArchiveUnavailable::class.java)
            assertThat(gateway.filePuts).isEmpty()
        }
    }

    @Test
    fun `control protection and every daily snapshot binding fail closed`() {
        val mutations = listOf<(S3ControlSnapshot) -> S3ControlSnapshot>(
            { it.copy(controlObjectProtection = ObjectProtectionSnapshot(null, it.controlObjectProtection?.retainUntil)) },
            { it.copy(controlObjectProtection = ObjectProtectionSnapshot("COMPLIANCE", Instant.parse("2026-08-28T11:59:59Z"))) },
            { it.copy(dailyControl = null) },
            { it.withRecord { record -> record.copy(policyFingerprint = "b".repeat(64)) } },
            { it.withRecord { record -> record.copy(identity = record.identity.copy(principalFingerprint = "c".repeat(64))) } },
            { it.withRecord { record -> record.copy(utcDate = record.utcDate.minusDays(1)) } },
            { it.withRecord { record -> record.copy(validUntil = record.validUntil.minusSeconds(1)) } },
            { it.withRecord { record -> record.copy(target = record.target.copy(key = "acceptance/other/target.json")) } },
            { it.withResult { reference -> reference.copy(versionId = null) } },
            { it.withResult { reference -> reference.copy(versionId = "null") } },
            { it.withResult { reference -> reference.copy(key = "acceptance/other/result.json") } },
            { it.withResult { reference -> reference.copy(sha256 = "b".repeat(64)) } },
        )

        mutations.forEachIndexed { index, mutation ->
            val gateway = FakeS3Gateway(mapper).apply { snapshotMutation = mutation }
            val checks = adapter(gateway).probe(policy(), context())

            assertThat(checks.single { it.name == "immutability" }.passed)
                .describedAs("binding mutation %s", index)
                .isFalse()
        }
    }

    @Test
    fun `canonical result SHA and all denied mutations are required`() {
        listOf(MutationCheckResult.ALLOWED, MutationCheckResult.INDETERMINATE).forEach { failure ->
            listOf<(DailyControlRecord) -> DailyControlRecord>(
                { it.copy(overwrite = failure) },
                { it.copy(delete = failure) },
                { it.copy(bypass = failure) },
            ).forEach { mutateRecord ->
                val gateway = FakeS3Gateway(mapper).apply {
                    snapshotMutation = { snapshot -> snapshot.withRecord(mutateRecord) }
                }

                val checks = adapter(gateway).probe(policy(), context())

                assertThat(checks.single { it.name == "immutability" }.passed).isFalse()
            }
        }
    }

    @Test
    fun `sequential and concurrent identities are passed independently to gateway`() {
        val fingerprints = listOf("1".repeat(64), "2".repeat(64), "3".repeat(64), "4".repeat(64))
        val gateway = FakeS3Gateway(mapper).apply {
            identities = Collections.synchronizedList(
                fingerprints.map { RuntimeIdentityRef(ArchiveProvider.S3_COMPATIBLE, it) }.toMutableList(),
            )
        }
        val adapter = adapter(gateway)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val futures = (1..4).map { executor.submit(Callable { adapter.probe(policy(), context()) }) }
            futures.forEach { assertThat(it.get()).allMatch(CapabilityCheck::passed) }
        } finally {
            executor.shutdownNow()
        }

        assertThat(gateway.controlCalls.map { it.identity.principalFingerprint })
            .containsExactlyInAnyOrderElementsOf(fingerprints)
        assertThat(gateway.controlCalls.map { it.targetKey }).containsExactlyInAnyOrderElementsOf(
            fingerprints.map { fingerprint ->
                "acceptance/capability-probe/$POLICY_FINGERPRINT/$fingerprint/2026-08-26/target.json"
            },
        )
        assertThat(gateway.identityTimeouts).containsOnly(PROBE_TIMEOUT)
        assertThat(gateway.controlCalls.map(ControlCall::timeout)).containsOnly(PROBE_TIMEOUT)
    }

    @Test
    fun `archive rejects forged policy report provider and state before upload`() {
        val basePolicy = policy()
        val invalid = listOf(
            basePolicy.copy(provider = ArchiveProvider.FILESYSTEM_STAGING) to authorization(),
            basePolicy to authorization(report().copy(provider = ArchiveProvider.FILESYSTEM_STAGING)),
            basePolicy to authorization(report().copy(mode = DeploymentMode.COMPANY)),
            basePolicy to authorization(report().copy(state = ArchiveCapabilityState.EXTERNAL_UNVERIFIED)),
            basePolicy to authorization(report().copy(policyFingerprint = "INVALID")),
        )
        invalid.forEach { (policy, authorization) ->
            val gateway = FakeS3Gateway(mapper)

            assertThatThrownBy { adapter(gateway).archive(command(), policy, authorization) }
                .isInstanceOf(ArchiveUnavailable::class.java)
            assertThat(gateway.filePuts).isEmpty()
        }
    }

    @Test
    fun `archive rejects incomplete S3 policy before upload`() {
        listOf(
            policy().copy(bucket = null),
            policy().copy(accessOwner = null),
            policy().copy(retentionPeriod = null),
            policy().copy(retentionPeriod = Duration.ZERO),
            policy().copy(enabled = false),
        ).forEach { invalidPolicy ->
            val gateway = FakeS3Gateway(mapper)

            assertThatThrownBy { adapter(gateway).archive(command(), invalidPolicy, authorization()) }
                .isInstanceOf(ArchiveUnavailable::class.java)
            assertThat(gateway.filePuts).isEmpty()
        }
    }

    @Test
    fun `archive fresh control failure prevents payload upload`() {
        val gateway = FakeS3Gateway(mapper).apply {
            controlsFailure = ArchiveUnavailable("control timeout")
        }

        assertThatThrownBy { adapter(gateway).archive(command(), policy(), authorization()) }
            .isInstanceOf(ArchiveUnavailable::class.java)
        assertThat(gateway.identityTimeouts).containsExactly(PROBE_TIMEOUT)
        assertThat(gateway.controlCalls).hasSize(1)
        assertThat(gateway.filePuts).isEmpty()
    }

    @Test
    fun `archive rejects runtime identity switch after retaining uploaded objects`() {
        val gateway = FakeS3Gateway(mapper).apply {
            identities = Collections.synchronizedList(
                mutableListOf(
                    IDENTITY,
                    IDENTITY.copy(principalFingerprint = "c".repeat(64)),
                ),
            )
        }

        assertThatThrownBy { adapter(gateway).archive(command(), policy(), authorization()) }
            .isInstanceOf(ArchiveUnavailable::class.java)
        assertThat(gateway.controlCalls.single().identity).isEqualTo(IDENTITY)
        assertThat(gateway.filePuts).hasSize(1)
        assertThat(gateway.jsonPuts).hasSize(1)
        assertThat(gateway.objects.keys).contains(gateway.filePuts.single().key, gateway.jsonPuts.single().key)
    }

    @Test
    fun `archive rejects second identity attestation failure and preserves receipt`() {
        val delegate = FakeS3Gateway(mapper)
        var calls = 0
        val gateway = object : S3Gateway by delegate {
            override fun runtimeIdentity(timeout: Duration): RuntimeIdentityRef {
                calls += 1
                if (calls == 2) throw ArchiveUnavailable("identity timeout")
                return delegate.runtimeIdentity(timeout)
            }
        }

        assertThatThrownBy { adapter(gateway).archive(command(), policy(), authorization()) }
            .isInstanceOf(ArchiveUnavailable::class.java)
        assertThat(delegate.filePuts).hasSize(1)
        assertThat(delegate.jsonPuts).hasSize(1)
        assertThat(delegate.objects.keys).contains(delegate.filePuts.single().key, delegate.jsonPuts.single().key)
    }

    @Test
    fun `archive rejects control expiry across UTC midnight after preserving receipt`() {
        val gateway = FakeS3Gateway(mapper)
        val times = QueueTimeProvider(
            Instant.parse("2026-08-26T23:59:50Z"),
            Instant.parse("2026-08-26T23:59:55Z"),
            Instant.parse("2026-08-27T00:00:00Z"),
        )

        assertThatThrownBy { adapter(gateway, times).archive(command(), policy(), authorization()) }
            .isInstanceOf(ArchiveUnavailable::class.java)
        assertThat(gateway.controlCalls.single().utcDate).isEqualTo(LocalDate.parse("2026-08-26"))
        assertThat(gateway.controlCalls.single().validUntil).isEqualTo(Instant.parse("2026-08-27T00:00:00Z"))
        assertThat(gateway.filePuts).hasSize(1)
        assertThat(gateway.jsonPuts).hasSize(1)
    }

    @Test
    fun `archive rejects when final identity attestation itself crosses UTC midnight`() {
        val storage = FakeS3Gateway(mapper)
        val time = MutableTimeProvider(Instant.parse("2026-08-26T23:59:50Z"))
        var identityCalls = 0
        val gateway = object : S3Gateway by storage {
            override fun runtimeIdentity(timeout: Duration): RuntimeIdentityRef {
                identityCalls += 1
                if (identityCalls == 2) time.value = Instant.parse("2026-08-27T00:00:00Z")
                return storage.runtimeIdentity(timeout)
            }
        }

        assertThatThrownBy { adapter(gateway, time).archive(command(), policy(), authorization()) }
            .isInstanceOf(ArchiveUnavailable::class.java)
        assertThat(storage.filePuts).hasSize(1)
        assertThat(storage.jsonPuts).hasSize(1)
    }

    @Test
    fun `archive uses safe domain separated content addressed payload and receipt keys`() {
        val gateway = FakeS3Gateway(mapper)
        val result = adapter(gateway).archive(command(), policy(), authorization())

        val payloadCall = gateway.filePuts.single()
        val receiptCall = gateway.jsonPuts.single()
        assertThat(payloadCall.key).startsWith("acceptance/payload/").endsWith("/$SOURCE_SHA.zip")
        assertThat(payloadCall.key).doesNotContain("acceptance-01", "Commit-A", "artifact.zip")
        assertThat(receiptCall.key).startsWith("acceptance/receipt/").endsWith(".json")
        assertThat(receiptCall.key).doesNotContain("payload", "acceptance-01", "Commit-A", "artifact.zip")
        assertThat(payloadCall.key).isNotEqualTo(receiptCall.key)
        assertThat(result.receipt.acceptanceId).isEqualTo("acceptance-01")
        assertThat(result.receipt.sourceCommit).isEqualTo("Commit-A")
        assertThat(result.receipt.sourceArtifactId).isEqualTo("artifact.zip")
    }

    @Test
    fun `archive rejects a generated UTF8 key beyond the S3 limit before every gateway call`() {
        val gateway = FakeS3Gateway(mapper)
        val controlCompatibleButPayloadOneByteTooLong = "a".repeat(753) + "/"
        val generatedPayload = fixedWidthPayloadKey(controlCompatibleButPayloadOneByteTooLong)

        assertThat(generatedPayload.toByteArray(Charsets.UTF_8)).hasSize(1025)

        assertThatThrownBy {
            adapter(gateway).archive(
                command(),
                policy().copy(objectPrefix = controlCompatibleButPayloadOneByteTooLong),
                authorization(),
            )
        }.isInstanceOf(ArchiveUnavailable::class.java)
        assertThat(gateway.identityTimeouts).isEmpty()
        assertThat(gateway.controlCalls).isEmpty()
        assertThat(gateway.filePuts).isEmpty()
    }

    @Test
    fun `UTF8 prefix at the exact worst case key budget remains valid`() {
        val gateway = FakeS3Gateway(mapper)
        val exactBudgetPrefix = "界".repeat(250) + "aa/"
        val boundaryPolicy = policy().copy(objectPrefix = exactBudgetPrefix)

        val checks = adapter(gateway).probe(boundaryPolicy, context())
        val result = adapter(gateway).archive(command(), boundaryPolicy, authorization())

        assertThat(checks).allMatch { it.passed }
        assertThat(result.receipt.payload.key.toByteArray(Charsets.UTF_8)).hasSize(1024)
        assertThat(gateway.filePuts).hasSize(1)
        assertThat(gateway.jsonPuts).hasSize(1)
    }

    @Test
    fun `archive rejects dangerous dynamic IDs without provider calls`() {
        listOf("../escape", "a/b", "a\\b", "/absolute", "C:/absolute", ".", "..").forEach { dangerous ->
            val gateway = FakeS3Gateway(mapper)
            val dangerousCommand = command().copy(acceptanceId = dangerous)

            assertThatThrownBy { adapter(gateway).archive(dangerousCommand, policy(), authorization()) }
                .isInstanceOf(ArchiveUnavailable::class.java)
                .hasMessageNotContaining(dangerous)
            assertThat(gateway.filePuts).isEmpty()
        }
    }

    @Test
    fun `payload upload readback and head use exact ref and operation timeout`() {
        val gateway = FakeS3Gateway(mapper)

        val result = adapter(gateway).archive(command(), policy(), authorization())

        val payload = result.receipt.payload
        assertThat(payload.provider).isEqualTo(ArchiveProvider.S3_COMPATIBLE)
        assertThat(payload.bucket).isEqualTo(BUCKET)
        assertThat(payload.versionId).isNotBlank()
        assertThat(payload.sha256).isEqualTo(SOURCE_SHA)
        assertThat(payload.locator).isEqualTo("s3://$BUCKET/${payload.key}")
        assertThat(gateway.downloads.single().source).isEqualTo(payload)
        assertThat(gateway.heads.first().source).isEqualTo(payload)
        assertThat(gateway.filePuts.single().timeout).isEqualTo(OPERATION_TIMEOUT)
        assertThat(gateway.downloads.single().timeout).isEqualTo(OPERATION_TIMEOUT)
        assertThat(gateway.heads).allMatch { it.timeout == OPERATION_TIMEOUT }
        assertThat(gateway.jsonPuts.single().timeout).isEqualTo(OPERATION_TIMEOUT)
        assertThat(gateway.identityTimeouts).containsExactly(PROBE_TIMEOUT, PROBE_TIMEOUT)
        val archiveControl = gateway.controlCalls.single()
        assertThat(archiveControl.identity).isEqualTo(IDENTITY)
        assertThat(archiveControl.utcDate).isEqualTo(LocalDate.parse("2026-08-26"))
        assertThat(archiveControl.validUntil).isEqualTo(Instant.parse("2026-08-27T00:00:00Z"))
        assertThat(archiveControl.requiredRetainUntil).isEqualTo(Instant.parse("2026-08-28T12:00:00Z"))
        assertThat(archiveControl.timeout).isEqualTo(PROBE_TIMEOUT)
    }

    @Test
    fun `payload returned reference must bind exact version bucket key locator digest and size`() {
        val mutations = listOf<(StoredObjectRef) -> StoredObjectRef>(
            { it.copy(provider = ArchiveProvider.FILESYSTEM_STAGING) },
            { it.copy(bucket = "other-bucket") },
            { it.copy(key = "acceptance/payload/shadow.zip") },
            { it.copy(locator = "s3://other-bucket/${it.key}") },
            { it.copy(versionId = null) },
            { it.copy(versionId = "null") },
            { it.copy(sha256 = "c".repeat(64)) },
            { it.copy(sizeBytes = it.sizeBytes + 1) },
        )
        mutations.forEach { mutation ->
            val storage = FakeS3Gateway(mapper)
            val gateway = object : S3Gateway by storage {
                override fun putFileIfAbsent(
                    bucket: String,
                    key: String,
                    source: Path,
                    sha256: String,
                    timeout: Duration,
                ): StoredObjectRef = mutation(storage.putFileIfAbsent(bucket, key, source, sha256, timeout))
            }

            assertThatThrownBy { adapter(gateway).archive(command(), policy(), authorization()) }
                .isInstanceOf(ArchiveIntegrityFailure::class.java)
            assertThat(storage.jsonPuts).isEmpty()
        }
    }

    @Test
    fun `receipt returned reference must bind exact version bucket key locator digest and size`() {
        val mutations = listOf<(StoredObjectRef) -> StoredObjectRef>(
            { it.copy(provider = ArchiveProvider.NONE) },
            { it.copy(bucket = null) },
            { it.copy(key = "acceptance/receipt/shadow.json") },
            { it.copy(locator = "s3://other-bucket/${it.key}") },
            { it.copy(versionId = null) },
            { it.copy(sha256 = "c".repeat(64)) },
            { it.copy(sizeBytes = it.sizeBytes + 1) },
        )
        mutations.forEach { mutation ->
            val storage = FakeS3Gateway(mapper)
            val gateway = object : S3Gateway by storage {
                override fun putJsonIfAbsent(
                    bucket: String,
                    key: String,
                    bytes: ByteArray,
                    sha256: String,
                    timeout: Duration,
                ): StoredObjectRef = mutation(storage.putJsonIfAbsent(bucket, key, bytes, sha256, timeout))
            }

            assertThatThrownBy { adapter(gateway).archive(command(), policy(), authorization()) }
                .isInstanceOf(ArchiveIntegrityFailure::class.java)
            assertThat(storage.objects.keys).contains(storage.filePuts.single().key, storage.jsonPuts.single().key)
        }
    }

    @Test
    fun `source digest mismatch is rejected before upload and source remains`() {
        val gateway = FakeS3Gateway(mapper)
        val source = source()

        assertThatThrownBy {
            adapter(gateway).archive(
                command(source).copy(expectedSha256 = "c".repeat(64)),
                policy(),
                authorization(),
            )
        }.isInstanceOf(ArchiveIntegrityFailure::class.java)
        assertThat(Files.exists(source)).isTrue()
        assertThat(gateway.filePuts).isEmpty()
    }

    @Test
    fun `download and head timeout style failures remain fail closed without deleting objects`() {
        val downloadStorage = FakeS3Gateway(mapper)
        val downloadFailure = object : S3Gateway by downloadStorage {
            override fun download(source: StoredObjectRef, target: Path, timeout: Duration) {
                throw ArchiveUnavailable("download timeout")
            }
        }
        assertThatThrownBy { adapter(downloadFailure).archive(command(), policy(), authorization()) }
            .isInstanceOf(ArchiveUnavailable::class.java)
        assertThat(downloadStorage.objects.keys).contains(downloadStorage.filePuts.single().key)
        assertThat(downloadStorage.jsonPuts).isEmpty()

        val headStorage = FakeS3Gateway(mapper)
        val headFailure = object : S3Gateway by headStorage {
            override fun headProtection(source: StoredObjectRef, timeout: Duration): ObjectProtectionSnapshot {
                throw ArchiveUnavailable("head timeout")
            }
        }
        assertThatThrownBy { adapter(headFailure).archive(command(), policy(), authorization()) }
            .isInstanceOf(ArchiveUnavailable::class.java)
        assertThat(headStorage.objects.keys).contains(headStorage.filePuts.single().key)
        assertThat(headStorage.jsonPuts).isEmpty()
    }

    @Test
    fun `readback digest mismatch fails with integrity error and preserves source and payload`() {
        val gateway = FakeS3Gateway(mapper).apply { corruptDownload = true }
        val source = source()

        assertThatThrownBy { adapter(gateway).archive(command(source), policy(), authorization()) }
            .isInstanceOf(ArchiveIntegrityFailure::class.java)
        assertThat(Files.exists(source)).isTrue()
        assertThat(gateway.objects.keys).contains(gateway.filePuts.single().key)
        assertThat(gateway.jsonPuts).isEmpty()
    }

    @Test
    fun `owned download temp is deleted and cleanup failure is suppressed onto primary failure`() {
        val successGateway = FakeS3Gateway(mapper)
        adapter(successGateway).archive(command(), policy(), authorization())
        assertThat(successGateway.downloads).allMatch { !Files.exists(it.target) }

        val failingGateway = FakeS3Gateway(mapper).apply { corruptDownload = true }
        val cleanupFailure = object : S3ArchiveFileOperations by NioS3ArchiveFileOperations {
            override fun deleteIfExists(path: Path) {
                throw IOException("private cleanup path: $path")
            }
        }
        val failure = catchThrowable {
            adapter(failingGateway, files = cleanupFailure).archive(command(), policy(), authorization())
        }
        assertThat(failure).isInstanceOf(ArchiveIntegrityFailure::class.java)
        assertThat(failure.suppressed).hasSize(1)
        val suppressed = failure.suppressed.single()
        assertThat(suppressed).isInstanceOf(ArchiveUnavailable::class.java)
        assertThat(suppressed.message).isEqualTo("Archive download cleanup failed")
    }

    @Test
    fun `initial download target delete failure is cleaned by the owned finally path`() {
        val gateway = FakeS3Gateway(mapper)
        val ownedTarget = tempDirectory.resolve("owned-initial-delete-failure.partial")
        val operations = object : S3ArchiveFileOperations by NioS3ArchiveFileOperations {
            override fun createDownloadTarget(): Path = Files.write(ownedTarget, byteArrayOf(1))

            override fun prepareDownloadTarget(path: Path) {
                throw IOException("initial delete failed")
            }
        }

        assertThatThrownBy {
            adapter(gateway, files = operations).archive(command(), policy(), authorization())
        }.isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("Archive download target is unavailable")
            .hasMessageNotContaining(ownedTarget.toString())
        assertThat(Files.exists(ownedTarget)).isFalse()
        assertThat(gateway.downloads).isEmpty()
        assertThat(gateway.filePuts).hasSize(1)
    }

    @Test
    fun `programmer errors are not converted into provider failures`() {
        val gateway = FakeS3Gateway(mapper)
        val bug = object : S3ArchiveFileOperations by NioS3ArchiveFileOperations {
            override fun sha256(path: Path): String = throw AssertionError("programmer bug")
        }

        assertThatThrownBy { adapter(gateway, files = bug).archive(command(), policy(), authorization()) }
            .isInstanceOf(AssertionError::class.java)
            .hasMessage("programmer bug")
        assertThat(gateway.filePuts).isEmpty()
    }

    @Test
    fun `payload and receipt protection failures retain every committed object`() {
        listOf(
            ProtectionFailure.PAYLOAD_MODE,
            ProtectionFailure.PAYLOAD_RETENTION,
            ProtectionFailure.RECEIPT_MODE,
            ProtectionFailure.RECEIPT_RETENTION,
        ).forEach { failure ->
            val gateway = FakeS3Gateway(mapper).apply { protectionFailure = failure }
            val source = source()

            assertThatThrownBy { adapter(gateway).archive(command(source), policy(), authorization()) }
                .isInstanceOf(ArchiveUnavailable::class.java)
            assertThat(Files.exists(source)).isTrue()
            assertThat(gateway.filePuts).hasSize(1)
            if (failure.name.startsWith("RECEIPT")) {
                assertThat(gateway.jsonPuts).hasSize(1)
                assertThat(gateway.objects.keys).contains(gateway.filePuts.single().key, gateway.jsonPuts.single().key)
            } else {
                assertThat(gateway.jsonPuts).isEmpty()
            }
        }
    }

    @Test
    fun `upload and receipt failures preserve source and committed payload`() {
        val source = source()
        val uploadFailure = FakeS3Gateway(mapper).apply { failFilePut = true }
        assertThatThrownBy { adapter(uploadFailure).archive(command(source), policy(), authorization()) }
            .isInstanceOf(ArchiveUnavailable::class.java)
        assertThat(Files.exists(source)).isTrue()
        assertThat(uploadFailure.jsonPuts).isEmpty()

        val receiptFailure = FakeS3Gateway(mapper).apply { failJsonPut = true }
        assertThatThrownBy { adapter(receiptFailure).archive(command(source), policy(), authorization()) }
            .isInstanceOf(ArchiveUnavailable::class.java)
        assertThat(Files.exists(source)).isTrue()
        assertThat(receiptFailure.objects.keys).contains(receiptFailure.filePuts.single().key)
    }

    @Test
    fun `successful receipt is canonical self reference free and independently referenced`() {
        val gateway = FakeS3Gateway(mapper)

        val result = adapter(gateway).archive(command(), policy(), authorization())
        val bytes = gateway.jsonPuts.single().bytes

        assertThat(result.receipt.longTerm).isTrue()
        assertThat(result.receipt.policyFingerprint).isEqualTo(POLICY_FINGERPRINT)
        assertThat(result.receipt.capabilityCheckedAt).isEqualTo(CHECKED_AT)
        assertThat(result.receipt.archivedAt).isEqualTo(ARCHIVED_AT)
        assertThat(result.receipt.immutabilityControl).isEqualTo("COMPLIANCE")
        assertThat(result.receipt.retentionPolicy).isEqualTo(RETENTION.toString())
        assertThat(result.receiptReference.locator).isEqualTo(gateway.jsonPuts.single().reference.locator)
        assertThat(result.receiptReference.versionId).isEqualTo(gateway.jsonPuts.single().reference.versionId)
        assertThat(result.receiptReference.sha256).isEqualTo(sha256(bytes))
        assertThat(String(bytes)).doesNotContain(
            result.receiptReference.locator,
            requireNotNull(result.receiptReference.versionId),
            result.receiptReference.sha256,
            PRINCIPAL_FINGERPRINT,
        )
        assertThat(mapper.readTree(bytes).toString()).doesNotContain("receiptReference")
    }

    @Test
    fun `canonical receipt bytes and content addressed key match the fixed UTF8 vector`() {
        val gateway = FakeS3Gateway(mapper)

        adapter(gateway).archive(command(), policy(), authorization())

        val receiptPut = gateway.jsonPuts.single()
        val expected = """
            {
              "acceptanceId":"acceptance-01",
              "accessOwner":"release-governance",
              "archivedAt":"2026-08-26T14:00:00Z",
              "capabilityCheckedAt":"2026-08-26T13:14:15Z",
              "immutabilityControl":"COMPLIANCE",
              "longTerm":true,
              "payload":{
                "bucket":"archive-bucket",
                "key":"acceptance/payload/4cdd0bbb42c2bbd28ceccafc421484e6c618dd7a726746ab0b619bd0da803fc8/44c28166667143d031a3f9a8a8ee3c6a3590a12a64e363aa381e44ab0318ff91/e5989fd72f2083136d3eb60d82eb0e572da000f5c1ec0a740c58d4d246c88af6/10852187a3388ca9cd1cdb71dd71d18c0941830ebed67d5f217f177a8f963524.zip",
                "locator":"s3://archive-bucket/acceptance/payload/4cdd0bbb42c2bbd28ceccafc421484e6c618dd7a726746ab0b619bd0da803fc8/44c28166667143d031a3f9a8a8ee3c6a3590a12a64e363aa381e44ab0318ff91/e5989fd72f2083136d3eb60d82eb0e572da000f5c1ec0a740c58d4d246c88af6/10852187a3388ca9cd1cdb71dd71d18c0941830ebed67d5f217f177a8f963524.zip",
                "provider":"S3_COMPATIBLE",
                "sha256":"10852187a3388ca9cd1cdb71dd71d18c0941830ebed67d5f217f177a8f963524",
                "sizeBytes":26,
                "versionId":"version-c4ece560cec3"
              },
              "policyFingerprint":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "retentionPolicy":"PT36H",
              "sourceArtifactId":"artifact.zip",
              "sourceCommit":"Commit-A",
              "sourceRunId":"run-01",
              "sourceSha256":"10852187a3388ca9cd1cdb71dd71d18c0941830ebed67d5f217f177a8f963524",
              "verifier":"SHA-256"
            }
        """.trimIndent().lineSequence().joinToString("") { it.trim() }.toByteArray()
        assertThat(receiptPut.bytes).isEqualTo(expected)
        assertThat(receiptPut.key).isEqualTo("acceptance/receipt/${sha256(expected)}.json")
    }

    @Test
    fun `identical candidate replays exact refs while fresh candidate reuses payload and creates receipt`() {
        val gateway = FakeS3Gateway(mapper)
        val fixed = adapter(gateway, ARCHIVED_AT)

        val first = fixed.archive(command(), policy(), authorization())
        val exactReplay = fixed.archive(command(), policy(), authorization())
        val fresh = adapter(gateway, ARCHIVED_AT.plusSeconds(1)).archive(
            command(),
            policy(),
            authorization(report().copy(checkedAt = CHECKED_AT.plusSeconds(1))),
        )

        assertThat(exactReplay.receipt.payload).isEqualTo(first.receipt.payload)
        assertThat(exactReplay.receiptReference).isEqualTo(first.receiptReference)
        assertThat(fresh.receipt.payload).isEqualTo(first.receipt.payload)
        assertThat(fresh.receiptReference).isNotEqualTo(first.receiptReference)
        assertThat(gateway.filePuts.map { it.reference }.distinct()).containsExactly(first.receipt.payload)
        assertThat(gateway.jsonPuts.map { it.reference }.distinct()).hasSize(2)
        assertThat(gateway.objects.keys).contains(
            first.receipt.payload.key,
            first.receiptReference.locator.removePrefix("s3://$BUCKET/"),
            fresh.receiptReference.locator.removePrefix("s3://$BUCKET/"),
        )
    }

    private fun adapter(
        gateway: S3Gateway,
        now: Instant = ARCHIVED_AT,
        files: S3ArchiveFileOperations = NioS3ArchiveFileOperations,
    ) = S3ArchiveAdapter(gateway, mapper, TimeProvider { now }, files)

    private fun adapter(
        gateway: S3Gateway,
        timeProvider: TimeProvider,
        files: S3ArchiveFileOperations = NioS3ArchiveFileOperations,
    ) = S3ArchiveAdapter(gateway, mapper, timeProvider, files)

    private fun policy() = ArchivePolicy(
        mode = DeploymentMode.PILOT,
        enabled = true,
        checksumVerificationEnabled = true,
        encryptionRequired = true,
        privateAccessRequired = true,
        retentionPolicyRequired = true,
        immutabilityRequired = true,
        provider = ArchiveProvider.S3_COMPATIBLE,
        stagingRoot = null,
        endpoint = null,
        region = "us-east-1",
        bucket = BUCKET,
        objectPrefix = "acceptance/",
        accessOwner = "release-governance",
        retentionPeriod = RETENTION,
        probeTimeout = PROBE_TIMEOUT,
        operationTimeout = OPERATION_TIMEOUT,
    )

    private fun context() = CapabilityProbeContext(POLICY_FINGERPRINT, CHECKED_AT)

    private fun report() = ArchiveCapabilityReport(
        mode = DeploymentMode.PILOT,
        provider = ArchiveProvider.S3_COMPATIBLE,
        state = ArchiveCapabilityState.EXTERNAL_VERIFIED,
        policyFingerprint = POLICY_FINGERPRINT,
        checkedAt = CHECKED_AT,
        checks = CHECK_NAMES.map { CapabilityCheck(it, true, "verified") },
    )

    private fun authorization(report: ArchiveCapabilityReport = report()) = ArchiveAuthorization(report, Any())

    private fun command(source: Path = source()) = ArchiveCommand(
        acceptanceId = "acceptance-01",
        sourceArtifactId = "artifact.zip",
        sourceRunId = "run-01",
        sourceCommit = "Commit-A",
        source = source,
        expectedSha256 = SOURCE_SHA,
    )

    private fun source(): Path {
        val source = tempDirectory.resolve("source.zip")
        if (!Files.exists(source)) Files.write(source, SOURCE_BYTES)
        return source
    }

    private fun fixedWidthPayloadKey(prefix: String): String =
        prefix + "payload/" + List(4) { "0".repeat(64) }.joinToString("/") + ".zip"

    private fun assertFixedChecks(checks: List<CapabilityCheck>) {
        assertThat(checks.map { it.name }).containsExactlyElementsOf(CHECK_NAMES)
        assertThat(checks.map { it.detail }).allMatch { it == "verified" || it == "not verified" }
    }

    private fun S3ControlSnapshot.withRecord(
        mutation: (DailyControlRecord) -> DailyControlRecord,
    ): S3ControlSnapshot {
        val daily = requireNotNull(dailyControl)
        val record = mutation(daily.record)
        val canonical = canonicalDailyControlRecordBytes(mapper, record)
        return copy(
            dailyControl = DailyControlSnapshot(
                record,
                daily.resultReference.copy(sha256 = sha256(canonical), sizeBytes = canonical.size.toLong()),
            ),
        )
    }

    private fun S3ControlSnapshot.withResult(
        mutation: (StoredObjectRef) -> StoredObjectRef,
    ): S3ControlSnapshot {
        val daily = requireNotNull(dailyControl)
        return copy(dailyControl = daily.copy(resultReference = mutation(daily.resultReference)))
    }

    private class FakeS3Gateway(
        private val mapper: com.fasterxml.jackson.databind.ObjectMapper,
    ) : S3Gateway {
        var identity = IDENTITY
        var identities: MutableList<RuntimeIdentityRef>? = null
        var identityFailure: RuntimeException? = null
        var controlsFailure: RuntimeException? = null
        var snapshotMutation: (S3ControlSnapshot) -> S3ControlSnapshot = { it }
        var defaultRetentionDays = 2L
        var corruptDownload = false
        var failFilePut = false
        var failJsonPut = false
        var protectionFailure: ProtectionFailure? = null
        var protectionMode: String = "COMPLIANCE"
        val identityTimeouts = Collections.synchronizedList(mutableListOf<Duration>())
        val controlCalls = Collections.synchronizedList(mutableListOf<ControlCall>())
        val filePuts = Collections.synchronizedList(mutableListOf<FilePut>())
        val jsonPuts = Collections.synchronizedList(mutableListOf<JsonPut>())
        val downloads = Collections.synchronizedList(mutableListOf<DownloadCall>())
        val heads = Collections.synchronizedList(mutableListOf<HeadCall>())
        val objects = Collections.synchronizedMap(linkedMapOf<String, StoredBytes>())

        override fun runtimeIdentity(timeout: Duration): RuntimeIdentityRef {
            identityTimeouts += timeout
            identityFailure?.let { throw it }
            val queue = identities
            return if (queue == null) identity else synchronized(queue) { queue.removeFirst() }
        }

        override fun controls(
            bucket: String,
            targetKey: String,
            resultKey: String,
            policyFingerprint: String,
            identity: RuntimeIdentityRef,
            utcDate: LocalDate,
            requiredRetainUntil: Instant,
            validUntil: Instant,
            timeout: Duration,
        ): S3ControlSnapshot {
            val call = ControlCall(
                bucket,
                targetKey,
                resultKey,
                policyFingerprint,
                identity,
                utcDate,
                requiredRetainUntil,
                validUntil,
                timeout,
            )
            controlCalls += call
            controlsFailure?.let { throw it }
            val target = reference(targetKey, CONTROL_TARGET_SHA, CONTROL_TARGET_BYTES.size.toLong())
            val record = DailyControlRecord(
                policyFingerprint,
                identity,
                utcDate,
                validUntil,
                target,
                MutationCheckResult.DENIED_AS_EXPECTED,
                MutationCheckResult.DENIED_AS_EXPECTED,
                MutationCheckResult.DENIED_AS_EXPECTED,
            )
            val canonical = canonicalDailyControlRecordBytes(mapper, record)
            val resultRef = reference(resultKey, sha256(canonical), canonical.size.toLong())
            return snapshotMutation(
                S3ControlSnapshot(
                    reachable = true,
                    encrypted = true,
                    privateAccess = true,
                    versioningEnabled = true,
                    objectLockEnabled = true,
                    defaultRetentionDays = defaultRetentionDays,
                    controlObjectProtection = ObjectProtectionSnapshot("COMPLIANCE", requiredRetainUntil),
                    dailyControl = DailyControlSnapshot(record, resultRef),
                ),
            )
        }

        override fun putFileIfAbsent(
            bucket: String,
            key: String,
            source: Path,
            sha256: String,
            timeout: Duration,
        ): StoredObjectRef {
            if (failFilePut) throw ArchiveUnavailable("file put failed")
            val bytes = Files.readAllBytes(source)
            val ref = createOrReplay(key, bytes, sha256)
            filePuts += FilePut(key, timeout, ref)
            return ref
        }

        override fun download(source: StoredObjectRef, target: Path, timeout: Duration) {
            downloads += DownloadCall(source, target, timeout)
            val stored = objects[source.key] ?: throw ArchiveUnavailable("missing exact object")
            if (stored.reference != source) throw ArchiveUnavailable("version shadow")
            val bytes = if (corruptDownload) stored.bytes + byteArrayOf(0) else stored.bytes
            Files.write(target, bytes)
        }

        override fun putJsonIfAbsent(
            bucket: String,
            key: String,
            bytes: ByteArray,
            sha256: String,
            timeout: Duration,
        ): StoredObjectRef {
            if (failJsonPut) throw ArchiveUnavailable("receipt put failed")
            val ref = createOrReplay(key, bytes, sha256)
            jsonPuts += JsonPut(key, bytes.copyOf(), timeout, ref)
            return ref
        }

        override fun headProtection(source: StoredObjectRef, timeout: Duration): ObjectProtectionSnapshot {
            heads += HeadCall(source, timeout)
            val stored = objects[source.key] ?: throw ArchiveUnavailable("missing exact object")
            if (stored.reference != source) throw ArchiveUnavailable("version shadow")
            val receipt = source.key.startsWith("acceptance/receipt/")
            val failure = protectionFailure
            return when {
                failure == ProtectionFailure.PAYLOAD_MODE && !receipt -> ObjectProtectionSnapshot(null, SUFFICIENT_RETAIN_UNTIL)
                failure == ProtectionFailure.PAYLOAD_RETENTION && !receipt ->
                    ObjectProtectionSnapshot("COMPLIANCE", REQUIRED_ARCHIVE_RETAIN_UNTIL.minusSeconds(1))
                failure == ProtectionFailure.RECEIPT_MODE && receipt ->
                    ObjectProtectionSnapshot("GOVERNANCE", SUFFICIENT_RETAIN_UNTIL)
                failure == ProtectionFailure.RECEIPT_RETENTION && receipt ->
                    ObjectProtectionSnapshot("COMPLIANCE", REQUIRED_ARCHIVE_RETAIN_UNTIL.minusSeconds(1))
                else -> ObjectProtectionSnapshot(protectionMode, SUFFICIENT_RETAIN_UNTIL)
            }
        }

        private fun createOrReplay(key: String, bytes: ByteArray, digest: String): StoredObjectRef {
            if (sha256(bytes) != digest) throw ArchiveUnavailable("digest mismatch")
            val existing = objects[key]
            if (existing != null) {
                if (!existing.bytes.contentEquals(bytes) || existing.reference.sha256 != digest) {
                    throw ArchiveUnavailable("create-only conflict")
                }
                return existing.reference
            }
            val reference = reference(key, digest, bytes.size.toLong())
            objects[key] = StoredBytes(bytes.copyOf(), reference)
            return reference
        }

        private fun reference(key: String, digest: String, size: Long) = StoredObjectRef(
            ArchiveProvider.S3_COMPATIBLE,
            "s3://$BUCKET/$key",
            BUCKET,
            key,
            "version-${sha256(key.toByteArray()).take(12)}",
            digest,
            size,
        )
    }

    private data class ControlCall(
        val bucket: String,
        val targetKey: String,
        val resultKey: String,
        val policyFingerprint: String,
        val identity: RuntimeIdentityRef,
        val utcDate: LocalDate,
        val requiredRetainUntil: Instant,
        val validUntil: Instant,
        val timeout: Duration,
    )

    private data class FilePut(val key: String, val timeout: Duration, val reference: StoredObjectRef)
    private data class JsonPut(
        val key: String,
        val bytes: ByteArray,
        val timeout: Duration,
        val reference: StoredObjectRef,
    )
    private data class DownloadCall(val source: StoredObjectRef, val target: Path, val timeout: Duration)
    private data class HeadCall(val source: StoredObjectRef, val timeout: Duration)
    private data class StoredBytes(val bytes: ByteArray, val reference: StoredObjectRef)

    private class QueueTimeProvider(vararg times: Instant) : TimeProvider {
        private val values = times.toMutableList()
        override fun now(): Instant = values.removeFirst()
    }

    private class MutableTimeProvider(var value: Instant) : TimeProvider {
        override fun now(): Instant = value
    }

    private enum class ProtectionFailure {
        PAYLOAD_MODE,
        PAYLOAD_RETENTION,
        RECEIPT_MODE,
        RECEIPT_RETENTION,
    }

    private companion object {
        const val BUCKET = "archive-bucket"
        const val POLICY_FINGERPRINT =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val PRINCIPAL_FINGERPRINT =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val IDENTITY = RuntimeIdentityRef(ArchiveProvider.S3_COMPATIBLE, PRINCIPAL_FINGERPRINT)
        val CHECKED_AT: Instant = Instant.parse("2026-08-26T13:14:15Z")
        val ARCHIVED_AT: Instant = Instant.parse("2026-08-26T14:00:00Z")
        val RETENTION: Duration = Duration.ofHours(36)
        val REQUIRED_ARCHIVE_RETAIN_UNTIL: Instant = ARCHIVED_AT.plus(RETENTION)
        val SUFFICIENT_RETAIN_UNTIL: Instant = REQUIRED_ARCHIVE_RETAIN_UNTIL.plus(Duration.ofDays(1))
        val PROBE_TIMEOUT: Duration = Duration.ofSeconds(5)
        val OPERATION_TIMEOUT: Duration = Duration.ofSeconds(30)
        val SOURCE_BYTES = "immutable evidence payload".toByteArray()
        val SOURCE_SHA = sha256(SOURCE_BYTES)
        val CONTROL_TARGET_BYTES = "{\"purpose\":\"archive-capability-probe\",\"version\":1}".toByteArray()
        val CONTROL_TARGET_SHA = sha256(CONTROL_TARGET_BYTES)
        val CHECK_NAMES = listOf(
            "identity",
            "connection",
            "encryption",
            "privateAccess",
            "versioning",
            "immutability",
            "retention",
        )
        const val TARGET_KEY =
            "acceptance/capability-probe/$POLICY_FINGERPRINT/$PRINCIPAL_FINGERPRINT/2026-08-26/target.json"
        const val RESULT_KEY =
            "acceptance/capability-probe/$POLICY_FINGERPRINT/$PRINCIPAL_FINGERPRINT/2026-08-26/result.json"

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
