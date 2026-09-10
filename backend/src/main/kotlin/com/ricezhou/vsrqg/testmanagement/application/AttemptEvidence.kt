package com.ricezhou.vsrqg.testmanagement.application

import java.time.Instant

data class EvidenceResolution(val availableIds:Set<String>,val failedRequiredTypes:Set<String>)

/** Outbound execution port; the Evidence module owns verification and session closure. */
interface AttemptEvidence {
    fun resolve(binding:AttemptBinding,evidenceIds:Set<String>):EvidenceResolution
    fun seal(binding:AttemptBinding,now:Instant)
}
