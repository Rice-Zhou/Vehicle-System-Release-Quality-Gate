package com.ricezhou.vsrqg.shared.archive

import com.ricezhou.vsrqg.shared.adapter.archive.ArchiveCapabilityConfiguration
import com.ricezhou.vsrqg.shared.adapter.archive.ArchiveCapabilityHealthIndicator
import com.ricezhou.vsrqg.shared.adapter.archive.NoneArchiveAdapter
import com.ricezhou.vsrqg.shared.application.archive.ArchiveAdapter
import com.ricezhou.vsrqg.shared.application.archive.ArchiveAuthorization
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCapabilityState
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCommand
import com.ricezhou.vsrqg.shared.application.archive.ArchivePolicy
import com.ricezhou.vsrqg.shared.application.archive.ArchiveProvider
import com.ricezhou.vsrqg.shared.application.archive.ArchiveResult
import com.ricezhou.vsrqg.shared.application.archive.ArchiveUnavailable
import com.ricezhou.vsrqg.shared.application.archive.CapabilityCheck
import com.ricezhou.vsrqg.shared.application.archive.CapabilityProbeContext
import com.ricezhou.vsrqg.shared.application.archive.DeploymentMode
import com.ricezhou.vsrqg.shared.application.archive.EvaluateArchiveCapability
import com.ricezhou.vsrqg.shared.application.archive.canonicalArchivePath
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.io.File
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatIllegalArgumentException
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumingThat
import org.junit.jupiter.api.Test
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.boot.actuate.health.Status
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.io.ClassPathResource

class ArchiveCapabilityTest {
    @Test
    fun `provider checks derive the truthful capability state`() {
        assertThat(evaluate(ArchiveProvider.NONE, passedCheck()).state)
            .isEqualTo(ArchiveCapabilityState.UNCONFIGURED)
        assertThat(evaluate(ArchiveProvider.FILESYSTEM_STAGING, passedCheck()).state)
            .isEqualTo(ArchiveCapabilityState.LOCAL_PILOT)
        assertThat(evaluate(ArchiveProvider.S3_COMPATIBLE, passedCheck(), failedCheck()).state)
            .isEqualTo(ArchiveCapabilityState.EXTERNAL_UNVERIFIED)
        assertThat(evaluate(ArchiveProvider.S3_COMPATIBLE, passedCheck()).state)
            .isEqualTo(ArchiveCapabilityState.EXTERNAL_VERIFIED)
    }

    @Test
    fun `an empty check list never passes`() {
        assertThat(evaluate(ArchiveProvider.FILESYSTEM_STAGING).state)
            .isEqualTo(ArchiveCapabilityState.UNCONFIGURED)
        assertThat(evaluate(ArchiveProvider.S3_COMPATIBLE).state)
            .isEqualTo(ArchiveCapabilityState.EXTERNAL_UNVERIFIED)
    }

    @Test
    fun `a missing adapter produces an explicit failed provider check`() {
        val evaluator = EvaluateArchiveCapability(emptyList(), fixedTimeProvider())

        val report = evaluator.evaluateReadiness(policy(provider = ArchiveProvider.S3_COMPATIBLE))

        assertThat(report.state).isEqualTo(ArchiveCapabilityState.EXTERNAL_UNVERIFIED)
        assertThat(report.checks).containsExactly(
            CapabilityCheck("provider", false, "No adapter is registered"),
        )
    }

    @Test
    fun `duplicate provider adapters are rejected`() {
        val first = CountingArchiveAdapter(ArchiveProvider.S3_COMPATIBLE, listOf(passedCheck()))
        val second = CountingArchiveAdapter(ArchiveProvider.S3_COMPATIBLE, listOf(passedCheck()))

        assertThatIllegalArgumentException()
            .isThrownBy { EvaluateArchiveCapability(listOf(first, second), fixedTimeProvider()) }
            .withMessage("Archive providers must be unique")
    }

