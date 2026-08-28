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
    private val compatibilityBaseline = ObjectMapper().readTree(
        Files.readString(repositoryRoot.resolve(COMPATIBILITY_BASELINE_PATH)),
    )

    @Test
    fun `M2 issue and traceability operations have approved permissions and exact idempotency metadata`() {
        APPROVED_OPERATIONS.forEach { approved ->
            val operation = operation(approved)

            assertThat(operation.path("x-permission").textValue())
                .describedAs("%s %s permission", approved.method.uppercase(), approved.path)
                .isEqualTo(approved.permission)
            assertExactIdempotency(operation, approved)
        }
    }

    @Test
    fun `user operations require exactly their dedicated OIDC scope`() {
        APPROVED_OPERATIONS.filterNot { it.permission == TRACEABILITY_INGEST_SCOPE }.forEach { approved ->
            val security = operation(approved).path("security")
            val requirement = security.path(0)

            assertThat(security.size())
                .describedAs("%s %s security requirement count", approved.method.uppercase(), approved.path)
                .isEqualTo(1)
            assertThat(requirement.fieldNames().asSequence().toList())
                .describedAs("%s %s security schemes", approved.method.uppercase(), approved.path)
                .containsExactly("oidc")
            assertThat(requirement.path("oidc").map(JsonNode::textValue))
                .describedAs("%s %s OIDC scopes", approved.method.uppercase(), approved.path)
                .containsExactly(approved.permission)
        }
    }

    @Test
    fun `asynchronous issue sync and traceability verify return accepted`() {
        APPROVED_OPERATIONS.filter { it.async }.forEach { approved ->
            val successCodes = operation(approved).path("responses").fieldNames().asSequence()
                .filter { (HTTP_STATUS.matches(it) && it.startsWith("2")) || it.equals("2XX", ignoreCase = true) }
                .toList()

            assertThat(successCodes)
                .describedAs("%s %s successful responses", approved.method.uppercase(), approved.path)
                .containsExactlyInAnyOrder("202")
        }
    }

    @Test
    fun `traceability ingest has a strict versioned fact batch request contract`() {
        val ingestOperation = operation(APPROVED_OPERATIONS.single { it.permission == TRACEABILITY_INGEST_SCOPE })
        val requestBody = contract.path("components").path("requestBodies").path("TraceabilityFactBatch")
        val batchSchema = contract.path("components").path("schemas").path("TraceabilityFactBatch")
        val factSchema = contract.path("components").path("schemas").path("TraceabilityFactInput")
        val batchProperties = batchSchema.path("properties")
        val factProperties = factSchema.path("properties")

        assertThat(ingestOperation.path("requestBody").path("\$ref").textValue())
            .isEqualTo(TRACEABILITY_FACT_BATCH_REQUEST_REF)
        assertThat(baselineOperation("post", TRACEABILITY_INGEST_PATH).path("requestBodyRef").textValue())
            .isEqualTo(TRACEABILITY_FACT_BATCH_REQUEST_REF)
        assertThat(requestBody.path("required").isBoolean && requestBody.path("required").booleanValue()).isTrue()
        assertThat(requestBody.path("content").path("application/json").path("schema").path("\$ref").textValue())
            .isEqualTo(TRACEABILITY_FACT_BATCH_SCHEMA_REF)

        assertStrictObject(batchSchema, BATCH_FIELDS)
        assertThat(batchSchema.path("required").map(JsonNode::textValue)).containsExactlyInAnyOrderElementsOf(BATCH_FIELDS)
        assertThat(batchProperties.path("schemaVersion").path("const").intValue()).isEqualTo(1)
        assertThat(batchProperties.path("artifactSha256").path("pattern").textValue())
            .isEqualTo(LOWERCASE_SHA256_PATTERN)
        assertThat(batchProperties.path("facts").path("minItems").intValue()).isEqualTo(1)
        assertThat(batchProperties.path("facts").path("items").path("\$ref").textValue())
            .isEqualTo(TRACEABILITY_FACT_INPUT_SCHEMA_REF)

        assertStrictObject(factSchema, FACT_FIELDS)
        assertThat(factSchema.path("required").map(JsonNode::textValue)).containsExactlyInAnyOrderElementsOf(FACT_FIELDS)
        assertThat(factProperties.path("edgeType").path("enum").map(JsonNode::textValue))
            .containsExactly("ISSUE_COMMIT", "COMMIT_BUILD", "BUILD_ARTIFACT")
        val acceptedFields = (batchProperties.fieldNames().asSequence() + factProperties.fieldNames().asSequence())
            .map(String::lowercase)
            .toList()
        assertThat(acceptedFields)
            .doesNotContain("fixed", "included", "verified")
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

    private fun baselineOperation(method: String, path: String): JsonNode = compatibilityBaseline.path("operations")
        .firstOrNull { it.path("method").textValue() == method && it.path("path").textValue() == path }
        ?: error("Missing compatibility baseline operation: ${method.uppercase()} $path")

    private fun assertExactIdempotency(operation: JsonNode, approved: ApprovedOperation) {
        val idempotency = operation.get("x-idempotency-required")
        val exact = if (approved.write) {
            idempotency?.isBoolean == true && idempotency.booleanValue()
        } else {
            idempotency == null || (idempotency.isBoolean && !idempotency.booleanValue())
        }
        assertThat(exact)
            .describedAs("%s %s idempotency", approved.method.uppercase(), approved.path)
            .isTrue()
    }

    private fun assertStrictObject(schema: JsonNode, fields: List<String>) {
        assertThat(schema.path("type").textValue()).isEqualTo("object")
        assertThat(schema.path("additionalProperties").isBoolean).isTrue()
        assertThat(schema.path("additionalProperties").booleanValue()).isFalse()
        assertThat(schema.path("properties").fieldNames().asSequence().toList())
            .containsExactlyInAnyOrderElementsOf(fields)
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
        const val COMPATIBILITY_BASELINE_PATH = "contracts/openapi/v0.2/compatibility-baseline.json"
        const val TRACEABILITY_INGEST_SCOPE = "traceability:ingest"
        const val TRACEABILITY_INGEST_PATH = "/api/v1/traceability/facts:ingest"
        const val TRACEABILITY_FACT_BATCH_REQUEST_REF = "#/components/requestBodies/TraceabilityFactBatch"
        const val TRACEABILITY_FACT_BATCH_SCHEMA_REF = "#/components/schemas/TraceabilityFactBatch"
        const val TRACEABILITY_FACT_INPUT_SCHEMA_REF = "#/components/schemas/TraceabilityFactInput"
        const val LOWERCASE_SHA256_PATTERN = "^[0-9a-f]{64}$"
        val HTTP_STATUS = Regex("^[1-5][0-9]{2}$")
        val BATCH_FIELDS = listOf(
            "schemaVersion",
            "project",
            "providerReference",
            "sourceRevision",
            "artifactSha256",
            "proofReference",
            "facts",
        )
        val FACT_FIELDS = listOf("edgeType", "fromEntityId", "toEntityId")
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
