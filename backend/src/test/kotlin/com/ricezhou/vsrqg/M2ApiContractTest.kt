package com.ricezhou.vsrqg

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.access.domain.Permission
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class M2ApiContractTest {
    private val contract = ObjectMapper().readTree(Files.readString(repositoryRoot.resolve(OPENAPI_PATH)))

    @Test
    fun `M2 issue and traceability operations have approved permissions and exact idempotency metadata`() {
        APPROVED_OPERATIONS.forEach { approved ->
            val operation = operation(approved)

            assertThat(operation.path("x-permission").textValue())
                .describedAs("%s %s permission", approved.method.uppercase(), approved.path)
                .isEqualTo(approved.permission)
            assertThat(operation.path("x-idempotency-required").booleanValue())
                .describedAs("%s %s idempotency", approved.method.uppercase(), approved.path)
                .isEqualTo(approved.write)
        }
    }

    @Test
    fun `asynchronous issue sync and traceability verify return accepted`() {
        APPROVED_OPERATIONS.filter { it.async }.forEach { approved ->
            assertThat(operation(approved).path("responses").has("202"))
                .describedAs("%s %s has a 202 response", approved.method.uppercase(), approved.path)
                .isTrue()
        }
    }

    @Test
    fun `traceability ingest is restricted to a dedicated service identity scope`() {
        val ingest = operation(APPROVED_OPERATIONS.single { it.permission == TRACEABILITY_INGEST_SCOPE })
        val security = ingest.path("security")
        val serviceRequirement = security.path(0)
        val serviceOauth = contract.path("components").path("securitySchemes").path("serviceOauth")
        val flows = serviceOauth.path("flows")
        val serviceScopes = flows.path("clientCredentials").path("scopes")

        assertThat(ingest.path("x-service-identity-only").booleanValue()).isTrue()
        assertThat(security.size()).isEqualTo(1)
        assertThat(serviceRequirement.fieldNames().asSequence().toList()).containsExactly("serviceOauth")
        assertThat(serviceRequirement.path("serviceOauth").map(JsonNode::textValue))
            .containsExactly(TRACEABILITY_INGEST_SCOPE)
        assertThat(serviceOauth.path("type").textValue()).isEqualTo("oauth2")
        assertThat(flows.fieldNames().asSequence().toList()).containsExactly("clientCredentials")
        assertThat(serviceScopes.fieldNames().asSequence().toList()).containsExactly(TRACEABILITY_INGEST_SCOPE)
        assertThat(Permission.entries.map(Permission::scope)).doesNotContain(TRACEABILITY_INGEST_SCOPE)
    }

    private fun operation(approved: ApprovedOperation): JsonNode {
        val operation = contract.path("paths").path(approved.path).path(approved.method)
        assertThat(operation.isMissingNode)
            .describedAs("%s %s exists", approved.method.uppercase(), approved.path)
            .isFalse()
        return operation
    }

    private data class ApprovedOperation(
        val method: String,
        val path: String,
        val permission: String,
        val write: Boolean,
        val async: Boolean = false,
    )

    private companion object {
        const val OPENAPI_PATH = "contracts/openapi/v0.2/openapi.json"
        const val TRACEABILITY_INGEST_SCOPE = "traceability:ingest"
        val repositoryRoot: Path = generateSequence(
            Path.of(M2ApiContractTest::class.java.protectionDomain.codeSource.location.toURI()).toAbsolutePath(),
        ) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve(OPENAPI_PATH)) }
            ?: error("Cannot locate repository root containing $OPENAPI_PATH")
        val APPROVED_OPERATIONS = listOf(
            ApprovedOperation("post", "/api/v1/issue-sources/{sourceId}/sync", "issue:sync", write = true, async = true),
            ApprovedOperation("get", "/api/v1/issue-sync-runs/{syncRunId}", "issue:read", write = false),
            ApprovedOperation("post", "/api/v1/releases/{releaseId}/issue-snapshots", "issue:snapshot", write = true),
            ApprovedOperation("post", "/api/v1/traceability/facts:ingest", TRACEABILITY_INGEST_SCOPE, write = true),
            ApprovedOperation(
                "post",
                "/api/v1/releases/{releaseId}/traceability:verify",
                "traceability:verify",
                write = true,
                async = true,
            ),
            ApprovedOperation("get", "/api/v1/releases/{releaseId}/traceability", "traceability:read", write = false),
        )
    }
}
