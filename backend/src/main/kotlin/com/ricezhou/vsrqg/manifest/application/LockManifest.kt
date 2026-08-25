package com.ricezhou.vsrqg.manifest.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import java.security.MessageDigest
import java.time.Instant
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class LockManifestCommand(
    val principal: Principal,
    val releaseId: String,
    val manifestId: String,
    val expectedVersion: Long,
    val idempotencyKey: String,
    val requestId: String,
    val reason: String,
)

data class LockManifestResult(
    val releaseId: String,
    val manifestId: String,
    val manifestRevision: Int,
    val contentDigest: String,
    val state: ManifestState,
    val lockedAt: Instant,
)

class ManifestLockConflict(code: String, detail: String) :
    ResourceConflict(code, "Manifest lock conflict", detail)

@Component
class ManifestValidationTrustPolicy(
    @Value("\${vsrqg.manifest.trusted-validator-versions:}") configuredVersions: String,
) {
    private val trustedVersions = configuredVersions.split(',').map(String::trim).filter(String::isNotEmpty).toSet()

    fun isTrusted(validatorVersion: String): Boolean = validatorVersion in trustedVersions
}

@Service
class LockManifest(
    private val repository: ManifestRepository,
    private val authorizer: ProjectAuthorizer,
    private val idempotentExecutor: IdempotentExecutor,
    private val governanceStore: GovernanceStore,
    private val idGenerator: IdGenerator,
    private val timeProvider: TimeProvider,
    private val objectMapper: ObjectMapper,
    private val trustPolicy: ManifestValidationTrustPolicy,
) {
    @Transactional
    fun lock(command: LockManifestCommand): LockManifestResult {
        val release = repository.lockRelease(command.releaseId)
            ?: throw ManifestNotFound(command.releaseId, command.manifestId)
        val authorization = authorizer.require(command.principal, release.projectId, Permission.MANIFEST_LOCK)
        return idempotentExecutor.execute(
            scope = "manifest:lock:${command.releaseId}:${command.manifestId}",
            principalId = authorization.principalId,
            key = command.idempotencyKey,
            requestDigest = requestDigest(command),
            responseType = LockManifestResult::class.java,
        ) {
            if (release.status != "REGISTERED" || release.lockedManifestId != null) {
                conflict(
                    "MANIFEST_LOCK_CONFLICT",
                    "Release '${release.id}' already has a locked manifest or is not ready for locking",
                )
            }
            val candidate = repository.findLockCandidate(command.releaseId, command.manifestId)
                ?: throw ManifestNotFound(command.releaseId, command.manifestId)
            if (candidate.state != ManifestState.REGISTERED) {
                conflict("MANIFEST_LOCK_CONFLICT", "Manifest '${candidate.id}' is not registered")
            }
            if (candidate.version != command.expectedVersion) {
                conflict(
                    "MANIFEST_VERSION_CONFLICT",
                    "Expected manifest version ${command.expectedVersion}, but found ${candidate.version}",
                )
            }
            if (!validatesPersistedAuthority(candidate)) {
                conflict(
                    "MANIFEST_VALIDATION_NOT_VALID",
                    "The latest validation report does not prove this manifest valid",
                )
            }
            if (digest(candidate.canonicalBytes) != candidate.contentDigest) {
                conflict(
                    "MANIFEST_CONTENT_DIGEST_MISMATCH",
                    "Persisted canonical manifest bytes do not match the authoritative digest",
                )
            }
            if (!repository.artifactIntegrityMatches(candidate.id)) {
                conflict(
                    "MANIFEST_ARTIFACT_INTEGRITY_MISMATCH",
                    "Persisted artifact integrity metadata does not match the registered manifest",
                )
            }

            val now = timeProvider.now()
            if (
                !repository.markManifestLocked(
                    candidate.id,
                    candidate.persistedValidationId,
                    command.expectedVersion,
                    now,
                )
            ) {
                conflict("MANIFEST_VERSION_CONFLICT", "Manifest version changed before it could be locked")
            }
            if (!repository.markReleaseReady(release.id, candidate.id, now)) {
                conflict("MANIFEST_LOCK_CONFLICT", "Release state changed before its manifest could be locked")
            }
            repository.appendManifestLockHistory(
                id = idGenerator.nextId("rsh_"),
                releaseId = release.id,
                actorId = authorization.principalId,
                reason = command.reason,
                occurredAt = now,
            )
            val result = LockManifestResult(
                releaseId = release.id,
                manifestId = candidate.id,
                manifestRevision = candidate.revision,
                contentDigest = candidate.contentDigest,
                state = ManifestState.LOCKED,
                lockedAt = now,
            )
            governanceStore.appendAudit(
                projectId = release.projectId,
                actorId = authorization.principalId,
                action = "MANIFEST_LOCKED",
                resourceType = "MANIFEST",
                resourceId = candidate.id,
                requestId = command.requestId,
                reason = command.reason,
                afterState = objectMapper.valueToTree(result),
            )
            governanceStore.appendOutbox(
                eventType = "manifest.locked",
                aggregateType = "MANIFEST",
                aggregateId = candidate.id,
                payload = objectMapper.createObjectNode()
                    .put("schemaVersion", 1)
                    .put("releaseId", release.id)
                    .put("manifestId", candidate.id)
                    .put("revision", candidate.revision)
                    .put("contentDigest", candidate.contentDigest)
                    .put("requestId", command.requestId),
            )
            result
        }
    }

    private fun requestDigest(command: LockManifestCommand): String = digest(
        objectMapper.writeValueAsBytes(
            mapOf(
                "manifestId" to command.manifestId,
                "expectedVersion" to command.expectedVersion,
                "reason" to command.reason,
            ),
        ),
    )

    private fun validatesPersistedAuthority(candidate: ManifestLockCandidate): Boolean =
        candidate.persistedValidationStatus == "VALID" &&
            candidate.persistedValidationId == candidate.validation.validationId &&
            candidate.validation.manifestId == candidate.id &&
            candidate.persistedValidationDigest == candidate.contentDigest &&
            candidate.validation.status == ValidationStatus.VALID &&
            candidate.validation.contentDigest == candidate.contentDigest &&
            candidate.persistedValidationSchemaVersion == candidate.schemaVersion &&
            candidate.validation.schemaVersion == candidate.schemaVersion &&
            candidate.validation.canonicalByteLength == candidate.canonicalBytes.size &&
            candidate.persistedValidatorVersion == candidate.validation.validatorVersion &&
            trustPolicy.isTrusted(candidate.persistedValidatorVersion)

    private fun digest(bytes: ByteArray): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
        return "sha256:" + hash.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun conflict(code: String, detail: String): Nothing = throw ManifestLockConflict(code, detail)
}
