package com.ricezhou.vsrqg.demo

import java.util.UUID
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate

data class DemoActors(
    val runId: String,
    val projectId: String,
    val projectKey: String,
    val managerSubject: String,
    val viewerSubject: String,
)

data class M2DemoActors(
    val engineerSubject: String,
    val serviceSubject: String,
    val sourceId: String,
)

class M1DemoBootstrap(context: ApplicationContext) {
    private val jdbc = context.getBean(JdbcClient::class.java)
    private val transactions = context.getBean(PlatformTransactionManager::class.java)

    fun initialize(runId: String = UUID.randomUUID().toString()): DemoActors {
        val projectId = UUID.randomUUID().toString()
        val actors = DemoActors(runId, projectId, "demo-$runId", UUID.randomUUID().toString(), UUID.randomUUID().toString())
        TransactionTemplate(transactions).executeWithoutResult {
            jdbc.sql("INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :key, :name, now())")
                .param("id", projectId).param("key", actors.projectKey).param("name", "SYNTHETIC_DEMO").update()
            listOf(actors.managerSubject to "RELEASE_MANAGER", actors.viewerSubject to "VIEWER").forEach { (subject, role) ->
                insertPrincipal(projectId, subject, "USER", role)
            }
        }
        return actors
    }

    fun initializeM2(actors: DemoActors): M2DemoActors {
        val m2Actors = M2DemoActors(
            engineerSubject = UUID.randomUUID().toString(),
            serviceSubject = UUID.randomUUID().toString(),
            sourceId = UUID.randomUUID().toString(),
        )
        TransactionTemplate(transactions).executeWithoutResult {
            insertPrincipal(actors.projectId, m2Actors.engineerSubject, "USER", "ENGINEER")
            insertPrincipal(actors.projectId, m2Actors.serviceSubject, "SERVICE", "ENGINEER")
            jdbc.sql("""
                INSERT INTO issue_source(
                  id, project_id, source_key, source_type, adapter_version, mapping_version,
                  credential_reference, enabled, created_at, updated_at
                ) VALUES (:id, :project, :key, 'FIXTURE', :adapter, :mapping, NULL, true, now(), now())
            """.trimIndent())
                .param("id", m2Actors.sourceId)
                .param("project", actors.projectId)
                .param("key", "m2-demo-${actors.runId}")
                .param("adapter", M2DemoInputs.descriptor.adapterVersion)
                .param("mapping", "M2_DEMO_MAPPING_NOT_ACTIVATED")
                .update()
        }
        return m2Actors
    }

    private fun insertPrincipal(projectId: String, subject: String, principalType: String, role: String) {
        val principalId = UUID.randomUUID().toString()
        jdbc.sql("INSERT INTO principal(id, issuer, subject, principal_type, created_at) VALUES (:id, :issuer, :subject, :type, now())")
            .param("id", principalId).param("issuer", M1DemoIdentity.ISSUER).param("subject", subject)
            .param("type", principalType).update()
        jdbc.sql("INSERT INTO project_assignment(project_id, principal_id, role, created_at) VALUES (:project, :principal, :role, now())")
            .param("project", projectId).param("principal", principalId).param("role", role).update()
    }

    // The 422 registration API omits the rejected ID; this lookup never writes business state.
    fun lookupRejectedManifestId(projectId: String, releaseId: String): String =
        TransactionTemplate(transactions).apply { isReadOnly = true }.execute {
            jdbc.sql("""
                SELECT m.id FROM manifest_revision m JOIN release_record r ON r.id = m.release_id
                WHERE r.project_id = :project AND r.id = :release AND m.state = 'REJECTED'
            """.trimIndent()).param("project", projectId).param("release", releaseId)
                .query(String::class.java).single()
        } ?: error("DEMO_REJECTED_MANIFEST_NOT_FOUND")
}
