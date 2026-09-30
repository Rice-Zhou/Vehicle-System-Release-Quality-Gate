package com.ricezhou.vsrqg.quality

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.yaml.snakeyaml.Yaml

@Timeout(60)
@AutoConfigureMockMvc
class RulePublicationIntegrationTest : PostgresIntegrationTest() {
    @MockitoBean private lateinit var jwtDecoder: JwtDecoder
    @Autowired private lateinit var jdbc: JdbcClient
    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var mapper: ObjectMapper

    private lateinit var projectId: String
    private lateinit var ruleSetId: String
    private lateinit var author: String
    private lateinit var reviewer: String

    @BeforeEach
    fun seed() {
        val suffix = UUID.randomUUID().toString().replace("-", "").take(16)
        projectId = "prj_$suffix"
        ruleSetId = "set_$suffix"
        author = "author_$suffix"
        reviewer = "reviewer_$suffix"
        jdbc.sql("INSERT INTO project(id,project_key,name,created_at) VALUES (:id,:key,'Quality fixture',:at)")
            .param("id", projectId).param("key", projectId).param("at", java.sql.Timestamp.from(Instant.now())).update()
        listOf(author to "QUALITY_OWNER", reviewer to "ADMINISTRATOR").forEach { (subject, role) ->
            val principalId = "usr_${subject.first()}_${subject.takeLast(16)}"
            jdbc.sql("INSERT INTO principal(id,issuer,subject,principal_type,created_at) VALUES (:id,:issuer,:subject,'USER',:at)")
                .param("id", principalId).param("issuer", "https://idp.vsrqg.test")
                .param("subject", subject).param("at", java.sql.Timestamp.from(Instant.now())).update()
            jdbc.sql("INSERT INTO project_assignment(project_id,principal_id,role,created_at) VALUES (:project,:principal,:role,:at)")
                .param("project", projectId).param("principal", principalId).param("role", role)
                .param("at", java.sql.Timestamp.from(Instant.now())).update()
        }
    }

    @Test
    fun `create replays same body and rejects different body with same key`() {
        val body = request()
        val first = create(body, "create-1", author).andExpect {
            status { isCreated() }
            header { string("ETag", "\"0\"") }
        }.andReturn().response.contentAsString
        val second = create(body, "create-1", author).andExpect { status { isCreated() } }.andReturn().response.contentAsString
        assertThat(second).isEqualTo(first)
        val changed = body.deepCopy<JsonNode>() as com.fasterxml.jackson.databind.node.ObjectNode
        changed.put("version", 2)
        create(changed, "create-1", author).andExpect { status { isConflict() } }
        assertThat(count("quality_rule_set_versions")).isEqualTo(1)
        assertThat(count("audit_event")).isEqualTo(1)
    }

    @Test
    fun `create rejects fractional catalog version and trailing JSON`() {
        val fractional = request().deepCopy<JsonNode>() as com.fasterxml.jackson.databind.node.ObjectNode
        fractional.put("catalogVersion", 2.5)
        create(fractional, "fractional-catalog", author).andExpect { status { isUnprocessableEntity() } }
        mockMvc.post("/api/v1/rule-sets") {
            with(identity(author, "rule:write"))
            header("Idempotency-Key", "trailing-json")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(request()) + " {}"
        }.andExpect { status { isUnprocessableEntity() } }
        assertThat(count("quality_rule_set_versions")).isZero()
    }

    @Test
    fun `create rejects a rule version outside database range`() {
        val oversized = request().deepCopy<JsonNode>() as com.fasterxml.jackson.databind.node.ObjectNode
        val rule = oversized["rules"][0] as com.fasterxml.jackson.databind.node.ObjectNode
        rule.put("version", java.math.BigInteger("9223372036854775808"))
        create(oversized, "oversized-rule-version", author).andExpect { status { isUnprocessableEntity() } }
        assertThat(count("quality_rule_set_versions")).isZero()
    }

