package com.ricezhou.vsrqg.testmanagement.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class GetTestRunResults(private val repository:TestRunRepository,private val authorizer:ProjectAuthorizer) {
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    fun get(principal:Principal,id:String):JsonNode {
        val run=repository.run(id)
        authorizer.require(principal,run.projectId,Permission.TEST_READ)
        if(run.state.terminal) repository.terminalSnapshot(id)?.let { return it }
        // Pre-V15 terminal Runs use exactly the same projection of immutable facts, without file I/O.
        val view=repository.resultView(id).deepCopy<ObjectNode>()
        return view.put("inputDigest",TestJson.digest(view))
    }
}
