package com.ricezhou.vsrqg.issue.adapter

import com.ricezhou.vsrqg.issue.application.CompiledIssueMappingProfile
import com.ricezhou.vsrqg.issue.application.IssueMappingProfileCodec
import com.ricezhou.vsrqg.issue.application.IssueMappingProfileRepository
import com.ricezhou.vsrqg.issue.application.IssueSyncRunRecord
import com.ricezhou.vsrqg.issue.application.IssueSyncResultSetMode
import com.ricezhou.vsrqg.issue.application.MappingProfileInvalid
import com.ricezhou.vsrqg.issue.application.IssueSourceDescriptorRegistry
import com.ricezhou.vsrqg.issue.application.IssueSourcePort
import com.ricezhou.vsrqg.issue.application.IssueSourceRuntimeDescriptor
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import java.time.Instant
import org.springframework.stereotype.Component

internal val JIRA_CLI_PILOT_DESCRIPTOR = IssueSourceRuntimeDescriptor(
    sourceType = "JIRA",
    adapterId = "jira-cli-pilot",
    adapterVersion = "jira-cli-pilot-adapter-v1",
    supportedMappingSchemas = setOf("jira-mapping-profile/v1"),
    supportedTransportRange = "jira-cli/1.7.x",
    resultSetMode = IssueSyncResultSetMode.FULL,
    filterReference = "all-relevant-issues/v1",
)

interface IssueSourceRuntimeFactory {
    val descriptor: IssueSourceRuntimeDescriptor
    fun open(profile: CompiledIssueMappingProfile): IssueSourcePort
}

enum class IssueRuntimeFailureCode {
    MAPPING_PROFILE_NOT_CONFIGURED,
    MAPPING_PROFILE_INTEGRITY_FAILED,
    MAPPING_SCHEMA_UNSUPPORTED,
    ADAPTER_VERSION_MISMATCH,
    MAPPING_VERSION_MISMATCH,
}

class IssueRuntimeConfigurationException(
    val code: IssueRuntimeFailureCode,
) : RuntimeException(code.name)

fun interface IssueSourceRuntimeRegistry {
    fun open(run: IssueSyncRunRecord): IssueSourcePort
}

@Component
class DefaultIssueSourceRuntimeRegistry(
    private val repository: IssueMappingProfileRepository,
    private val codec: IssueMappingProfileCodec,
    factories: List<IssueSourceRuntimeFactory>,
) : IssueSourceRuntimeRegistry {
    private val factoriesBySourceType = factories.associateBy { it.descriptor.sourceType }

    init {
        require(factoriesBySourceType.size == factories.size) { "DUPLICATE_ISSUE_SOURCE_RUNTIME_FACTORY" }
    }

    override fun open(run: IssueSyncRunRecord): IssueSourcePort {
        val source = repository.findSource(run.sourceId)
            ?: fail(IssueRuntimeFailureCode.MAPPING_PROFILE_NOT_CONFIGURED)
        if (source.projectId != run.projectId) fail(IssueRuntimeFailureCode.MAPPING_VERSION_MISMATCH)

        val factory = factoriesBySourceType[source.sourceType]
            ?: fail(IssueRuntimeFailureCode.ADAPTER_VERSION_MISMATCH)
        if (factory.descriptor.adapterVersion != run.adapterVersion) {
            fail(IssueRuntimeFailureCode.ADAPTER_VERSION_MISMATCH)
        }

        val profile = repository.find(run.sourceId, run.mappingVersion)
            ?: fail(IssueRuntimeFailureCode.MAPPING_PROFILE_NOT_CONFIGURED)
        if (profile.projectId != run.projectId || profile.sourceId != run.sourceId) {
            fail(IssueRuntimeFailureCode.MAPPING_VERSION_MISMATCH)
        }

        val authoritativeMappingVersion = try {
            codec.mappingVersion(profile.definition)
        } catch (_: MappingProfileInvalid) {
            fail(IssueRuntimeFailureCode.MAPPING_PROFILE_INTEGRITY_FAILED)
        }
        if (authoritativeMappingVersion != profile.mappingVersion) {
            fail(IssueRuntimeFailureCode.MAPPING_PROFILE_INTEGRITY_FAILED)
        }
        if (profile.schemaVersion !in factory.descriptor.supportedMappingSchemas) {
            fail(IssueRuntimeFailureCode.MAPPING_SCHEMA_UNSUPPORTED)
        }

        val compiled = try {
            codec.compile(profile.definition)
        } catch (failure: MappingProfileInvalid) {
            if ("SCHEMA_VERSION_UNSUPPORTED" in failure.violationCodes) {
                fail(IssueRuntimeFailureCode.MAPPING_SCHEMA_UNSUPPORTED)
            }
            fail(IssueRuntimeFailureCode.MAPPING_PROFILE_INTEGRITY_FAILED)
        }
        if (compiled.schemaVersion != profile.schemaVersion) {
            fail(IssueRuntimeFailureCode.MAPPING_SCHEMA_UNSUPPORTED)
        }
        if (compiled.mappingVersion != run.mappingVersion) {
            fail(IssueRuntimeFailureCode.MAPPING_VERSION_MISMATCH)
        }
        return factory.open(compiled)
    }

    private fun fail(code: IssueRuntimeFailureCode): Nothing = throw IssueRuntimeConfigurationException(code)
}

class JiraCliPilotRuntimeFactory(
    private val properties: JiraCliPilotProperties,
    private val processRunner: JiraProcessRunner,
    private val observedAt: () -> Instant = Instant::now,
) : IssueSourceRuntimeFactory {
    override val descriptor: IssueSourceRuntimeDescriptor = JIRA_CLI_PILOT_DESCRIPTOR

    override fun open(profile: CompiledIssueMappingProfile): IssueSourcePort = JiraCliPilotAdapter(
        properties = properties,
        processRunner = processRunner,
        mapper = JiraIssueMapper(profile),
        mappingVersion = profile.mappingVersion,
        observedAt = observedAt,
    )
}

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