    @Test
    fun `publication requires another authorized reviewer and matching row version`() {
        create(request(), "create-2", author).andExpect { status { isCreated() } }
        publish("pub-self", author, "0").andExpect { status { isForbidden() } }
        publish("pub-old", reviewer, "1").andExpect { status { isConflict() } }
        publish("pub-good", reviewer, "0").andExpect {
            status { isOk() }
            header { string("ETag", "\"1\"") }
            jsonPath("$.state") { value("PUBLISHED") }
        }
        publish("pub-good", reviewer, "0").andExpect { status { isOk() } }
        assertThat(count("audit_event")).isEqualTo(2)
        assertThat(jdbc.sql("SELECT reviewer_id IS NOT NULL FROM quality_rule_set_versions WHERE rule_set_id=:id")
            .param("id", ruleSetId).query(Boolean::class.java).single()).isTrue()
    }

    @Test
    fun `project isolation and missing scope reject before writing`() {
        create(request(), "scope-1", author, "rule:publish").andExpect { status { isForbidden() } }
        create(request(), "scope-2", reviewer, "rule:write").andExpect { status { isCreated() } }
        val other = "other_${UUID.randomUUID().toString().take(8)}"
        val otherProject = "other_${UUID.randomUUID().toString().take(8)}"
        val otherPrincipal = "usr_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        jdbc.sql("INSERT INTO project(id,project_key,name,created_at) VALUES (:id,:key,'Other project',:at)")
            .param("id", otherProject).param("key", otherProject).param("at", java.sql.Timestamp.from(Instant.now())).update()
        jdbc.sql("INSERT INTO principal(id,issuer,subject,principal_type,created_at) VALUES (:id,:issuer,:subject,'USER',:at)")
            .param("id", otherPrincipal).param("issuer", "https://idp.vsrqg.test")
            .param("subject", other).param("at", java.sql.Timestamp.from(Instant.now())).update()
        jdbc.sql("INSERT INTO project_assignment(project_id,principal_id,role,created_at) VALUES (:project,:principal,'QUALITY_OWNER',:at)")
            .param("project", otherProject).param("principal", otherPrincipal)
            .param("at", java.sql.Timestamp.from(Instant.now())).update()
        publish("scope-3", other, "0").andExpect { status { isForbidden() } }
        assertThat(count("audit_event")).isEqualTo(1)
    }

    @Test
    fun `audit database failure rolls back publication and idempotency record`() {
        create(request(), "rollback-create", author).andExpect { status { isCreated() } }
        jdbc.sql("""
            CREATE FUNCTION reject_quality_fixture_audit() RETURNS trigger LANGUAGE plpgsql AS ${'$'}${'$'}
            BEGIN
              IF NEW.action='QUALITY_RULE_SET_PUBLISHED' THEN
                RAISE EXCEPTION 'fixture audit failure';
              END IF;
              RETURN NEW;
            END ${'$'}${'$'}
        """.trimIndent()).update()
        jdbc.sql("CREATE TRIGGER reject_quality_fixture_audit BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE FUNCTION reject_quality_fixture_audit()").update()
        try {
            publish("rollback-publish", reviewer, "0").andExpect { status { is5xxServerError() } }
            assertThat(jdbc.sql("SELECT state FROM quality_rule_set_versions WHERE rule_set_id=:id")
                .param("id", ruleSetId).query(String::class.java).single()).isEqualTo("DRAFT")
            assertThat(count("audit_event")).isEqualTo(1)
            assertThat(jdbc.sql("SELECT count(*) FROM idempotency_record WHERE idempotency_key='rollback-publish'")
                .query(Int::class.java).single()).isZero()
        } finally {
            jdbc.sql("DROP TRIGGER reject_quality_fixture_audit ON audit_event").update()
            jdbc.sql("DROP FUNCTION reject_quality_fixture_audit()").update()
        }
    }

