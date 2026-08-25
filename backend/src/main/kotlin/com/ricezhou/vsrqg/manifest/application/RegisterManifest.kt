package com.ricezhou.vsrqg.manifest.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.manifest.domain.ManifestDocument
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class RegisterManifestCommand(
    val principal: Principal,
    val releaseId: String,
    val document: ManifestDocument,
    val idempotencyKey: String,
    val requestId: String,
)

data class RegisterManifestResult(
    val manifestId: String,
    val revision: Int,
    val state: ManifestState,
    val contentDigest: String,
    val validation: ValidationReport,
)

class ManifestSchemaInvalid(
    val violations: List<ManifestViolation>,
) : RuntimeException("Manifest does not satisfy the V0.2 schema and canonicalization contract")

class ManifestRegistrationConflict(releaseId: String, status: String) :
    ResourceConflict(
        "MANIFEST_REGISTRATION_NOT_ALLOWED",
        "Manifest registration is not allowed",
        "Release '$releaseId' cannot accept a manifest revision while its status is '$status'",
    )

@Service
class RegisterManifest(
    private val repository: ManifestRepository,
    private val releaseAuthorizer: ProjectAuthorizer,
    private val idempotentExecutor: IdempotentExecutor,
    private val manifestValidator: ManifestValidator,
    private val validateManifest: ValidateManifest,
    private val canonicalizer: ManifestCanonicalizer,
    private val governanceStore: GovernanceStore,
    private val idGenerator: IdGenerator,
    private val timeProvider: TimeProvider,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun register(command: RegisterManifestCommand): RegisterManifestResult {
        val release = repository.lockRelease(command.releaseId)
            ?: throw ManifestNotFound(command.releaseId, "new")
        val authorization = releaseAuthorizer.require(
            command.principal,
            release.projectId,
            Permission.MANIFEST_WRITE,
        )
        if (release.status !in REGISTRABLE_RELEASE_STATES) {
            throw ManifestRegistrationConflict(release.id, release.status)
        }
        val structuralViolations = manifestValidator.validate(command.document)
        if (structuralViolations.isNotEmpty()) {
            throw ManifestSchemaInvalid(structuralViolations)
        }
        val canonical = canonicalizer.canonicalize(command.document)
        return idempotentExecutor.execute(
            scope = "manifest:register:${command.releaseId}",
            principalId = authorization.principalId,
            key = command.idempotencyKey,
            requestDigest = canonical.contentDigest,
            responseType = RegisterManifestResult::class.java,
        ) {
            repository.findByDigest(command.releaseId, canonical.contentDigest)
                ?: persist(command, release, authorization.principalId, canonical.bytes, canonical.contentDigest)
        }
    }

    private fun persist(
        command: RegisterManifestCommand,
        release: ManifestRelease,
        actorId: String,
        canonicalBytes: ByteArray,
        contentDigest: String,
    ): RegisterManifestResult {
        val root = objectMapper.readTree(command.document.source)
        val now = timeProvider.now()
        val manifestId = idGenerator.nextId("man_")
        val schemaVersion = root.path("manifestVersion").asText()
        val validation = validateManifest.evaluate(
            release = release,
            root = root,
            validationId = idGenerator.nextId("mvl_"),
            manifestId = manifestId,
            contentDigest = contentDigest,
            schemaVersion = schemaVersion,
            canonicalByteLength = canonicalBytes.size,
            validatedAt = now,
        )
        val finalState = if (validation.status == ValidationStatus.FAILED) {
            ManifestState.REJECTED
        } else {
            ManifestState.REGISTERED
        }
        val revision = repository.nextRevision(release.id)
        repository.insertRevision(
            ManifestRevisionRecord(
                id = manifestId,
                releaseId = release.id,
                revision = revision,
                contentDigest = contentDigest,
                rawManifest = command.document.source,
                canonicalBytes = canonicalBytes,
                schemaVersion = schemaVersion,
                state = ManifestState.DRAFT,
                createdAt = now,
            ),
        )
        persistArtifacts(root.path("artifacts"), manifestId, now)
        repository.insertValidation(validation)
        repository.finalizeRevision(manifestId, finalState, now)
        if (finalState == ManifestState.REGISTERED && repository.markReleaseRegistered(release.id, now)) {
            repository.appendReleaseHistory(
                id = idGenerator.nextId("rsh_"),
                releaseId = release.id,
                actorId = actorId,
                occurredAt = now,
            )
        }
        val result = RegisterManifestResult(
            manifestId = manifestId,
            revision = revision,
            state = finalState,
            contentDigest = contentDigest,
            validation = validation,
        )
        governanceStore.appendAudit(
            projectId = release.projectId,
            actorId = actorId,
            action = if (finalState == ManifestState.REGISTERED) "MANIFEST_REGISTERED" else "MANIFEST_REJECTED",
            resourceType = "MANIFEST",
            resourceId = manifestId,
            requestId = command.requestId,
            reason = null,
            afterState = objectMapper.valueToTree(result),
        )
        governanceStore.appendOutbox(
            eventType = if (finalState == ManifestState.REGISTERED) {
                "manifest.registered"
            } else {
                "manifest.rejected"
            },
            aggregateType = "MANIFEST",
            aggregateId = manifestId,
            payload = objectMapper.createObjectNode()
                .put("schemaVersion", 1)
                .put("releaseId", release.id)
                .put("manifestId", manifestId)
                .put("revision", revision)
                .put("state", finalState.name)
                .put("contentDigest", contentDigest)
                .put("validationId", validation.validationId)
                .put("requestId", command.requestId),
        )
        return result
    }

    private fun persistArtifacts(artifacts: JsonNode, manifestId: String, createdAt: java.time.Instant) {
        val linked = mutableSetOf<String>()
        artifacts.forEachIndexed { ordinal, artifact ->
            val checksum = artifact.path("checksum")
            val identityDigest = canonicalizer.canonicalize(ManifestDocument(artifact.toString())).contentDigest
            val artifactId = repository.findOrInsertArtifact(
                ArtifactRecord(
                    id = idGenerator.nextId("art_"),
                    identityDigest = identityDigest,
                    type = artifact.path("type").asText(),
                    locator = artifact.deepCopy(),
                    checksumAlgorithm = checksum.path("algorithm").asText(),
                    checksumValue = checksum.path("value").asText(),
                    createdAt = createdAt,
                ),
            )
            if (linked.add(artifactId)) {
                repository.linkArtifact(
                    manifestId = manifestId,
                    artifactId = artifactId,
                    ordinal = ordinal,
                    required = artifact.path("required").asBoolean(),
                    createdAt = createdAt,
                )
            }
        }
    }

    private companion object {
        val REGISTRABLE_RELEASE_STATES = setOf("DRAFT", "REGISTERED")
    }
}
