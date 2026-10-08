package com.ricezhou.vsrqg.quality.adapter

import com.ricezhou.vsrqg.evidence.application.EvidenceConflict
import com.ricezhou.vsrqg.issue.application.SnapshotContentIntegrityFailure
import com.ricezhou.vsrqg.issue.application.SyncObservationIntegrityFailure
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.quality.application.QualityDecisionRunner
import com.ricezhou.vsrqg.quality.application.QualityEvaluationClaim
import com.ricezhou.vsrqg.quality.application.QualityEvaluationRepository
import com.ricezhou.vsrqg.quality.application.QualityInputFailure
import com.ricezhou.vsrqg.quality.application.QualityRepository
import com.ricezhou.vsrqg.quality.application.QualitySourceReader
import com.ricezhou.vsrqg.quality.domain.QualityFailure
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class QualityEvaluationWorker(
    private val evaluations: QualityEvaluationRepository,
    private val sources: QualitySourceReader,
    private val rules: QualityRepository,
    private val runner: QualityDecisionRunner,
    private val ids: IdGenerator,
    private val clock: TimeProvider,
) {
    fun runNext(): Boolean {
        val claim = evaluations.claimNext(clock.now()) ?: return false
        try {
            val record = evaluations.request(claim)
            val pinned = sources.read(record.projectId, record.releaseId, record.request)
            sources.verifyEvidence(pinned)
            val snapshot = evaluations.seal(claim, pinned, clock.now())
            val ref = record.request.path("ruleSet")
            val set = rules.findPublished(ref.path("ruleSetId").asText(), ref.path("version").asLong())
                ?: throw QualityInputFailure("QUALITY_RULE_SET_NOT_PUBLISHED")
            if (set.id != record.ruleSetVersionId || set.projectId != record.projectId ||
                set.contentDigest != snapshot.path("ruleSetDigest").asText()) {
                throw QualityInputFailure("QUALITY_RULE_SET_CHANGED")
            }
            val decision = runner.evaluate(snapshot, rules.rules(set.id), ids.nextId("qrl_"))
            evaluations.complete(claim, snapshot, decision.ruleResults, decision.result,
                decision.errorCode, clock.now())
        } catch (failure: QualityInputFailure) {
            if (failure.code != "QUALITY_STALE_LEASE") recordError(claim, failure.code)
        } catch (failure: QualityFailure) {
            recordError(claim, failure.code)
        } catch (failure: EvidenceConflict) {
            recordError(claim, failure.code)
        } catch (_: SnapshotContentIntegrityFailure) {
            recordError(claim, "QUALITY_ISSUE_SNAPSHOT_INTEGRITY_ERROR")
        } catch (_: SyncObservationIntegrityFailure) {
            recordError(claim, "QUALITY_ISSUE_SNAPSHOT_INTEGRITY_ERROR")
        } catch (_: ResourceNotFound) {
            recordError(claim, "QUALITY_SOURCE_NOT_FOUND")
        } catch (failure: DataAccessException) {
            evaluations.retryInfrastructure(claim, clock.now())
        }
        return true
    }

    private fun recordError(claim: QualityEvaluationClaim, code: String) {
        try {
            evaluations.fail(claim, code, clock.now())
        } catch (failure: QualityInputFailure) {
            if (failure.code != "QUALITY_STALE_LEASE") throw failure
        }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "vsrqg.quality", name = ["worker-enabled"], havingValue = "true")
class QualityEvaluationSchedule(private val worker: QualityEvaluationWorker) {
    @Scheduled(fixedDelayString = "\${vsrqg.quality.poll-interval:PT1S}", initialDelayString = "\${vsrqg.quality.initial-delay:PT1S}")
    fun poll() {
        worker.runNext()
    }
}