    @Test
    fun `later version can be drafted after prior publication without altering history`() {
        create(request(), "v1-create", author).andExpect { status { isCreated() } }
        publish("v1-publish", reviewer, "0").andExpect { status { isOk() } }
        val next = request().deepCopy<JsonNode>() as com.fasterxml.jackson.databind.node.ObjectNode
        next.put("version", 2)
        create(next, "v2-create", author).andExpect { status { isCreated() } }
        assertThat(jdbc.sql("SELECT state FROM quality_rule_set_versions WHERE rule_set_id=:id ORDER BY rule_set_version")
            .param("id", ruleSetId).query(String::class.java).list()).containsExactly("PUBLISHED", "DRAFT")
    }

    @Test
    fun `published row rejects update and delete at database boundary`() {
        create(request(), "immutable-create", author).andExpect { status { isCreated() } }
        publish("immutable-publish", reviewer, "0").andExpect { status { isOk() } }
        org.assertj.core.api.Assertions.assertThatThrownBy {
            jdbc.sql("UPDATE quality_rule_set_versions SET content_digest='changed' WHERE state='PUBLISHED'").update()
        }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
        org.assertj.core.api.Assertions.assertThatThrownBy {
            jdbc.sql("DELETE FROM quality_rule_set_versions WHERE rule_set_id=:id")
                .param("id", ruleSetId).update()
        }.isInstanceOf(org.springframework.dao.DataAccessException::class.java)
    }

    @Test
    fun `unsupported rule cannot publish and failed transaction leaves no publication`() {
        val changed = request().deepCopy<JsonNode>() as com.fasterxml.jackson.databind.node.ObjectNode
        (changed["rules"] as com.fasterxml.jackson.databind.node.ArrayNode).remove(1)
        create(changed, "unsupported-create", author).andExpect { status { isCreated() } }
        publish("unsupported-publish", reviewer, "0").andExpect { status { isUnprocessableEntity() } }
        assertThat(jdbc.sql("SELECT state FROM quality_rule_set_versions WHERE rule_set_id=:id")
            .param("id", ruleSetId).query(String::class.java).single()).isEqualTo("DRAFT")
        assertThat(count("audit_event")).isEqualTo(1)
    }

    private fun request(): JsonNode {
        val exampleDir = Path.of("../contracts/examples/v0.2/quality-rule")
        val rules = listOf("smoke-case-outcome.yaml", "required-issue-verified.yaml").map { file ->
            mapper.valueToTree<JsonNode>(Yaml().load<Any>(Files.readString(exampleDir.resolve(file))))
        }
        val body = mapper.createObjectNode().put("ruleSetId", ruleSetId).put("version", 1)
            .put("project", projectId).put("catalogVersion", 2)
            .put("engineVersion", "VSRQG-QUALITY-ENGINE-1")
        body.set<JsonNode>("requiredIssueRefs", mapper.createArrayNode())
        body.set<JsonNode>("selectedCaseRefs", mapper.createArrayNode().add(mapper.createObjectNode().put("caseId", "SMOKE_CASE").put("version", 1)))
        body.set<JsonNode>("rules", mapper.valueToTree(rules))
        return body
    }

    private fun create(body: JsonNode, key: String, subject: String, scope: String = "rule:write") =
        mockMvc.post("/api/v1/rule-sets") {
            with(identity(subject, scope))
            header("Idempotency-Key", key)
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(body)
        }

    private fun publish(key: String, subject: String, version: String) =
        mockMvc.post("/api/v1/rule-sets/$ruleSetId:publish") {
            with(identity(subject, "rule:publish"))
            header("Idempotency-Key", key)
            header("If-Match", version)
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"reason":"fixture review"}"""
        }

    private fun identity(subject: String, scope: String) = jwt().jwt { builder ->
        builder.issuer("https://idp.vsrqg.test").subject(subject).claim("principal_type", "USER")
    }.authorities(SimpleGrantedAuthority("SCOPE_$scope"))

    private fun count(table: String) = jdbc.sql("SELECT count(*) FROM $table WHERE project_id=:project")
        .param("project", projectId).query(Int::class.java).single()
}
