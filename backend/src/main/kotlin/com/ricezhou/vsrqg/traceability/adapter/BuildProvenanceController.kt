package com.ricezhou.vsrqg.traceability.adapter

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.ricezhou.vsrqg.access.application.AuthenticatedPrincipalResolver
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceInvalid
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceResult
import com.ricezhou.vsrqg.traceability.application.IngestBuildProvenance
import com.ricezhou.vsrqg.traceability.application.IngestBuildProvenanceCommand
import com.ricezhou.vsrqg.traceability.domain.BuildProvenanceEnvelope
import com.ricezhou.vsrqg.traceability.domain.ProvenanceProviderId
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

@JsonDeserialize(using = BuildProvenanceRequestDeserializer::class)
data class BuildProvenanceRequest(
    val schemaVersion: Int,
    @field:Size(min = 1, max = 128)
    val project: String,
    @field:Size(min = 1, max = 128)
    val releaseIssueSnapshotId: String,
    @field:Size(min = 1, max = 40)
    val provider: String,
    @field:Size(min = 1, max = 512)
    val repository: String,
    @field:Size(min = 1, max = 40)
    val sourceRevision: String,
    @field:Size(min = 1, max = 255)
    val pipeline: String,
    @field:Size(min = 1, max = 255)
    val buildId: String,
    val buildAttempt: Int,
    @field:Size(min = 1, max = 1024)
    val workflowReference: String,
    @field:Size(min = 1, max = 1024)
    val proofReference: String,
    @field:Size(min = 1, max = 71)
    val proofDigest: String,
    @field:Size(min = 1, max = 20)
    val sourceIssueIds: List<String>,
    @field:Size(min = 1, max = 20)
    val artifactSha256s: List<String>,
) {
    fun toEnvelope(): BuildProvenanceEnvelope = BuildProvenanceEnvelope(
        schemaVersion = schemaVersion,
        projectReference = project,
        releaseIssueSnapshotId = releaseIssueSnapshotId,
        provider = ProvenanceProviderId(providerId()),
        repository = repository,
        sourceRevision = sourceRevision,
        pipeline = pipeline,
        buildId = buildId,
        buildAttempt = buildAttempt,
        workflowReference = workflowReference,
        proofReference = proofReference,
        proofDigest = proofDigest,
        sourceIssueIds = sourceIssueIds,
        artifactSha256s = artifactSha256s,
    )

    private fun providerId(): String =
        if (provider == GITHUB_ACTIONS) GITHUB_ACTIONS_ID else throw BuildProvenanceInvalid("PROVIDER_INVALID")

    private companion object {
        const val GITHUB_ACTIONS = "GITHUB_ACTIONS"
        const val GITHUB_ACTIONS_ID = "github-actions"
    }
}

class BuildProvenanceRequestDeserializer : StdDeserializer<BuildProvenanceRequest>(BuildProvenanceRequest::class.java) {
    override fun deserialize(parser: JsonParser, context: DeserializationContext): BuildProvenanceRequest {
        val node = parser.codec.readTree<JsonNode>(parser)
        if (!node.isObject || node.fieldNames().asSequence().any { it !in FIELDS }) invalid(parser)
        return BuildProvenanceRequest(
            schemaVersion = node.requiredInt("schemaVersion", parser),
            project = node.requiredText("project", parser),
            releaseIssueSnapshotId = node.requiredText("releaseIssueSnapshotId", parser),
            provider = node.requiredText("provider", parser),
            repository = node.requiredText("repository", parser),
            sourceRevision = node.requiredText("sourceRevision", parser),
            pipeline = node.requiredText("pipeline", parser),
            buildId = node.requiredText("buildId", parser),
            buildAttempt = node.requiredInt("buildAttempt", parser),
            workflowReference = node.requiredText("workflowReference", parser),
            proofReference = node.requiredText("proofReference", parser),
            proofDigest = node.requiredText("proofDigest", parser),
            sourceIssueIds = node.requiredTextArray("sourceIssueIds", parser),
            artifactSha256s = node.requiredTextArray("artifactSha256s", parser),
        )
    }

    private fun JsonNode.requiredText(field: String, parser: JsonParser): String =
        get(field)?.takeIf(JsonNode::isTextual)?.textValue() ?: invalid(parser)

    private fun JsonNode.requiredInt(field: String, parser: JsonParser): Int =
        get(field)?.takeIf(JsonNode::isInt)?.intValue() ?: invalid(parser)

    private fun JsonNode.requiredTextArray(field: String, parser: JsonParser): List<String> {
        val array = get(field)?.takeIf(JsonNode::isArray) ?: invalid(parser)
        if (array.any { !it.isTextual }) invalid(parser)
        return array.map(JsonNode::textValue)
    }

    private fun invalid(parser: JsonParser): Nothing =
        throw JsonMappingException.from(parser, "INVALID_BUILD_PROVENANCE_REQUEST")

    private companion object {
        val FIELDS = setOf(
            "schemaVersion",
            "project",
            "releaseIssueSnapshotId",
            "provider",
            "repository",
            "sourceRevision",
            "pipeline",
            "buildId",
            "buildAttempt",
            "workflowReference",
            "proofReference",
            "proofDigest",
            "sourceIssueIds",
            "artifactSha256s",
        )
    }
}

@Validated
@RestController
class BuildProvenanceController(
    private val useCase: IngestBuildProvenance,
    private val principalResolver: AuthenticatedPrincipalResolver,
) {
    @PostMapping(INGEST_PATH)
    @PreAuthorize("hasAuthority('SCOPE_traceability:ingest')")
    fun ingest(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestHeader("Idempotency-Key") @Size(min = 1, max = 128) idempotencyKey: String,
        @Valid @RequestBody body: BuildProvenanceRequest,
        request: HttpServletRequest,
    ): ResponseEntity<BuildProvenanceResult> = ResponseEntity.ok(
        useCase.ingest(
            IngestBuildProvenanceCommand(
                principal = principalResolver.resolve(
                    jwt.issuer?.toString(),
                    jwt.subject,
                    jwt.getClaimAsString("principal_type"),
                ),
                tokenProjectReference = jwt.claims["project"] as? String,
                envelope = body.toEnvelope(),
                idempotencyKey = idempotencyKey,
                requestId = RequestIdFilter.from(request),
            ),
        ),
    )

    private companion object {
        const val INGEST_PATH = "/api/v1/traceability/facts:ingest"
    }
}
