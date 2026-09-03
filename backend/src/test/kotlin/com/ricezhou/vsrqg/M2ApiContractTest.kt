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
    fun `issue snapshot request accepts only source id and returns created`() {
        val operation = operation(APPROVED_OPERATIONS.single { it.permission == "issue:snapshot" })
        val schema = contract.path("components").path("schemas").path("IdentifierInput")
        assertStrictObject(schema, listOf("sourceId"))
        assertThat(operation.path("requestBody").path("\$ref").textValue())
            .isEqualTo("#/components/requestBodies/IdentifierInput")
        assertThat(operation.path("responses").fieldNames().asSequence().filter { it.startsWith("2") }.toList())
            .containsExactly("201")
    }

    @Test
    fun `mapping profile activation has a strict definition request and metadata-only created response`() {
        val approved = APPROVED_OPERATIONS.single { it.permission == ISSUE_CONFIGURE_SCOPE }
        val activation = operation(approved)
        val requestBody = contract.path("components").path("requestBodies").path("ActivateIssueMappingProfile")
        val requestSchema = contract.path("components").path("schemas").path("ActivateIssueMappingProfileRequest")
        val requestProperties = requestSchema.path("properties")
        val responseSchema = contract.path("components").path("schemas").path("IssueMappingProfileActivation")
        val responseProperties = responseSchema.path("properties")

        assertThat(activation.path("requestBody").path("\$ref").textValue())
            .isEqualTo(ACTIVATE_MAPPING_PROFILE_REQUEST_REF)
        assertThat(activation.path("parameters").map { it.path("\$ref").textValue() })
            .containsExactly(IDEMPOTENCY_KEY_PARAMETER_REF)
        assertThat(contract.path("paths").path(MAPPING_PROFILE_ACTIVATION_PATH).path("parameters").path(0)
            .path("\$ref").textValue()).isEqualTo(SOURCE_ID_PARAMETER_REF)
        assertThat(baselineOperation("post", MAPPING_PROFILE_ACTIVATION_PATH).path("requestBodyRef").textValue())
            .isEqualTo(ACTIVATE_MAPPING_PROFILE_REQUEST_REF)
        assertThat(requestBody.path("required").isBoolean && requestBody.path("required").booleanValue()).isTrue()
        assertThat(requestBody.path("content").path("application/json").path("schema").path("\$ref").textValue())
            .isEqualTo(ACTIVATE_MAPPING_PROFILE_SCHEMA_REF)

        assertStrictObject(requestSchema, MAPPING_PROFILE_REQUEST_FIELDS)
        assertThat(requestSchema.path("maxProperties").intValue()).isEqualTo(MAPPING_PROFILE_REQUEST_FIELDS.size)
        assertThat(requestSchema.path("required").map(JsonNode::textValue))
            .containsExactlyInAnyOrderElementsOf(MAPPING_PROFILE_REQUEST_FIELDS)
        assertThat(requestProperties.path("schemaVersion").path("const").textValue())
            .isEqualTo("jira-mapping-profile/v1")
        assertThat(requestProperties.path("normalizationVersion").path("const").textValue())
            .isEqualTo("unicode-nfc-trim-root-lower/v1")
        assertThat(requestProperties.path("unknownStatusPolicy").path("const").textValue())
            .isEqualTo("MAP_TO_UNKNOWN_WITH_WARNING")
        assertThat(requestProperties.path("unknownSeverityPolicy").path("const").textValue())
            .isEqualTo("MAP_TO_UNKNOWN_WITH_WARNING")
        assertThat(requestProperties.fieldNames().asSequence().toList())
            .doesNotContain("mappingVersion", "adapterVersion")
        listOf("statusAliases", "severityAliases").forEach { aliasesField ->
            assertThat(requestProperties.path(aliasesField).path("additionalProperties").path("maxItems").intValue())
                .describedAs("%s alias array maxItems", aliasesField)
                .isEqualTo(256)
            assertThat(
                requestProperties.path(aliasesField).path("additionalProperties").path("items").path("\$ref").textValue(),
            ).isEqualTo(MAPPING_ALIAS_TOKEN_SCHEMA_REF)
        }
        assertThat(contract.path("components").path("schemas").path("IssueMappingAliasToken").path("maxLength").intValue())
            .isEqualTo(120)

        val successCodes = activation.path("responses").fieldNames().asSequence()
            .filter { HTTP_STATUS.matches(it) && it.startsWith("2") }
            .toList()
        assertThat(successCodes).containsExactly("201")
        assertThat(activation.path("responses").path("201").path("\$ref").textValue())
            .isEqualTo(MAPPING_PROFILE_ACTIVATED_RESPONSE_REF)
        assertThat(contract.path("components").path("responses").path("IssueMappingProfileActivated")
            .path("content").path("application/json").path("schema").path("\$ref").textValue())
            .isEqualTo(MAPPING_PROFILE_ACTIVATION_SCHEMA_REF)
        assertStrictObject(responseSchema, MAPPING_PROFILE_RESPONSE_FIELDS)
        assertThat(responseSchema.path("required").map(JsonNode::textValue))
            .containsExactlyInAnyOrderElementsOf(MAPPING_PROFILE_RESPONSE_FIELDS)
        assertThat(responseProperties.fieldNames().asSequence().toList())
            .doesNotContain("definition", "adapterVersion")
    }

    @Test
    fun `traceability ingestion contract is one strict build provenance envelope v2`() {
        val ingestOperation = operation(APPROVED_OPERATIONS.single { it.permission == TRACEABILITY_INGEST_SCOPE })
        val requestBody = contract.path("components").path("requestBodies").path("BuildProvenanceEnvelope")
        val envelopeSchema = contract.path("components").path("schemas").path("BuildProvenanceEnvelope")
        val resultSchema = contract.path("components").path("schemas").path("BuildProvenanceResult")
        val edgeRevisionSchema = contract.path("components").path("schemas").path("EdgeRevisionResult")
        val envelopeProperties = envelopeSchema.path("properties")
        val resultProperties = resultSchema.path("properties")
        val edgeRevisionProperties = edgeRevisionSchema.path("properties")

        assertThat(ingestOperation.path("requestBody").path("\$ref").textValue())
            .isEqualTo(BUILD_PROVENANCE_ENVELOPE_REQUEST_REF)
        assertThat(baselineOperation("post", TRACEABILITY_INGEST_PATH).path("requestBodyRef").textValue())
            .isEqualTo(BUILD_PROVENANCE_ENVELOPE_REQUEST_REF)
        assertThat(requestBody.path("required").isBoolean && requestBody.path("required").booleanValue()).isTrue()
        assertThat(requestBody.path("content").path("application/json").path("schema").path("\$ref").textValue())
            .isEqualTo(BUILD_PROVENANCE_ENVELOPE_SCHEMA_REF)
        assertThat(ingestOperation.path("responses").path("200").path("\$ref").textValue())
            .isEqualTo(BUILD_PROVENANCE_RESULT_RESPONSE_REF)
        assertThat(contract.path("components").path("responses").path("BuildProvenanceResult")
            .path("content").path("application/json").path("schema").path("\$ref").textValue())
            .isEqualTo(BUILD_PROVENANCE_RESULT_SCHEMA_REF)

        assertStrictObject(envelopeSchema, BUILD_PROVENANCE_FIELDS)
        assertThat(envelopeSchema.path("required").map(JsonNode::textValue))
            .containsExactlyInAnyOrderElementsOf(BUILD_PROVENANCE_FIELDS)
        assertThat(envelopeProperties.path("schemaVersion").path("const").intValue()).isEqualTo(2)
        assertThat(envelopeProperties.path("provider").path("enum").map(JsonNode::textValue))
            .containsExactly("GITHUB_ACTIONS")
        assertThat(envelopeProperties.path("sourceRevision").path("pattern").textValue())
            .isEqualTo(LOWERCASE_GIT_SHA_PATTERN)
        assertThat(envelopeProperties.path("proofDigest").path("pattern").textValue())
            .isEqualTo(PREFIXED_LOWERCASE_SHA256_PATTERN)
        assertThat(envelopeProperties.path("sourceIssueIds").path("minItems").intValue()).isEqualTo(1)
        assertThat(envelopeProperties.path("sourceIssueIds").path("maxItems").intValue()).isEqualTo(20)
        assertThat(envelopeProperties.path("sourceIssueIds").path("uniqueItems").booleanValue()).isTrue()
        assertThat(envelopeProperties.path("artifactSha256s").path("minItems").intValue()).isEqualTo(1)
        assertThat(envelopeProperties.path("artifactSha256s").path("maxItems").intValue()).isEqualTo(20)
        assertThat(envelopeProperties.path("artifactSha256s").path("uniqueItems").booleanValue()).isTrue()
        assertThat(envelopeProperties.path("artifactSha256s").path("items").path("pattern").textValue())
            .isEqualTo(LOWERCASE_SHA256_PATTERN)
        assertThat(envelopeProperties.path("buildAttempt").path("minimum").intValue()).isEqualTo(1)

        assertStrictObject(resultSchema, BUILD_PROVENANCE_RESULT_FIELDS)
        assertThat(resultSchema.path("required").map(JsonNode::textValue))
            .containsExactlyInAnyOrderElementsOf(BUILD_PROVENANCE_RESULT_FIELDS)
        assertThat(resultProperties.path("envelopeDigest").path("pattern").textValue())
            .isEqualTo(PREFIXED_LOWERCASE_SHA256_PATTERN)
        assertThat(resultProperties.path("edgeRevisions").path("items").path("\$ref").textValue())
            .isEqualTo(EDGE_REVISION_RESULT_SCHEMA_REF)

        assertStrictObject(edgeRevisionSchema, EDGE_REVISION_RESULT_FIELDS)
        assertThat(edgeRevisionSchema.path("required").map(JsonNode::textValue))
            .containsExactlyInAnyOrderElementsOf(EDGE_REVISION_RESULT_FIELDS)
        assertThat(edgeRevisionProperties.path("edgeType").path("enum").map(JsonNode::textValue))
            .containsExactly("ISSUE_COMMIT", "COMMIT_BUILD", "BUILD_ARTIFACT")
        assertThat(edgeRevisionProperties.path("revision").path("minimum").intValue()).isEqualTo(1)
        assertThat(edgeRevisionProperties.path("factDigest").path("pattern").textValue())
            .isEqualTo(PREFIXED_LOWERCASE_SHA256_PATTERN)
        assertThat(contract.path("components").path("schemas").path("TraceabilityFactInput").isMissingNode).isTrue()
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
        const val ISSUE_CONFIGURE_SCOPE = "issue:configure"
        const val MAPPING_PROFILE_ACTIVATION_PATH =
            "/api/v1/issue-sources/{sourceId}/mapping-profiles:activate"
        const val ACTIVATE_MAPPING_PROFILE_REQUEST_REF =
            "#/components/requestBodies/ActivateIssueMappingProfile"
        const val ACTIVATE_MAPPING_PROFILE_SCHEMA_REF =
            "#/components/schemas/ActivateIssueMappingProfileRequest"
        const val MAPPING_ALIAS_TOKEN_SCHEMA_REF = "#/components/schemas/IssueMappingAliasToken"
        const val IDEMPOTENCY_KEY_PARAMETER_REF = "#/components/parameters/IdempotencyKey"
        const val SOURCE_ID_PARAMETER_REF = "#/components/parameters/SourceId"
        const val MAPPING_PROFILE_ACTIVATED_RESPONSE_REF =
            "#/components/responses/IssueMappingProfileActivated"
        const val MAPPING_PROFILE_ACTIVATION_SCHEMA_REF =
            "#/components/schemas/IssueMappingProfileActivation"
        const val TRACEABILITY_INGEST_PATH = "/api/v1/traceability/facts:ingest"
        const val BUILD_PROVENANCE_ENVELOPE_REQUEST_REF = "#/components/requestBodies/BuildProvenanceEnvelope"
        const val BUILD_PROVENANCE_ENVELOPE_SCHEMA_REF = "#/components/schemas/BuildProvenanceEnvelope"
        const val BUILD_PROVENANCE_RESULT_RESPONSE_REF = "#/components/responses/BuildProvenanceResult"
        const val BUILD_PROVENANCE_RESULT_SCHEMA_REF = "#/components/schemas/BuildProvenanceResult"
        const val EDGE_REVISION_RESULT_SCHEMA_REF = "#/components/schemas/EdgeRevisionResult"
        const val LOWERCASE_SHA256_PATTERN = "^[0-9a-f]{64}$"
        const val PREFIXED_LOWERCASE_SHA256_PATTERN = "^sha256:[0-9a-f]{64}$"
        const val LOWERCASE_GIT_SHA_PATTERN = "^[0-9a-f]{40}$"
        val HTTP_STATUS = Regex("^[1-5][0-9]{2}$")
        val BUILD_PROVENANCE_FIELDS = listOf(
            "schemaVersion",
            "project",
            "releaseIssueSnapshotId",
            "provider",
            "repository",
            "sourceRevision",
            "pipeline",
            "buildId",
            "buildAttempt",
            "workflowReference",
            "proofReference",
            "proofDigest",
            "sourceIssueIds",
            "artifactSha256s",
        )
        val BUILD_PROVENANCE_RESULT_FIELDS = listOf(
            "receiptId",
            "releaseIssueSnapshotId",
            "sourceCommitId",
            "buildRecordId",
            "envelopeDigest",
            "validatorVersion",
            "verificationStatus",
            "confidence",
            "edgeRevisions",
        )
        val EDGE_REVISION_RESULT_FIELDS = listOf(
            "edgeId",
            "edgeType",
            "revisionId",
            "revision",
            "verificationStatus",
            "confidence",
            "factDigest",
        )
        val MAPPING_PROFILE_REQUEST_FIELDS = listOf(
            "schemaVersion",
            "normalizationVersion",
            "unknownStatusPolicy",
            "unknownSeverityPolicy",
            "statusAliases",
            "severityAliases",
        )
        val MAPPING_PROFILE_RESPONSE_FIELDS = listOf(
            "profileId",
            "sourceId",
            "schemaVersion",
            "mappingVersion",
            "activatedAt",
        )
        val repositoryRoot: Path = generateSequence(
            Path.of(M2ApiContractTest::class.java.protectionDomain.codeSource.location.toURI()).toAbsolutePath(),
        ) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve(OPENAPI_PATH)) }
            ?: error("Cannot locate repository root containing $OPENAPI_PATH")
        val APPROVED_OPERATIONS = listOf(
            ApprovedOperation("post", "/api/v1/issue-sources/{sourceId}/sync", "issue:sync", write = true, async = true),
            ApprovedOperation(
                "post",
                "/api/v1/issue-sources/{sourceId}/mapping-profiles:activate",
                "issue:configure",
                write = true,
            ),
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
