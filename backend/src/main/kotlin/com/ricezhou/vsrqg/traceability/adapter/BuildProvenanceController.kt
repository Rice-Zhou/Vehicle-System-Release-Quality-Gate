package com.ricezhou.vsrqg.traceability.adapter

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
