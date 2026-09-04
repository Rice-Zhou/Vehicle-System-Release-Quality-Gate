package com.ricezhou.vsrqg.traceability.application

import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import org.springframework.stereotype.Service

@Service
class RunTraceabilityVerification(
    private val repository: TraceabilityVerificationRepository,
    private val canonicalizer: TraceabilityCanonicalizer,
    private val idGenerator: IdGenerator,
    private val timeProvider: TimeProvider,
) {
    private val verifier = TraceabilityVerifier(canonicalizer)

    fun run(claim: TraceabilityVerificationJobClaim) {
        val execution = try {
            repository.loadPinnedExecution(claim.verificationRunId).also { pinned ->
                val actualDigest = canonicalizer.canonicalizeInput(pinned.input).digest
                if (actualDigest != pinned.inputDigest) {
                    throw TraceabilityVerificationFailure(
                        "TRACEABILITY_INPUT_NOT_VALID",
                        "PINNED_INPUT_DIGEST_MISMATCH",
                    )
                }
            }
        } catch (failure: TraceabilityVerificationFailure) {
            repository.failInvalidInput(claim, failure.diagnosticCode, timeProvider.now())
            return
        }
        val computation = try {
            verifier.verify(execution.input)
        } catch (failure: TraceabilityVerificationFailure) {
            repository.failInvalidInput(claim, failure.diagnosticCode, timeProvider.now())
            return
        }

        repeat(MAX_VERSION_ATTEMPTS) { attempt ->
            val materialization = TraceabilitySnapshotMaterialization(
                snapshotId = idGenerator.nextId("trs_"),
                runGapIds = computation.gaps.map { idGenerator.nextId("gap_") },
                computation = computation,
                completedAt = timeProvider.now(),
            )
            try {
                repository.materializeResult(claim, execution, materialization)
                return
            } catch (conflict: TraceabilitySnapshotVersionConflict) {
                if (attempt == MAX_VERSION_ATTEMPTS - 1) throw conflict
            }
        }
    }

    private companion object {
        const val MAX_VERSION_ATTEMPTS = 3
    }
}
