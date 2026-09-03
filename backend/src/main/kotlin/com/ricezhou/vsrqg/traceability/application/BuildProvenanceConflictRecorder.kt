package com.ricezhou.vsrqg.traceability.application

import java.time.Instant

fun interface BuildProvenanceConflictRecorder {
    fun record(
        acceptedReceiptId: String,
        projectId: String,
        actorId: String,
        rejectedEnvelopeDigest: String,
        requestId: String,
        attemptedAt: Instant,
    )
}
