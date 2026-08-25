package com.ricezhou.vsrqg.release

import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.aMapWithSize
import org.hamcrest.Matchers.nullValue
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInfo
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.vsrqg.test",
        "spring.security.oauth2.resourceserver.jwt.audiences[0]=vsrqg-api",
    ],
)
class ReleaseApiContractTest : PostgresIntegrationTest() {
    @MockitoBean
    private lateinit var jwtDecoder: JwtDecoder

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbc: JdbcClient

    private lateinit var buildId: String

    @BeforeEach
    fun setUpAuthorityFixtures(testInfo: TestInfo) {
        buildId = "build-" + testInfo.testMethod.orElseThrow().name.hashCode().toUInt().toString(16)
        jdbc.sql(
            """
            INSERT INTO project(id, project_key, name, created_at)
            VALUES ('project_api', 'vehicle-x', 'Vehicle X', now())
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            INSERT INTO principal(id, issuer, subject, principal_type, disabled, created_at)
            VALUES
              ('principal_release', :issuer, 'release-engineer', 'USER', false, now()),
              ('principal_release_unauthorized', :issuer, 'release-outsider', 'USER', false, now())
            ON CONFLICT (id) DO NOTHING
            """.trimIndent(),
        ).param("issuer", ISSUER).update()
        jdbc.sql(
            """
            INSERT INTO project_assignment(project_id, principal_id, role, created_at)
            VALUES ('project_api', 'principal_release', 'ENGINEER', now())
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).update()
    }

    @Test
    fun `create release returns the frozen identity response and request id`() {
        createRelease("create-api-1", validCreateReleaseJson)
            .andExpect {
                status { isCreated() }
                header { exists("X-Request-Id") }
                jsonPath("$") { value(aMapWithSize<String, Any>(5)) }
                jsonPath("$.releaseId") { value(startsWith("rel_")) }
                jsonPath("$.status") { value("DRAFT") }
                jsonPath("$.manifestId") { value(nullValue()) }
                jsonPath("$.createdAt") { exists() }
                jsonPath("$.version") { value(1) }
                jsonPath("$.project") { doesNotExist() }
            }
    }

    @Test
    fun `same idempotency key and request replays the original response`() {
        val first = createRelease("create-api-replay", validCreateReleaseJson)
            .andExpect { status { isCreated() } }
            .andReturn()
        val replay = createRelease("create-api-replay", validCreateReleaseJson)
            .andExpect { status { isCreated() } }
            .andReturn()

        assertThat(replay.response.contentAsString).isEqualTo(first.response.contentAsString)
    }

    @Test
    fun `equivalent JSON field order has the same request digest`() {
        val reordered =
            """
            {
              "buildId":"$buildId",
              "systemVersion":"2026.08-rc1",
              "platform":"android-automotive",
              "vehicle":"model-a",
              "project":"vehicle-x"
            }
            """.trimIndent()
        val first = createRelease("create-api-canonical", validCreateReleaseJson).andReturn().response
        val replay = createRelease("create-api-canonical", reordered)
            .andExpect { status { isCreated() } }
            .andReturn().response

        assertThat(replay.contentAsString).isEqualTo(first.contentAsString)
    }

    @Test
    fun `duplicate stable build identity returns a release conflict`() {
        createRelease("create-api-identity-1", validCreateReleaseJson)
            .andExpect { status { isCreated() } }

        createRelease("create-api-identity-2", validCreateReleaseJson)
            .andExpect {
                status { isConflict() }
                content { contentType("application/problem+json") }
                jsonPath("$.code") { value("RELEASE_ALREADY_EXISTS") }
            }
    }

    @Test
    fun `reused idempotency key with a different request returns stable problem details`() {
        createRelease("create-api-conflict", validCreateReleaseJson)
            .andExpect { status { isCreated() } }

        createRelease(
            "create-api-conflict",
            validCreateReleaseJson.replace(buildId, "$buildId-different"),
        ).andExpect {
            status { isConflict() }
            content { contentType("application/problem+json") }
            header { exists("X-Request-Id") }
            jsonPath("$.code") { value("IDEMPOTENCY_KEY_REUSED") }
            jsonPath("$.status") { value(409) }
            jsonPath("$.requestId") { exists() }
            jsonPath("$.violations") { isArray() }
        }
    }

    @Test
    fun `unknown write field is rejected with problem details`() {
        createRelease(
            "create-api-unknown",
            validCreateReleaseJson.replace("\"buildId\"", "\"unexpected\":true,\"buildId\""),
        ).andExpect {
            status { isBadRequest() }
            content { contentType("application/problem+json") }
            jsonPath("$.code") { value("INVALID_REQUEST") }
            jsonPath("$.requestId") { exists() }
        }
    }

    @Test
    fun `missing scope returns problem details`() {
        mockMvc.post("/api/v1/releases") {
            with(releaseJwt("release:read"))
            header("Idempotency-Key", "create-api-forbidden")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = validCreateReleaseJson
        }.andExpect {
            status { isForbidden() }
            content { contentType("application/problem+json") }
            jsonPath("$.code") { value("ACCESS_DENIED") }
            jsonPath("$.requestId") { exists() }
        }
    }

    @Test
    fun `unauthenticated request returns problem details`() {
        mockMvc.get("/api/v1/releases/rel_missing")
            .andExpect {
                status { isUnauthorized() }
                content { contentType("application/problem+json") }
                header { exists("X-Request-Id") }
                jsonPath("$.code") { value("AUTHENTICATION_REQUIRED") }
                jsonPath("$.requestId") { exists() }
            }
    }

    @Test
    fun `authorized caller can query the release identity`() {
        val releaseId = createRelease("create-api-get", validCreateReleaseJson)
            .andReturn().response.let { response ->
                Regex("\"releaseId\":\"([^\"]+)\"").find(response.contentAsString)!!.groupValues[1]
            }

        mockMvc.get("/api/v1/releases/{releaseId}", releaseId) {
            with(releaseJwt("release:read"))
        }.andExpect {
            status { isOk() }
            header { exists("X-Request-Id") }
            jsonPath("$.releaseId") { value(releaseId) }
            jsonPath("$.status") { value("DRAFT") }
            jsonPath("$.version") { value(1) }
        }
    }

    @Test
    fun `missing and invisible releases return the same not found problem`() {
        val releaseId = createRelease("create-api-hidden", validCreateReleaseJson)
            .andReturn().response.let { response ->
                Regex("\"releaseId\":\"([^\"]+)\"").find(response.contentAsString)!!.groupValues[1]
            }

        val invisible = mockMvc.get("/api/v1/releases/{releaseId}", releaseId) {
            with(releaseJwt("release:read", "release-outsider"))
        }.andExpect {
            status { isNotFound() }
            content { contentType("application/problem+json") }
            jsonPath("$.code") { value("RELEASE_NOT_FOUND") }
        }.andReturn().response
        val missing = mockMvc.get("/api/v1/releases/{releaseId}", "rel_missing") {
            with(releaseJwt("release:read"))
        }.andExpect {
            status { isNotFound() }
            content { contentType("application/problem+json") }
            jsonPath("$.code") { value("RELEASE_NOT_FOUND") }
        }.andReturn().response

        assertThat(invisible.status).isEqualTo(missing.status)
    }

    private fun createRelease(idempotencyKey: String, body: String) = mockMvc.post("/api/v1/releases") {
        with(releaseJwt("release:create"))
        header("Idempotency-Key", idempotencyKey)
        contentType = org.springframework.http.MediaType.APPLICATION_JSON
        content = body
    }

    private fun releaseJwt(scope: String, subject: String = "release-engineer") = jwt()
        .jwt { token ->
            token.issuer(ISSUER)
            token.subject(subject)
        }
        .authorities(SimpleGrantedAuthority("SCOPE_$scope"))

    private companion object {
        const val ISSUER = "https://idp.vsrqg.test"
    }

    private val validCreateReleaseJson: String
        get() =
            """
            {
              "project":"vehicle-x",
              "vehicle":"model-a",
              "platform":"android-automotive",
              "systemVersion":"2026.08-rc1",
              "buildId":"$buildId"
            }
            """.trimIndent()
}
