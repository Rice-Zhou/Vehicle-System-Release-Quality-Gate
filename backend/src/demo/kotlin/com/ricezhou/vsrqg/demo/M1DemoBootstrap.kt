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

class M1DemoBootstrap(context: ApplicationContext) {
    private val jdbc = context.getBean(JdbcClient::class.java)
    private val transactions = context.getBean(PlatformTransactionManager::class.java)

    fun initialize(): DemoActors {
        val runId = UUID.randomUUID().toString()
        val projectId = UUID.randomUUID().toString()
        val actors = DemoActors(runId, projectId, "demo-$runId", UUID.randomUUID().toString(), UUID.randomUUID().toString())
        TransactionTemplate(transactions).executeWithoutResult {
            jdbc.sql("INSERT INTO project(id, project_key, name, created_at) VALUES (:id, :key, :name, now())")
                .param("id", projectId).param("key", actors.projectKey).param("name", "SYNTHETIC_DEMO").update()
            listOf(actors.managerSubject to "RELEASE_MANAGER", actors.viewerSubject to "VIEWER").forEach { (subject, role) ->
                val principalId = UUID.randomUUID().toString()
                jdbc.sql("INSERT INTO principal(id, issuer, subject, principal_type, created_at) VALUES (:id, :issuer, :subject, 'USER', now())")
                    .param("id", principalId).param("issuer", M1DemoIdentity.ISSUER).param("subject", subject).update()
                jdbc.sql("INSERT INTO project_assignment(project_id, principal_id, role, created_at) VALUES (:project, :principal, :role, now())")
                    .param("project", projectId).param("principal", principalId).param("role", role).update()
            }
        }
        return actors
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
