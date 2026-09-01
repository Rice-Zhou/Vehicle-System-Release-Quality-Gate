package com.ricezhou.vsrqg.issue.adapter

import com.ricezhou.vsrqg.issue.application.IssueSourceDescriptorRegistry
import com.ricezhou.vsrqg.issue.application.IssueSourceRuntimeDescriptor
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import org.springframework.stereotype.Component

internal val JIRA_CLI_PILOT_DESCRIPTOR = IssueSourceRuntimeDescriptor(
    sourceType = "JIRA",
    adapterId = "jira-cli-pilot",
    adapterVersion = "jira-cli-pilot-adapter-v1",
    supportedMappingSchemas = setOf("jira-mapping-profile/v1"),
    supportedTransportRange = "jira-cli/1.7.x",
)

@Component
class FixedIssueSourceDescriptorRegistry : IssueSourceDescriptorRegistry {
    private val bySourceType: Map<String, IssueSourceRuntimeDescriptor>

    constructor() : this(listOf(JIRA_CLI_PILOT_DESCRIPTOR))

    internal constructor(descriptors: List<IssueSourceRuntimeDescriptor>) {
        bySourceType = descriptors.associateBy(IssueSourceRuntimeDescriptor::sourceType)
        require(bySourceType.size == descriptors.size) { "DUPLICATE_ISSUE_SOURCE_DESCRIPTOR" }
    }

    override fun require(sourceType: String): IssueSourceRuntimeDescriptor =
        bySourceType[sourceType] ?: throw ResourceConflict(
            code = "ADAPTER_NOT_CONFIGURED",
            resourceTitle = "Issue source adapter is not configured",
            detail = "No adapter descriptor is configured for this source type",
        )
}