    @Test
    fun `readiness and authorization each use a fresh probe and authorization carries that report`() {
        val adapter = CountingArchiveAdapter(ArchiveProvider.S3_COMPATIBLE, listOf(passedCheck()))
        val timeProvider = AdvancingTimeProvider()
        val evaluator = EvaluateArchiveCapability(listOf(adapter), timeProvider)
        val policy = policy(provider = ArchiveProvider.S3_COMPATIBLE)

        val readinessOne = evaluator.evaluateReadiness(policy)
        val readinessTwo = evaluator.evaluateReadiness(policy)

        assertThat(adapter.probeCount).isEqualTo(2)

        val authorizationOne = evaluator.authorizeArchive(policy)
        val authorizationTwo = evaluator.authorizeArchive(policy)

        assertThat(adapter.probeCount).isEqualTo(4)
        assertThat(readinessOne.checkedAt).isNotEqualTo(readinessTwo.checkedAt)
        assertThat(authorizationOne.report.checkedAt).isEqualTo(adapter.contexts[2].checkedAt)
        assertThat(authorizationOne.report.policyFingerprint).isEqualTo(adapter.contexts[2].policyFingerprint)
        assertThat(authorizationTwo.report.checkedAt).isEqualTo(adapter.contexts[3].checkedAt)
        assertThat(authorizationTwo.report.policyFingerprint).isEqualTo(adapter.contexts[3].policyFingerprint)
        assertThat(authorizationOne.report.checkedAt).isNotEqualTo(authorizationTwo.report.checkedAt)
        evaluator.requireIssued(authorizationOne)
        evaluator.requireIssued(authorizationTwo)
    }

    @Test
    fun `authorization issued by another identity is rejected`() {
        val evaluator = EvaluateArchiveCapability(
            listOf(CountingArchiveAdapter(ArchiveProvider.NONE, listOf(failedCheck()))),
            fixedTimeProvider(),
        )
        val fakeReport = evaluator.authorizeArchive(policy(provider = ArchiveProvider.NONE)).report.copy(
            state = ArchiveCapabilityState.EXTERNAL_VERIFIED,
            policyFingerprint = "f".repeat(64),
            checkedAt = FIXED_TIME.plusSeconds(30),
            checks = listOf(passedCheck()),
        )
        val forged = ArchiveAuthorization(fakeReport, Any())

        assertThatIllegalArgumentException()
            .isThrownBy { evaluator.requireIssued(forged) }
            .withMessage("Archive authorization was not issued by the trusted evaluator")
    }

    @Test
    fun `fingerprint is stable lowercase sha256 and changes with every policy field`() {
        val base = policy(provider = ArchiveProvider.S3_COMPATIBLE)
        val evaluator = evaluatorForAllProviders()
        val fingerprint = evaluator.evaluateReadiness(base).policyFingerprint
        val variants = linkedMapOf(
            "mode" to base.copy(mode = DeploymentMode.COMPANY),
            "enabled" to base.copy(enabled = false),
            "checksumVerificationEnabled" to base.copy(checksumVerificationEnabled = false),
            "encryptionRequired" to base.copy(encryptionRequired = false),
            "privateAccessRequired" to base.copy(privateAccessRequired = false),
            "retentionPolicyRequired" to base.copy(retentionPolicyRequired = false),
            "immutabilityRequired" to base.copy(immutabilityRequired = false),
            "provider" to base.copy(provider = ArchiveProvider.FILESYSTEM_STAGING),
            "stagingRoot" to base.copy(stagingRoot = Path.of("D:/other-staging")),
            "endpoint" to base.copy(endpoint = URI("https://other-archive.example.test")),
            "region" to base.copy(region = "cn-south-1"),
            "bucket" to base.copy(bucket = "other-bucket"),
            "objectPrefix" to base.copy(objectPrefix = "other-prefix/"),
            "accessOwner" to base.copy(accessOwner = "other-owner"),
            "retentionPeriod" to base.copy(retentionPeriod = Duration.ofDays(366)),
            "probeTimeout" to base.copy(probeTimeout = Duration.ofSeconds(6)),
            "operationTimeout" to base.copy(operationTimeout = Duration.ofSeconds(31)),
        )

        assertThat(fingerprint).matches("^[0-9a-f]{64}$")
        assertThat(fingerprint).isEqualTo("092ca9b6418b38ca1d472972508977ff8ca449a1c85e4dded3a2e17016bb0eb6")
        assertThat(evaluator.evaluateReadiness(base).policyFingerprint).isEqualTo(fingerprint)
        variants.forEach { (field, variant) ->
            assertThat(evaluator.evaluateReadiness(variant).policyFingerprint)
                .describedAs(field)
                .isNotEqualTo(fingerprint)
        }
    }

