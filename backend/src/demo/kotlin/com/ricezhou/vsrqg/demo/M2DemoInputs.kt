package com.ricezhou.vsrqg.demo

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.ricezhou.vsrqg.issue.adapter.FixtureIssueSourceAdapter
import com.ricezhou.vsrqg.issue.adapter.FixturePage
import com.ricezhou.vsrqg.issue.adapter.FixtureScenario
import com.ricezhou.vsrqg.issue.adapter.IssueSourceRuntimeFactory
import com.ricezhou.vsrqg.issue.application.CompiledIssueMappingProfile
import com.ricezhou.vsrqg.issue.application.IssueSourcePort
import com.ricezhou.vsrqg.issue.application.IssueSourceRuntimeDescriptor
import com.ricezhou.vsrqg.issue.application.IssueSyncResultSetMode
import com.ricezhou.vsrqg.issue.domain.IssueSeverity
import com.ricezhou.vsrqg.issue.domain.IssueStatus
import com.ricezhou.vsrqg.issue.domain.NormalizedIssue
import java.time.Instant

object M2DemoInputs {
    val descriptor = IssueSourceRuntimeDescriptor(
        sourceType = "FIXTURE",
        adapterId = "m2-demo-fixture",
        adapterVersion = "m2-demo-fixture/v1",
        supportedMappingSchemas = setOf("jira-mapping-profile/v1"),
        supportedTransportRange = "fixture/v1",
        resultSetMode = IssueSyncResultSetMode.FULL,
        filterReference = "all-relevant-issues/v1",
    )

    fun mappingDefinition(): JsonNode = jacksonObjectMapper().readTree(MAPPING_DEFINITION)

    fun factory(observedAt: Instant): IssueSourceRuntimeFactory = object : IssueSourceRuntimeFactory {
        override val descriptor = M2DemoInputs.descriptor

        override fun open(profile: CompiledIssueMappingProfile): IssueSourcePort {
            val issues = listOf("DEMO-1", "DEMO-2").map { sourceIssueId ->
                NormalizedIssue(
                    source = "FIXTURE",
                    sourceIssueId = sourceIssueId,
                    title = "Synthetic issue $sourceIssueId",
                    severity = IssueSeverity.HIGH,
                    status = IssueStatus.CLOSED,
                    rawSeverity = "Major",
                    rawStatus = "Closed",
                    sourceVersion = "1",
                    sourceReference = "SYNTHETIC_DEMO",
                    observedAt = observedAt,
                    mappingVersion = profile.mappingVersion,
                )
            }
            return FixtureIssueSourceAdapter(
                FixtureScenario(
                    source = "FIXTURE",
                    mappingVersion = profile.mappingVersion,
                    pages = listOf(FixturePage(null, issues, null, "1", observedAt, terminal = true)),
                ),
            )
        }
    }

    private const val MAPPING_DEFINITION = """
        {
          "schemaVersion":"jira-mapping-profile/v1",
          "normalizationVersion":"unicode-nfc-trim-root-lower/v1",
          "unknownStatusPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
          "unknownSeverityPolicy":"MAP_TO_UNKNOWN_WITH_WARNING",
          "statusAliases":{"CLOSED":["Closed"]},
          "severityAliases":{"HIGH":["Major"]}
        }
    """
}
