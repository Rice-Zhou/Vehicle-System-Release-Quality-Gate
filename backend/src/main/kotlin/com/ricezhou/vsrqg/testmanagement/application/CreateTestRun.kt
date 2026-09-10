package com.ricezhou.vsrqg.testmanagement.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.testmanagement.domain.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

interface TestInputValidator {
    fun validateAgent(body:JsonNode,schema:String)
    fun validateCreate(body:JsonNode)
    fun environment(bytes:ByteArray):JsonNode
    fun context(body:JsonNode)
}

@Service
class CreateTestRun(private val repository:TestRunRepository,private val access:AgentAccess,
    private val authorizer:ProjectAuthorizer,private val source:SmokeEnvironmentSource,
    private val validator:TestInputValidator,private val idempotency:IdempotentExecutor,
    private val governance:GovernanceStore,private val ids:IdGenerator,private val clock:TimeProvider,
    private val mapper:ObjectMapper,@param:Value("\${vsrqg.demo.smoke.enabled:false}") private val enabled:Boolean) {

    @Transactional
    fun create(principal:Principal,body:JsonNode,key:String,requestId:String):JsonNode {
        if(!enabled) throw TestRunConflict("SMOKE_EXECUTION_DISABLED")
        validator.validateCreate(body)
        val project=repository.projectForRelease(body.path("releaseId").asText())
        val operator=authorizer.require(principal,project,Permission.TEST_EXECUTE)
        val configured=source.load()
        val selected=repository.lockAgent(configured.agentId)
        val actor=access.requireAgent(selected.fingerprint,"agent:execute")
        if(actor.projectId!=project || actor.deviceId!=configured.deviceId) throw TestRunConflict("DEVICE_SELECTOR_MISMATCH")
        return idempotency.execute("test:create",operator.principalId,key,TestJson.digest(body),JsonNode::class.java) {
            val manifest=repository.manifest(body.path("releaseId").asText())
            val selector=body.path("deviceSelector")
            if(selector.path("vehicle").asText()!=manifest.vehicle || selected.vehicle!=manifest.vehicle ||
                selected.platform!=manifest.platform) throw TestRunConflict("DEVICE_SELECTOR_MISMATCH")
            if(!selected.registered || !selected.capabilities.containsAll(SmokePolicy.capabilities +
                    selector.path("requiredCapabilities").map { it.asText() })) throw TestRunConflict("AGENT_CAPABILITY_MISMATCH")
            val now=clock.now()
            val plan=repository.plan(body.path("testPlan").path("planId").asText(),body.path("testPlan").path("version").asInt())
            if(repository.activeDeviceRun(actor.deviceId)) throw TestRunConflict("DEVICE_BUSY")
            val environment=validator.environment(configured.configBytes)
            val artifacts=mapper.readTree(manifest.bytes).path("artifacts")
            val apks=artifacts.filter { it.path("type").asText()=="APK" }
            val configs=artifacts.filter { it.path("type").asText()=="CONFIG" }
            if(apks.size!=1 || configs.size!=1 || artifacts.any { it.path("required").asBoolean() &&
                    it.path("type").asText() !in setOf("APK","CONFIG") }) throw TestRunConflict("MANIFEST_SCOPE_UNSUPPORTED")
            val apk=apks.single()
            if(!apk.path("required").asBoolean() || !configs.single().path("required").asBoolean() ||
                apk.path("packageName").asText()!="com.ricezhou.vsrqg.smoke" || apk.path("versionCode").asText()!="1")
                throw TestRunConflict("MANIFEST_SCOPE_UNSUPPORTED")
            if(TestJson.sha256(configured.configBytes)!="sha256:"+configs.single().path("checksum").path("value").asText())
                throw TestRunConflict("ENVIRONMENT_CONFIG_MISMATCH")
            val runId=ids.nextId("run_")
            val attemptId=AttemptIds.fromGenerated(ids.nextId("att_"))
            val commandId=ids.nextId("cmd_")
            val context=mapper.createObjectNode().put("schemaVersion","1.0").put("attemptId",attemptId).put("commandId",commandId)
                .put("projectId",project).put("releaseId",manifest.releaseId).put("manifestId",manifest.manifestId)
                .put("manifestDigest",manifest.digest).put("deviceId",actor.deviceId)
            context.set<JsonNode>("environment",environment)
            context.set<JsonNode>("apk",mapper.createObjectNode().put("checksum","sha256:"+apk.path("checksum").path("value").asText())
                .put("packageName",apk.path("packageName").asText()).put("versionCode",1)
                .put("signingCertificateSha256","sha256:"+apk.path("signingCertificateSha256").asText()))
            context.set<JsonNode>("plan",body.path("testPlan"))
            context.set<JsonNode>("case",plan.definition)
            validator.context(context)
            val run=RunRecord(runId,manifest.releaseId,project,actor.agentId,actor.deviceId,operator.principalId,
                RunState.WAITING_FOR_AGENT,now.plusSeconds(SmokePolicy.ALLOCATION_SECONDS),now.plusSeconds(SmokePolicy.RUN_SECONDS),null,null,now)
            val attempt=AttemptRecord(attemptId,runId,AttemptState.QUEUED,commandId,ids.nextId("lse_"),1,null,null,null,null,context,null,null)
            repository.insert(NewRun(run,attempt,manifest,plan,ids.nextId("env_"),configured.configBytes,environment))
            val response=mapper.createObjectNode().put("testRunId",runId).put("releaseId",manifest.releaseId)
                .put("state",run.state.name).put("createdAt",now.toString()).put("deadline",run.deadline.toString())
            governance.appendAudit(project,operator.principalId,"TEST_RUN_CREATED","TEST_RUN",runId,requestId,null,afterState=response)
            governance.appendOutbox("test.run.created","TEST_RUN",runId,response)
            response
        }
    }
}