    @Test
    fun `fingerprint canonicalization length prefixes fields and preserves path element identity`() {
        val evaluator = evaluatorForAllProviders()
        val first = policy().copy(region = "x", bucket = "bucket=y")
        val second = policy().copy(region = "xbucket=", bucket = "y")
        val absentPath = canonicalArchivePath(null)
        val emptyPath = canonicalArchivePath(Path.of(""))
        val currentDirectory = canonicalArchivePath(Path.of("."))
        val firstSegments = canonicalArchivePath(Path.of("a", "bc"))
        val secondSegments = canonicalArchivePath(Path.of("ab", "c"))

        assertThat(evaluator.evaluateReadiness(first).policyFingerprint)
            .isNotEqualTo(evaluator.evaluateReadiness(second).policyFingerprint)
        assertThat(absentPath).isEqualTo("0")
        assertThat(emptyPath).startsWith("1")
        assertThat(emptyPath).isEqualTo(currentDirectory)
        assertThat(firstSegments).isNotEqualTo(secondSegments)
        assertThat(evaluator.evaluateReadiness(policy().copy(stagingRoot = null)).policyFingerprint)
            .isNotEqualTo(evaluator.evaluateReadiness(policy().copy(stagingRoot = Path.of(""))).policyFingerprint)
        assumingThat(File.separatorChar == '\\') {
            val driveRelative = Path.of("D:archive")
            val driveAbsolute = Path.of("D:/archive")

            assertThat(canonicalArchivePath(driveRelative)).isNotEqualTo(canonicalArchivePath(driveAbsolute))
            assertThat(canonicalArchivePath(Path.of("D:/"))).isNotEqualTo(canonicalArchivePath(driveRelative))
            assertThat(evaluator.evaluateReadiness(policy().copy(stagingRoot = driveRelative)).policyFingerprint)
                .isNotEqualTo(evaluator.evaluateReadiness(policy().copy(stagingRoot = driveAbsolute)).policyFingerprint)
        }
        assumingThat(File.separatorChar == '/') {
            val literalBackslash = Path.of("/srv/archive\\a")
            val structuralSeparator = Path.of("/srv/archive/a")

            assertThat(canonicalArchivePath(Path.of("/"))).isNotEqualTo(emptyPath)
            assertThat(canonicalArchivePath(literalBackslash))
                .isNotEqualTo(canonicalArchivePath(structuralSeparator))
            assertThat(evaluator.evaluateReadiness(policy().copy(stagingRoot = literalBackslash)).policyFingerprint)
                .isNotEqualTo(
                    evaluator.evaluateReadiness(policy().copy(stagingRoot = structuralSeparator)).policyFingerprint,
                )
        }
    }

    @Test
    fun `health probes every call and pilot stays up with the actual state`() {
        val adapter = CountingArchiveAdapter(ArchiveProvider.S3_COMPATIBLE, listOf(failedCheck()))
        val evaluator = EvaluateArchiveCapability(listOf(adapter), AdvancingTimeProvider())
        val indicator = ArchiveCapabilityHealthIndicator(
            policy(mode = DeploymentMode.PILOT, enabled = false),
            evaluator,
        )

        val first = indicator.health()
        val second = indicator.health()

        assertThat(adapter.probeCount).isEqualTo(2)
        assertThat(first.status).isEqualTo(Status.UP)
        assertThat(second.status).isEqualTo(Status.UP)
        assertThat(first.details["state"]).isEqualTo(ArchiveCapabilityState.EXTERNAL_UNVERIFIED.name)
        assertThat(first.details["checkedAt"]).isNotEqualTo(second.details["checkedAt"])
    }

