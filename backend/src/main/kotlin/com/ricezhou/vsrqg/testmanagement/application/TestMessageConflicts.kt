package com.ricezhou.vsrqg.testmanagement.application

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class TestMessageConflicts(private val governance:GovernanceStore,private val mapper:ObjectMapper) {
    // Audit aggregate references are scalar identities: this transaction never locks the parent Run/Agent.
    @Transactional(propagation=Propagation.REQUIRES_NEW)
    fun record(actor:AgentActor,runId:String,digest:String,code:String,requestId:String) {
        governance.appendAudit(actor.projectId,actor.principalId,"TEST_MESSAGE_CONFLICT","TEST_RUN",runId,requestId,code,
            afterState=mapper.createObjectNode().put("code",code).put("requestDigest",digest))
    }
}
