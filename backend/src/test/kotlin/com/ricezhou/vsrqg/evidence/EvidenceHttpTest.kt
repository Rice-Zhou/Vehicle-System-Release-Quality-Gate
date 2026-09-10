package com.ricezhou.vsrqg.evidence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import com.ricezhou.vsrqg.access.adapter.SecurityConfig
import com.ricezhou.vsrqg.access.application.AuthenticatedPrincipalResolver
import com.ricezhou.vsrqg.access.application.ProjectAuthorization
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.access.domain.Principal
import com.ricezhou.vsrqg.evidence.adapter.*
import com.ricezhou.vsrqg.evidence.application.*
import com.ricezhou.vsrqg.evidence.domain.EvidenceState
import com.ricezhou.vsrqg.shared.application.GovernanceStore
import com.ricezhou.vsrqg.shared.application.IdempotentExecutor
import com.ricezhou.vsrqg.shared.application.IdempotencyConflict
import com.ricezhou.vsrqg.shared.id.UuidV7IdGenerator
import com.ricezhou.vsrqg.shared.problem.ProblemHandler
import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.shared.time.TimeProvider
import com.ricezhou.vsrqg.shared.web.RequestIdFilter
import com.ricezhou.vsrqg.testmanagement.AgentTlsTestInitializer
import com.ricezhou.vsrqg.testmanagement.TestAgentCertificates
import com.ricezhou.vsrqg.testmanagement.adapter.AgentSecurityConfiguration
import com.ricezhou.vsrqg.testmanagement.adapter.CertificateFingerprintExtractor
import com.ricezhou.vsrqg.testmanagement.adapter.TestWire
import com.ricezhou.vsrqg.testmanagement.application.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Real TLS, servlet, security and payload files. Database/identity ports are explicit test doubles. */
@SpringBootTest(classes=[EvidenceHttpTest.Application::class],webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties=["vsrqg.demo.evidence.enabled=true","vsrqg.demo.evidence.upload-timeout=PT0.5S","management.endpoint.health.validate-group-membership=false"])
@ContextConfiguration(initializers=[AgentTlsTestInitializer::class])
@org.springframework.test.context.ActiveProfiles("evidence-http-isolated")
@Timeout(60)
class EvidenceHttpTest {
    @LocalServerPort var port:Int=0
    @Autowired lateinit var mapper:ObjectMapper
    @Autowired lateinit var state:State
    @Autowired lateinit var uploads:EvidenceUploadService
    @Autowired lateinit var controller:EvidenceUploadController
    @Autowired lateinit var repository:MemoryEvidence
    @BeforeEach fun reset() { state.active=true; state.auditFails=false; state.high=false; state.now=Instant.parse("2026-09-09T00:00:00Z") }
    private fun call(method:String,path:String,body:ByteArray=byteArrayOf(),agent:Boolean=true,token:String?=null,key:String=UUID.randomUUID().toString(),range:Boolean=false):HttpResponse<ByteArray> {
        val client=HttpClient.newBuilder().sslContext(TestAgentCertificates.sslContext(if(agent) "trusted" else null)).connectTimeout(Duration.ofSeconds(5)).build()
        val request=HttpRequest.newBuilder(URI("https://localhost:$port$path")).timeout(Duration.ofSeconds(10))
            .header("Content-Type",if(method=="PUT") "application/octet-stream" else "application/json").header("Idempotency-Key",key)
            .method(method,if(body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        if(token!=null) request.header("Authorization","Bearer $token")
        if(range) request.header("Range","bytes=0-1")
        return client.send(request.build(),HttpResponse.BodyHandlers.ofByteArray())
    }
    private fun json(response:HttpResponse<ByteArray>,status:Int):JsonNode {
        assertThat(response.statusCode()).describedAs(String(response.body())).isEqualTo(status)
        return mapper.readTree(response.body())
    }
    private fun declaration(bytes:ByteArray)=mapper.createObjectNode().put("messageType","EVIDENCE_UPLOAD_CREATE").put("protocolVersion","1.0")
        .put("attemptId",ATTEMPT).put("evidenceType","LOG").put("contentType","text/plain").put("sizeBytes",bytes.size)
        .put("payloadChecksum",TestJson.sha256(bytes)).put("capturedAt",state.now.toString()).put("collectorVersion","1.0")
    private fun create(body:JsonNode)=json(call("POST","/agent-api/v1/evidence/uploads",body.toString().toByteArray()),201)
    private fun complete(session:JsonNode,body:ObjectNode,status:Int=200):JsonNode {
        val complete=body.deepCopy().apply { remove(listOf("attemptId","evidenceType"));put("messageType","EVIDENCE_UPLOAD_COMPLETE") }
        return json(call("POST","/agent-api/v1/evidence/uploads/${session.path("uploadId").asText()}:complete",complete.toString().toByteArray()),status)
    }
    @Test fun `real mTLS upload and JWT grant download preserve bytes across authenticated endpoints`() {
        val bytes="synthetic LOG\n".toByteArray(); val body=declaration(bytes); val session=create(body)
        val upload=session.path("uploadUrl").asText()
        assertThat(call("PUT",upload,bytes).statusCode()).isEqualTo(204)
        assertThat(call("PUT",upload,bytes).statusCode()).isEqualTo(204)
        val metadata=complete(session,body); assertThat(metadata.path("state").asText()).isEqualTo("AVAILABLE")
        val id=metadata.path("evidenceId").asText()
        val grant=json(call("POST","/api/v1/evidence/$id:download","""{"reason":"diagnose"}""".toByteArray(),false,"reader",key="grant-positive"),200)
        val downloaded=call("GET",grant.path("url").asText(),agent=false,token="reader")
        assertThat(downloaded.statusCode()).isEqualTo(200); assertThat(downloaded.body()).isEqualTo(bytes)
        assertThat(downloaded.headers().firstValue("Cache-Control").orElse("")).isEqualTo("no-store")
        assertThat(downloaded.headers().firstValue("Location")).isEmpty
        assertThat(call("GET",grant.path("url").asText(),agent=false,token="other").statusCode()).isEqualTo(403)
        assertThat(call("GET",grant.path("url").asText(),agent=false).statusCode()).isEqualTo(401)
        assertThat(call("GET",grant.path("url").asText(),agent=false,token="reader",range=true).statusCode()).isEqualTo(416)
        state.now=state.now.plusSeconds(60)
        assertThat(call("GET",grant.path("url").asText(),agent=false,token="reader").statusCode()).isEqualTo(409)
        assertThat(call("POST","/api/v1/evidence/$id:download","""{"reason":"diagnose"}""".toByteArray(),false,"reader",key="grant-positive").statusCode()).isEqualTo(409)
    }
    @Test fun `HIGH requires sensitive scope and current role and audit failure releases no bytes`() {
        state.high=true
        val bytes="high".toByteArray();val body=declaration(bytes);val session=create(body)
        assertThat(call("PUT",session.path("uploadUrl").asText(),bytes).statusCode()).isEqualTo(204)
        val id=complete(session,body).path("evidenceId").asText();val path="/api/v1/evidence/$id:download"
        assertThat(call("POST",path,"""{"reason":"diagnose"}""".toByteArray(),false,"reader").statusCode()).isEqualTo(403)
        val grant=json(call("POST",path,"""{"reason":"diagnose"}""".toByteArray(),false,"owner"),200)
        assertThat(call("GET",grant.path("url").asText(),agent=false,token="owner").statusCode()).isEqualTo(200)
        state.auditFails=true
        val failure=call("GET",grant.path("url").asText(),agent=false,token="owner")
        assertThat(failure.statusCode()).isEqualTo(500)
        assertThat(String(failure.body())).doesNotContain("high").doesNotContain(storage.toString())
    }
    @Test fun `strict wire lease and digest errors cannot generate AVAILABLE`() {
        val bytes="hello".toByteArray(); val body=declaration(bytes); val session=create(body)
        assertThat(call("PUT",session.path("uploadUrl").asText(),"too-long".toByteArray()).statusCode()).isEqualTo(413)
        assertThat(call("PUT",session.path("uploadUrl").asText(),bytes,agent=false,token="reader").statusCode()).isEqualTo(401)
        assertThat(call("PUT",session.path("uploadUrl").asText(),bytes).statusCode()).isEqualTo(204)
        state.active=false; complete(session,body,409); state.active=true
        val malformed=body.toString()+" {}"
        assertThat(call("POST","/agent-api/v1/evidence/uploads",malformed.toByteArray()).statusCode()).isEqualTo(400)
        Files.write(storage.resolve("${session.path("uploadId").asText()}.payload"),"wrong".toByteArray())
        complete(session,body,409)
        assertThat(repository.session(session.path("uploadId").asText()).state).isEqualTo(EvidenceState.REJECTED)
    }
    // Real Spring annotation proxy and JDBC transaction manager; JDBC calls are observed test doubles.
    private fun transactionalUploads(connection:java.sql.Connection):EvidenceUploadService {
        val dataSource=org.mockito.Mockito.mock(javax.sql.DataSource::class.java)
        org.mockito.Mockito.`when`(dataSource.connection).thenReturn(connection)
        org.mockito.Mockito.`when`(connection.autoCommit).thenReturn(true)
        val interceptor=org.springframework.transaction.interceptor.TransactionInterceptor().apply {
            transactionManager=org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource)
            transactionAttributeSource=org.springframework.transaction.annotation.AnnotationTransactionAttributeSource()
        }
        return org.springframework.aop.framework.ProxyFactory(uploads).apply {
            isProxyTargetClass=true;addAdvice(interceptor)
        }.proxy as EvidenceUploadService
    }
    private fun completeBody(body:ObjectNode)=body.deepCopy().apply {
        remove(listOf("attemptId","evidenceType"));put("messageType","EVIDENCE_UPLOAD_COMPLETE")
    }
    @Test fun `Complete missing file requests JDBC rollback through its real Spring transaction proxy`() {
        val body=declaration("hello".toByteArray());val session=create(body)
        val connection=org.mockito.Mockito.mock(java.sql.Connection::class.java)
        val service=transactionalUploads(connection)
        assertThatThrownBy { service.complete(CertificateFingerprintExtractor().extractPrincipal(TestAgentCertificates.trustedCertificate).toString(),
            session.path("uploadId").asText(),completeBody(body),UUID.randomUUID().toString(),"req_io_rollback") }
            .isInstanceOf(java.nio.file.NoSuchFileException::class.java)
        org.mockito.Mockito.verify(connection).rollback()
        org.mockito.Mockito.verify(connection,org.mockito.Mockito.never()).commit()
    }
    @Test fun `Complete integrity rejection retains explicit noRollbackFor and commits REJECTED`() {
        val body=declaration("hello".toByteArray());val session=create(body);val id=session.path("uploadId").asText()
        assertThat(call("PUT",session.path("uploadUrl").asText(),"hello".toByteArray()).statusCode()).isEqualTo(204)
        Files.write(storage.resolve("$id.payload"),"other".toByteArray())
        val connection=org.mockito.Mockito.mock(java.sql.Connection::class.java)
        val service=transactionalUploads(connection)
        assertThatThrownBy { service.complete(CertificateFingerprintExtractor().extractPrincipal(TestAgentCertificates.trustedCertificate).toString(),
            id,completeBody(body),UUID.randomUUID().toString(),"req_integrity_commit") }.isInstanceOf(EvidenceRejected::class.java)
        org.mockito.Mockito.verify(connection).commit()
        org.mockito.Mockito.verify(connection,org.mockito.Mockito.never()).rollback()
        assertThat(repository.session(id).state).isEqualTo(EvidenceState.REJECTED)
    }
    @Test fun `integer beyond Long is rejected before Session files or idempotency side effects`() {
        val body=declaration("hello".toByteArray())
        val invalid=body.deepCopy().put("sizeBytes",java.math.BigInteger("18446744073709551621"))
        val sessionsBefore=repository.records.toMap();val grantsBefore=repository.grants.toMap()
        val filesBefore=Files.list(storage).use { it.toList().toSet() }
        val key=UUID.randomUUID().toString()
        val response=call("POST","/agent-api/v1/evidence/uploads",invalid.toString().toByteArray(),key=key)
        assertThat(json(response,409).path("code").asText()).isEqualTo("EVIDENCE_DECLARATION_INVALID")
        assertThat(repository.records).isEqualTo(sessionsBefore)
        assertThat(repository.grants).isEqualTo(grantsBefore)
        assertThat(Files.list(storage).use { it.toList().toSet() }).isEqualTo(filesBefore)
        // Reusing the rejected request key proves no successful Create response was cached.
        json(call("POST","/agent-api/v1/evidence/uploads",body.toString().toByteArray(),key=key),201)
    }
    @Test fun `short and wrong hash candidates permit correct same Session retransmission`() {
        val bytes="hello".toByteArray();val body=declaration(bytes);val session=create(body);val url=session.path("uploadUrl").asText()
        assertThat(call("PUT",url,"hel".toByteArray()).statusCode()).isEqualTo(409)
        assertThat(Files.exists(storage.resolve("${session.path("uploadId").asText()}.payload"))).isFalse()
        assertThat(call("PUT",url,"other".toByteArray()).statusCode()).isEqualTo(409)
        assertThat(Files.exists(storage.resolve("${session.path("uploadId").asText()}.payload"))).isFalse()
        assertThat(call("PUT",url,bytes).statusCode()).isEqualTo(204)
        assertThat(complete(session,body).path("state").asText()).isEqualTo("AVAILABLE")
    }
    @Test fun `total upload deadline terminates a waiting TLS body and cleans its candidate`() {
        val session=create(declaration("hello".toByteArray()));val url=session.path("uploadUrl").asText()
        val before=System.nanoTime()
        val response=TestAgentCertificates.sslContext("trusted").socketFactory.createSocket("localhost",port).use { socket->
            socket.soTimeout=3000
            val output=socket.getOutputStream()
            output.write(("PUT $url HTTP/1.1\r\nHost: localhost:$port\r\nContent-Type: application/octet-stream\r\nContent-Length: 5\r\nConnection: close\r\n\r\nh").toByteArray())
            output.flush()
            // Headers and one byte reach Tomcat immediately; EOF never arrives before its response.
            socket.getInputStream().bufferedReader().readText()
        }
        assertThat(response).startsWith("HTTP/1.1 408").contains("UPLOAD_TIMEOUT").contains("requestId")
        assertThat(Duration.ofNanos(System.nanoTime()-before)).isLessThan(Duration.ofSeconds(3))
        assertThat(Files.exists(storage.resolve("${session.path("uploadId").asText()}.payload"))).isFalse()
        Files.list(storage).use { files->assertThat(files.anyMatch { it.fileName.toString().endsWith(".partial") }).isFalse() }
        assertThat(call("PUT",url,"hello".toByteArray()).statusCode()).isEqualTo(204)
    }
    @Test fun `postflight uses current authority after network reception started`() {
        val body=declaration("hello".toByteArray());val session=create(body);val url=session.path("uploadUrl").asText()
        TestAgentCertificates.sslContext("trusted").socketFactory.createSocket("localhost",port).use { socket->
            socket.soTimeout=3000
            val output=socket.getOutputStream()
            output.write(("PUT $url HTTP/1.1\r\nHost: localhost:$port\r\nContent-Type: application/octet-stream\r\nContent-Length: 5\r\nConnection: close\r\n\r\nh").toByteArray());output.flush()
            val until=System.nanoTime()+Duration.ofMillis(400).toNanos()
            fun receiving()=Files.list(storage).use { files->files.anyMatch { it.fileName.toString().endsWith(".partial") } }
            while(!receiving() && System.nanoTime()<until) Thread.sleep(5)
            assertThat(receiving()).isTrue()
            state.active=false
            output.write("ello".toByteArray());output.flush()
            assertThat(socket.getInputStream().bufferedReader().readText()).startsWith("HTTP/1.1 409").contains("STALE_LEASE")
        }
        assertThat(Files.exists(storage.resolve("${session.path("uploadId").asText()}.payload"))).isFalse()
        assertThat(repository.session(session.path("uploadId").asText()).state).isEqualTo(EvidenceState.PENDING_UPLOAD)
    }
    @Test fun `EOF timeout and error callbacks settle one response and clean only their candidate`() {
        repeat(10) {
            val session=create(declaration("hello".toByteArray()));val id=session.path("uploadId").asText()
            lateinit var listener:jakarta.servlet.ReadListener
            val stream=object:jakarta.servlet.ServletInputStream() {
                private val input="hello".byteInputStream()
                override fun read()=input.read()
                override fun isReady()=true
                override fun isFinished()=input.available()==0
                override fun setReadListener(value:jakarta.servlet.ReadListener) { listener=value }
            }
            val request=object:org.springframework.mock.web.MockHttpServletRequest() { override fun getInputStream()=stream }
            request.isAsyncSupported=true
            request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE,"req_race")
            val response=org.springframework.mock.web.MockHttpServletResponse()
            val authentication=org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                CertificateFingerprintExtractor().extractPrincipal(TestAgentCertificates.trustedCertificate),"")
            controller.put(authentication,id,request,response)
            listener.onDataAvailable()
            val async=request.asyncContext as org.springframework.mock.web.MockAsyncContext
            val timeout=async.listeners.single()
            val sequence=java.util.concurrent.atomic.AtomicInteger()
            com.ricezhou.vsrqg.shared.runConcurrently(3) {
                when(sequence.getAndIncrement()) {
                    0->listener.onAllDataRead()
                    1->timeout.onTimeout(jakarta.servlet.AsyncEvent(async))
                    else->listener.onError(java.io.IOException("synthetic disconnect"))
                }
            }
            assertThat(response.status).isIn(204,408,409)
            val published=Files.exists(storage.resolve("$id.payload"))
            assertThat(published).isEqualTo(response.status==204)
            assertThat(repository.session(id).state).isEqualTo(if(published) EvidenceState.UPLOADING else EvidenceState.PENDING_UPLOAD)
            if(published) assertThat(Files.readAllBytes(storage.resolve("$id.payload"))).isEqualTo("hello".toByteArray())
            Files.list(storage).use { files->assertThat(files.anyMatch { it.fileName.toString().endsWith(".partial") }).isFalse() }
        }
    }
    class State { @Volatile var active=true;var auditFails=false;var high=false;var now=Instant.parse("2026-09-09T00:00:00Z") }
    class MemoryEvidence(private val state:State):EvidenceRepository {
        val records=java.util.concurrent.ConcurrentHashMap<String,EvidenceSession>();val grants=java.util.concurrent.ConcurrentHashMap<String,DownloadGrant>()
        override fun session(id:String,lock:Boolean)=records[id]?:throw EvidenceNotFound()
        override fun evidence(id:String,lock:Boolean)=records.values.singleOrNull { it.evidenceId==id }?:throw EvidenceNotFound()
        override fun insert(session:EvidenceSession) { records[session.id]=if(state.high) session.copy(sensitivity="HIGH") else session }
        override fun state(id:String,state:EvidenceState) { records[id]=session(id).copy(state=state) }
        override fun available(id:String,metadata:JsonNode) { records[id]=session(id).copy(state=EvidenceState.AVAILABLE,metadata=metadata) }
        override fun sessions(attemptId:String)=records.values.filter { it.binding.attemptId==attemptId }
        override fun inventory(ids:Set<String>)=ids.map { evidence(it) }
        override fun observe(id:String,code:String,now:Instant) {}
        override fun latestObservation(id:String):String?=null
        override fun saveGrant(grant:DownloadGrant) { grants[grant.id]=grant }
        override fun grant(id:String)=grants[id]?:throw EvidenceNotFound()
    }
    @org.springframework.context.annotation.Configuration
    @org.springframework.context.annotation.Profile("evidence-http-isolated")
    @EnableAutoConfiguration(exclude=[DataSourceAutoConfiguration::class,FlywayAutoConfiguration::class])
    @Import(SecurityConfig::class,AgentSecurityConfiguration::class,TestWire::class,LocalEvidenceConfiguration::class,
        EvidenceUploadController::class,EvidenceQueryController::class,EvidenceProblemHandler::class,EvidenceConflictProblemHandler::class,ProblemHandler::class,
        ProblemWriter::class,RequestIdFilter::class,UuidV7IdGenerator::class,EvidenceUploadService::class,EvidenceDownloadService::class)
    class Application {
        @Bean fun state()=State()
        @Bean fun repository(state:State)=MemoryEvidence(state)
        @Bean fun time(state:State)=TimeProvider { state.now }
        @Bean fun attempts(state:State)=object:AttemptAccess {
            override fun lockWritable(actor:AgentActor,attemptId:String,now:Instant):AttemptBinding {
                if(!state.active) throw EvidenceConflict("STALE_LEASE")
                if(attemptId!=ATTEMPT) throw AccessDeniedException("Wrong attempt")
                return AttemptBinding(ATTEMPT,"run_test","rel_test","project_test","agent_test","device_test","lease_test",1)
            }
            override fun context(actor:AgentActor,attemptId:String):JsonNode=throw UnsupportedOperationException()
        }
        @Bean fun agents()=AgentAccess { fingerprint,_->
            if(fingerprint!=CertificateFingerprintExtractor().extractPrincipal(TestAgentCertificates.trustedCertificate)) throw AccessDeniedException("Unknown agent")
            AgentActor("svc_test","project_test","agent_test","device_test")
        }
        @Bean fun principals()=object:AuthenticatedPrincipalResolver {
            override fun resolve(issuer:String?,subject:String?,principalType:String?)=Principal(requireNotNull(issuer),requireNotNull(subject),false)
        }
        @Bean fun authorizer()=ProjectAuthorizer { principal,project,permission->
            if(project!="project_test" || (permission==Permission.EVIDENCE_READ_SENSITIVE && principal.subject!="owner")) throw AccessDeniedException("Project permission denied")
            ProjectAuthorization(principal.subject)
        }
        @Bean fun decoder()=JwtDecoder { token->Jwt.withTokenValue(token).header("alg","synthetic")
            .issuer("https://idp.vsrqg.test").subject(token).claim("principal_type","USER")
            .claim("scope",if(token=="owner") "evidence:read evidence:read:sensitive" else "evidence:read").build() }
        @Bean fun idempotency()=object:IdempotentExecutor {
            val records=mutableMapOf<String,Pair<String,Any>>()
            @Synchronized override fun <T:Any> execute(scope:String,principalId:String,key:String,requestDigest:String,responseType:Class<T>,action:()->T):T {
                val identity="$scope/$principalId/$key";val old=records[identity]
                if(old!=null) { if(old.first!=requestDigest) throw IdempotencyConflict(scope);return responseType.cast(old.second) }
                return action().also { records[identity]=requestDigest to it }
            }
        }
        @Bean fun governance(state:State)=object:GovernanceStore {
            override fun appendAudit(projectId:String,actorId:String,action:String,resourceType:String,resourceId:String,requestId:String,reason:String?,beforeState:JsonNode?,afterState:JsonNode?) {
                if(state.auditFails) error("SYNTHETIC_AUDIT_FAILURE")
            }
            override fun appendOutbox(eventType:String,aggregateType:String,aggregateId:String,payload:JsonNode) {}
        }
    }
    companion object {
        const val ATTEMPT="01992560-aaab-7000-8000-123456789abc"
        val storage=ownedTestRoot(Files.createTempDirectory("evidence-http-"))
        @JvmStatic @DynamicPropertySource fun properties(registry:DynamicPropertyRegistry) { registry.add("vsrqg.demo.evidence.root") { storage.toString() } }
    }
}