    @Test
    fun `company health is up only when enabled and externally verified`() {
        val verified = companyHealth(enabled = true, checks = listOf(passedCheck()))
        val unverified = companyHealth(enabled = true, checks = listOf(failedCheck()))
        val disabled = companyHealth(enabled = false, checks = listOf(passedCheck()))

        assertThat(verified.status).isEqualTo(Status.UP)
        assertThat(unverified.status).isEqualTo(Status.DOWN)
        assertThat(disabled.status).isEqualTo(Status.DOWN)
        assertThat(disabled.details["state"]).isEqualTo(ArchiveCapabilityState.EXTERNAL_VERIFIED.name)
    }

    @Test
    fun `health details expose only the capability report allowlist`() {
        val policy = policy(
            mode = DeploymentMode.COMPANY,
            endpoint = URI("https://archive.example.test/internal-endpoint"),
        ).copy(
            region = "sensitive-region",
            bucket = "sensitive-bucket",
            accessOwner = "sensitive-owner",
        )
        val opaqueCredential = "opaque-secret-value-without-a-sensitive-label"
        val internalEndpoint = "dial tcp archive.internal.example:9000: i-o timeout"
        val indicator = ArchiveCapabilityHealthIndicator(
            policy,
            EvaluateArchiveCapability(
                listOf(
                    CountingArchiveAdapter(
                        ArchiveProvider.S3_COMPATIBLE,
                        listOf(
                            CapabilityCheck("connection", false, opaqueCredential),
                            CapabilityCheck(internalEndpoint, false, "presigned-url-value"),
                        ),
                    ),
                ),
                fixedTimeProvider(),
            ),
        )

        val health = indicator.health()
        val detailsText = health.details.toString()

        assertThat(health.details.keys).containsExactlyInAnyOrder(
            "mode",
            "provider",
            "state",
            "policyFingerprint",
            "checkedAt",
            "checks",
        )
        @Suppress("UNCHECKED_CAST")
        val checks = health.details["checks"] as List<Map<String, Any>>
        assertThat(checks).allSatisfy { check ->
            assertThat(check.keys).containsExactlyInAnyOrder("name", "passed", "detail")
        }
        assertThat(checks[0]).containsEntry("name", "connection")
            .containsEntry("passed", false)
            .containsEntry("detail", "not verified")
        assertThat(checks[1]).containsEntry("name", "unrecognized")
            .containsEntry("passed", false)
            .containsEntry("detail", "not verified")
        assertThat(detailsText)
            .doesNotContain(
                "internal-endpoint",
                "sensitive-region",
                "sensitive-bucket",
                "sensitive-owner",
                opaqueCredential,
                internalEndpoint,
                "presigned-url-value",
            )
    }

    @Test
    fun `none adapter reports unavailable and never archives`() {
        val adapter = NoneArchiveAdapter()
        val policy = policy(provider = ArchiveProvider.NONE)
        val context = CapabilityProbeContext("a".repeat(64), FIXED_TIME)
        val authorization = ArchiveAuthorization(
            evaluate(ArchiveProvider.NONE, failedCheck()),
            Any(),
        )

        assertThat(adapter.probe(policy, context)).containsExactly(
            CapabilityCheck("provider", false, "Archive provider is not configured"),
        )
        assertThatThrownBy {
            adapter.archive(command(), policy, authorization)
        }.isInstanceOf(ArchiveUnavailable::class.java)
            .hasMessage("Archive provider is not configured")
    }

