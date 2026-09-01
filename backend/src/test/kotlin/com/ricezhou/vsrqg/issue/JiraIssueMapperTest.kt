package com.ricezhou.vsrqg.issue

import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.ricezhou.vsrqg.issue.adapter.FixedIssueSourceDescriptorRegistry
import com.ricezhou.vsrqg.issue.adapter.JiraCliPilotAdapter
import com.ricezhou.vsrqg.issue.adapter.JiraCliPilotProperties
import com.ricezhou.vsrqg.issue.adapter.JiraCliPilotRuntimeFactory
import com.ricezhou.vsrqg.issue.adapter.JiraIssueMapper
import com.ricezhou.vsrqg.issue.adapter.JiraProcessResult
import com.ricezhou.vsrqg.issue.adapter.JiraProcessRunner
import com.ricezhou.vsrqg.issue.adapter.isValidMappingTokenInput
import com.ricezhou.vsrqg.issue.application.CompiledIssueMappingProfile
import com.ricezhou.vsrqg.issue.domain.IssueFilter
import com.ricezhou.vsrqg.issue.domain.IssueMappingWarning
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class JiraIssueMapperTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `maps only exact normalized aliases from the pinned profile`() {
        val mapper = JiraIssueMapper(profile())

        assertThat(mapper.status("  Ready FOR release  ")).isEqualTo(IssueStatus.RESOLVED to null)
        assertThat(mapper.severity(" MAJOR ")).isEqualTo(IssueSeverity.HIGH to null)
        assertThat(mapper.status("Ready for release candidate"))
            .isEqualTo(IssueStatus.UNKNOWN to IssueMappingWarning.UNKNOWN_STATUS)
        assertThat(mapper.severity("Major impact"))
            .isEqualTo(IssueSeverity.UNKNOWN to IssueMappingWarning.UNKNOWN_SEVERITY)
    }

    @Test
    fun `unknown status and severity produce their bounded warnings`() {
        val mapper = JiraIssueMapper(profile())

        assertThat(mapper.status("unmapped workflow state"))
            .isEqualTo(IssueStatus.UNKNOWN to IssueMappingWarning.UNKNOWN_STATUS)
        assertThat(mapper.severity("unmapped priority"))
            .isEqualTo(IssueSeverity.UNKNOWN to IssueMappingWarning.UNKNOWN_SEVERITY)
    }

    @Test
    fun `mapping token legality has one shared input boundary`() {
        listOf("Open", " x ", "x".repeat(120)).forEach { valid ->
            assertThat(isValidMappingTokenInput(valid)).describedAs("valid token").isTrue()
        }
        listOf("", "\u2003\u2002", "line\nfeed", "x".repeat(121)).forEach { invalid ->
            assertThat(isValidMappingTokenInput(invalid)).describedAs("invalid token").isFalse()
        }
    }

    @Test
    fun `runtime factory exposes the single Jira descriptor and pins profile version to page and issue`() {
        val executable = Files.createFile(tempDir.resolve("jira-cli.bin")).toAbsolutePath()
        val compiled = profile()
        val output = listOf(
            "SAFE-1",
            "Profile mapped issue",
            "Ready for release",
            "Major",
            "2026-08-28T10:10:00Z",
        ).joinToString("\u241f").toByteArray(StandardCharsets.UTF_8)
        val factory = JiraCliPilotRuntimeFactory(
            properties = JiraCliPilotProperties(true, executable.toString(), "SAFE", 20, Duration.ofSeconds(15)),
            processRunner = JiraProcessRunner { _, _, _ -> JiraProcessResult(0, output, timedOut = false) },
            observedAt = { java.time.Instant.parse("2026-08-28T10:16:00Z") },
        )

        val descriptor = factory.descriptor
        val registered = FixedIssueSourceDescriptorRegistry().require("JIRA")
        val page = factory.open(compiled).fetchChanges(null, IssueFilter(), 20)

        assertThat(descriptor).isEqualTo(registered)
        assertThat(descriptor.adapterVersion).isEqualTo("jira-cli-pilot-adapter-v1")
        assertThat(descriptor.supportedMappingSchemas).containsExactly("jira-mapping-profile/v1")
        assertThat(page.mappingVersion).isEqualTo(MAPPING_VERSION)
        assertThat(page.issues.single().mappingVersion).isEqualTo(MAPPING_VERSION)
        assertThat(page.issues.single().status).isEqualTo(IssueStatus.RESOLVED)
        assertThat(page.issues.single().severity).isEqualTo(IssueSeverity.HIGH)
    }

    @Test
    fun `adapter source contains no fallback mapping version or workflow token map`() {
        val sourcePath = generateSequence(
            Path.of(JiraIssueMapperTest::class.java.protectionDomain.codeSource.location.toURI()).toAbsolutePath(),
        ) { it.parent }
            .map { it.resolve("src/main/kotlin/com/ricezhou/vsrqg/issue/adapter/JiraCliPilotAdapter.kt") }
            .first(Files::isRegularFile)
        val source = Files.readString(sourcePath)

        assertThat(source).doesNotContain(
            "issue-mapping-v1",
            "private fun mapStatus",
            "private fun mapSeverity",
            "\"open\", \"to do\"",
            "\"highest\", \"critical\"",
        )
    }

    @Test
    fun `adapter rejects a mapping version that differs from its mapper profile`() {
        val executable = Files.createFile(tempDir.resolve("jira-cli.bin")).toAbsolutePath()
        val properties = JiraCliPilotProperties(true, executable.toString(), "SAFE", 20, Duration.ofSeconds(15))

        assertThatThrownBy {
            JiraCliPilotAdapter(
                properties = properties,
                processRunner = JiraProcessRunner { _, _, _ -> error("runner must not execute") },
                mapper = JiraIssueMapper(profile()),
                mappingVersion = "sha256:${"b".repeat(64)}",
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("MAPPING_VERSION_MISMATCH")
    }

    @Test
    fun `custom profile never falls back to legacy Open and High mappings`() {
        val executable = Files.createFile(tempDir.resolve("jira-cli.bin")).toAbsolutePath()
        val output = listOf(
            "SAFE-1",
            "No fallback issue",
            "Open",
            "High",
            "2026-08-28T10:10:00Z",
        ).joinToString("\u241f").toByteArray(StandardCharsets.UTF_8)
        val adapter = JiraCliPilotAdapter(
            properties = JiraCliPilotProperties(true, executable.toString(), "SAFE", 20, Duration.ofSeconds(15)),
            processRunner = JiraProcessRunner { _, _, _ -> JiraProcessResult(0, output, timedOut = false) },
            mapper = JiraIssueMapper(profile()),
            mappingVersion = MAPPING_VERSION,
        )

        val issue = adapter.fetchChanges(null, IssueFilter(), 20).issues.single()

        assertThat(issue.status).isEqualTo(IssueStatus.UNKNOWN)
        assertThat(issue.severity).isEqualTo(IssueSeverity.UNKNOWN)
        assertThat(issue.warnings).containsExactlyInAnyOrder(
            IssueMappingWarning.UNKNOWN_STATUS,
            IssueMappingWarning.UNKNOWN_SEVERITY,
        )
    }

    private fun profile() = CompiledIssueMappingProfile(
        schemaVersion = "jira-mapping-profile/v1",
        mappingVersion = MAPPING_VERSION,
        definition = JsonNodeFactory.instance.objectNode(),
        statusByToken = mapOf("ready for release" to IssueStatus.RESOLVED),
        severityByToken = mapOf("major" to IssueSeverity.HIGH),
    )

    companion object {
        private const val MAPPING_VERSION = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
