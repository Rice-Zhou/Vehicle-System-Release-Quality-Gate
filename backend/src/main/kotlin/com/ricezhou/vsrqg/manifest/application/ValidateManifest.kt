package com.ricezhou.vsrqg.manifest.application

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import java.time.Instant
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

enum class ManifestState {
    DRAFT,
    REGISTERED,
    REJECTED,
    LOCKED,
}

enum class ValidationStatus {
    VALID,
    FAILED,
    INCOMPLETE,
}

data class ValidationReport(
    val validationId: String,
    val manifestId: String,
    val status: ValidationStatus,
    val contentDigest: String,
    val schemaVersion: String,
    val violations: List<ManifestViolation>,
    val validatedAt: Instant,
    val canonicalizationId: String,
    val validatorVersion: String,
    val canonicalByteLength: Int,
)

data class ValidateManifestCommand(
    val principal: Principal,
    val releaseId: String,
    val manifestId: String,
    val idempotencyKey: String,
    val requestDigest: String,
    val requestId: String,
    val reason: String,
)

class ManifestNotFound(releaseId: String, manifestId: String) :
    ResourceNotFound(
        "MANIFEST_NOT_FOUND",
        "Manifest not found",
        "Manifest '$manifestId' for release '$releaseId' was not found or is not visible",
    )

@Service
class ValidateManifest(
    private val repository: ManifestRepository,
    private val authorizer: ProjectAuthorizer,
    private val idempotentExecutor: IdempotentExecutor,
    private val governanceStore: GovernanceStore,
) {
    @Transactional
    fun validate(command: ValidateManifestCommand): ValidationReport {
        val release = repository.lockRelease(command.releaseId)
            ?: throw ManifestNotFound(command.releaseId, command.manifestId)
        val authorization = authorizer.require(command.principal, release.projectId, Permission.MANIFEST_WRITE)
        return idempotentExecutor.execute(
            scope = "manifest:validate:${command.releaseId}:${command.manifestId}",
            principalId = authorization.principalId,
            key = command.idempotencyKey,
            requestDigest = command.requestDigest,
            responseType = ValidationReport::class.java,
        ) {
            val registration = repository.findById(command.releaseId, command.manifestId)
                ?: throw ManifestNotFound(command.releaseId, command.manifestId)
            governanceStore.appendAudit(
                projectId = release.projectId,
                actorId = authorization.principalId,
                action = "MANIFEST_VALIDATED",
                resourceType = "MANIFEST",
                resourceId = command.manifestId,
                requestId = command.requestId,
                reason = command.reason,
            )
            governanceStore.appendOutbox(
                eventType = "manifest.validated",
                aggregateType = "MANIFEST",
                aggregateId = command.manifestId,
                payload = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()
                    .put("schemaVersion", 1)
                    .put("releaseId", command.releaseId)
                    .put("manifestId", command.manifestId)
                    .put("validationId", registration.validation.validationId)
                    .put("status", registration.validation.status.name)
                    .put("requestId", command.requestId),
            )
            registration.validation
        }
    }

    fun evaluate(
        release: ManifestRelease,
        root: JsonNode,
        validationId: String,
        manifestId: String,
        contentDigest: String,
        schemaVersion: String,
        canonicalByteLength: Int,
        validatedAt: Instant,
    ): ValidationReport {
        val failures = buildList {
            match("releaseId", release.id, root, "MANIFEST_RELEASE_ID_MISMATCH", this)
            match("project", release.projectReference, root, "MANIFEST_PROJECT_MISMATCH", this)
            match("vehicle", release.vehicle, root, "MANIFEST_VEHICLE_MISMATCH", this)
            match("platform", release.platform, root, "MANIFEST_PLATFORM_MISMATCH", this)
            match("systemVersion", release.systemVersion, root, "MANIFEST_SYSTEM_VERSION_MISMATCH", this)
            match("buildId", release.buildId, root, "MANIFEST_BUILD_ID_MISMATCH", this)
            val artifactIds = root.path("artifacts").map { it.path("artifactId").asText() }
            artifactIds.groupingBy { it }.eachCount()
                .filterValues { it > 1 }
                .keys
                .sorted()
                .forEach { artifactId ->
                    add(
                        ManifestViolation(
                            code = "MANIFEST_ARTIFACT_ID_DUPLICATE",
                            path = "/artifacts",
                            message = "Artifact ID '$artifactId' occurs more than once",
                        ),
                    )
                }
        }
        val violations = if (failures.isEmpty()) {
            listOf(
                ManifestViolation(
                    code = "ARTIFACT_CHECKSUM_NOT_VERIFIED",
                    path = "/artifacts",
                    message = "Declared checksums are stored but no artifact payload was available for verification",
                ),
            )
        } else {
            failures.sortedWith(compareBy(ManifestViolation::path, ManifestViolation::code))
        }
        return ValidationReport(
            validationId = validationId,
            manifestId = manifestId,
            status = if (failures.isEmpty()) ValidationStatus.INCOMPLETE else ValidationStatus.FAILED,
            contentDigest = contentDigest,
            schemaVersion = schemaVersion,
            violations = violations,
            validatedAt = validatedAt,
            canonicalizationId = CANONICALIZATION_ID,
            validatorVersion = VALIDATOR_VERSION,
            canonicalByteLength = canonicalByteLength,
        )
    }

    private fun match(
        field: String,
        expected: String,
        root: JsonNode,
        code: String,
        violations: MutableList<ManifestViolation>,
    ) {
        val actual = root.path(field).asText()
        if (actual != expected) {
            violations += ManifestViolation(
                code = code,
                path = "/$field",
                message = "Manifest $field does not match the target release",
            )
        }
    }

    companion object {
        const val CANONICALIZATION_ID = "RFC8785-JCS-1"
        const val VALIDATOR_VERSION = "vsrqg-manifest-validator/0.2.0"
    }
}