    @Test
    fun `spring wiring exposes the internal evaluator and exact health bean name`() {
        ApplicationContextRunner()
            .withBean(ArchivePolicy::class.java, { policy(provider = ArchiveProvider.NONE) })
            .withBean(TimeProvider::class.java, { fixedTimeProvider() })
            .withUserConfiguration(
                ArchiveCapabilityConfiguration::class.java,
                ArchiveCapabilityHealthIndicator::class.java,
            )
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).hasSingleBean(EvaluateArchiveCapability::class.java)
                assertThat(context).hasBean("archiveCapability")
                assertThat(context.getBean("archiveCapability")).isInstanceOf(HealthIndicator::class.java)
            }
    }

    @Test
    fun `archive capability is appended only to readiness health group`() {
        val properties = YamlPropertySourceLoader()
            .load("application", ClassPathResource("application.yml"))
            .first()
        val readiness = requireNotNull(
            properties.getProperty("management.endpoint.health.group.readiness.include"),
        ).toString()
            .split(',')
            .map(String::trim)
        val liveness = properties.getProperty("management.endpoint.health.group.liveness.include")
            ?.toString()
            .orEmpty()

        assertThat(readiness).contains("readinessState", "archiveCapability")
        assertThat(liveness).doesNotContain("archiveCapability")
    }

    private fun evaluate(provider: ArchiveProvider, vararg checks: CapabilityCheck) =
        EvaluateArchiveCapability(
            listOf(CountingArchiveAdapter(provider, checks.toList())),
            fixedTimeProvider(),
        ).evaluateReadiness(policy(provider = provider))

    private fun evaluatorForAllProviders(): EvaluateArchiveCapability =
        EvaluateArchiveCapability(
            ArchiveProvider.entries.map { provider ->
                CountingArchiveAdapter(provider, listOf(passedCheck()))
            },
            fixedTimeProvider(),
        )

    private fun companyHealth(enabled: Boolean, checks: List<CapabilityCheck>) =
        ArchiveCapabilityHealthIndicator(
            policy(mode = DeploymentMode.COMPANY, enabled = enabled),
            EvaluateArchiveCapability(
                listOf(CountingArchiveAdapter(ArchiveProvider.S3_COMPATIBLE, checks)),
                fixedTimeProvider(),
            ),
        ).health()

    private fun policy(
        mode: DeploymentMode = DeploymentMode.PILOT,
        enabled: Boolean = true,
        provider: ArchiveProvider = ArchiveProvider.S3_COMPATIBLE,
        endpoint: URI? = URI("https://archive.example.test"),
    ) = ArchivePolicy(
        mode = mode,
        enabled = enabled,
        checksumVerificationEnabled = true,
        encryptionRequired = true,
        privateAccessRequired = true,
        retentionPolicyRequired = true,
        immutabilityRequired = true,
        provider = provider,
        stagingRoot = null,
        endpoint = endpoint,
        region = "cn-north-1",
        bucket = "vsrqg-archive",
        objectPrefix = "acceptance/",
        accessOwner = "release-governance",
        retentionPeriod = Duration.ofDays(365),
        probeTimeout = Duration.ofSeconds(5),
        operationTimeout = Duration.ofSeconds(30),
    )

    private fun command() = ArchiveCommand(
        acceptanceId = "acceptance-1",
        sourceArtifactId = "artifact-1",
        sourceRunId = "run-1",
        sourceCommit = "0123456789abcdef",
        source = Path.of("artifact.zip"),
        expectedSha256 = "b".repeat(64),
    )

    private fun passedCheck() = CapabilityCheck("connection", true, "verified")

    private fun failedCheck() = CapabilityCheck("connection", false, "unavailable")

    private fun fixedTimeProvider() = TimeProvider { FIXED_TIME }

    private class AdvancingTimeProvider : TimeProvider {
        private var invocation = 0L

        override fun now(): Instant = FIXED_TIME.plusSeconds(invocation++)
    }

    private class CountingArchiveAdapter(
        override val provider: ArchiveProvider,
        private val checks: List<CapabilityCheck>,
    ) : ArchiveAdapter {
        var probeCount = 0
            private set
        val contexts = mutableListOf<CapabilityProbeContext>()

        override fun probe(policy: ArchivePolicy, context: CapabilityProbeContext): List<CapabilityCheck> {
            probeCount += 1
            contexts += context
            return checks
        }

        override fun archive(
            command: ArchiveCommand,
            policy: ArchivePolicy,
            authorization: ArchiveAuthorization,
        ): ArchiveResult = throw UnsupportedOperationException("Not used by capability tests")
    }

    private companion object {
        val FIXED_TIME: Instant = Instant.parse("2026-08-26T06:00:00Z")
    }
}
