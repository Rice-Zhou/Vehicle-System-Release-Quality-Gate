package com.ricezhou.vsrqg.access

import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.ImmutableJWKSet
import com.ricezhou.vsrqg.access.adapter.JwtPrincipalMapper
import com.ricezhou.vsrqg.access.application.ProjectAuthorizer
import com.ricezhou.vsrqg.access.domain.Permission
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtAudienceValidator
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.time.Instant

@AutoConfigureMockMvc
@Import(SecurityTestConfiguration::class)
@ActiveProfiles("security-acceptance-test")
@TestPropertySource(
    properties = [
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://idp.vsrqg.test",
        "spring.security.oauth2.resourceserver.jwt.audiences[0]=vsrqg-api",
    ],
)
class SecurityAcceptanceTest : PostgresIntegrationTest() {
    @Autowired
    private lateinit var jdbc: JdbcClient

    @Autowired
    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun prepareAssignments() {
        jdbc.sql(
            """
            INSERT INTO project(id, project_key, name, created_at)
            VALUES
              ('project_a', 'vehicle-a', 'Vehicle A', now()),
              ('project_b', 'vehicle-b', 'Vehicle B', now())
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).update()
        jdbc.sql(
            """
            INSERT INTO principal(id, issuer, subject, principal_type, disabled, created_at)
            VALUES
              ('principal_a', :issuer, 'user-a', 'USER', false, now()),
              ('principal_disabled', :issuer, 'user-disabled', 'USER', true, now())
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).param("issuer", ISSUER).update()
        jdbc.sql(
            """
            INSERT INTO project_assignment(project_id, principal_id, role, created_at)
            VALUES
              ('project_a', 'principal_a', 'VIEWER', now()),
              ('project_a', 'principal_disabled', 'ADMINISTRATOR', now())
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        ).update()
    }

    @Test
    fun `unauthenticated request is 401`() {
        mockMvc.get("/test-support/projects/project_a")
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `authorized project principal can read`() {
        getProject("project_a", token())
            .andExpect {
                status { isNoContent() }
            }
    }

    @Test
    fun `cross project principal is 403`() {
        getProject("project_b", token())
            .andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `missing permission scope is 403`() {
        getProject("project_a", token(scope = "manifest:write"))
            .andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `issue snapshot endpoint rejects a token without its dedicated scope`() {
        mockMvc.post("/api/v1/releases/release_hidden/issue-snapshots") {
            header("Authorization", "Bearer ${token(scope = Permission.RELEASE_READ.scope)}")
            header("Idempotency-Key", "snapshot-security-key")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"sourceId":"source_hidden"}"""
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `issue snapshot endpoint hides an unknown release and source as 404`() {
        mockMvc.post("/api/v1/releases/release_hidden/issue-snapshots") {
            header("Authorization", "Bearer ${token(scope = Permission.ISSUE_SNAPSHOT.scope)}")
            header("Idempotency-Key", "snapshot-hidden-key")
            contentType = org.springframework.http.MediaType.APPLICATION_JSON
            content = """{"sourceId":"source_hidden"}"""
        }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `disabled principal is 403`() {
        getProject("project_a", token(subject = "user-disabled"))
            .andExpect {
                status { isForbidden() }
            }
    }

    @Test
    fun `wrong issuer is 401`() {
        getProject("project_a", token(issuer = "https://untrusted-idp.test"))
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `wrong audience is 401`() {
        getProject("project_a", token(audience = "different-api"))
            .andExpect {
                status { isUnauthorized() }
            }
    }

    @Test
    fun `expired token is 401`() {
        val expiredAt = Instant.now().minusSeconds(300)
        getProject(
            "project_a",
            token(issuedAt = expiredAt.minusSeconds(300), expiresAt = expiredAt),
        ).andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `invalid signature is 401`() {
        getProject("project_a", token(signingKey = UNTRUSTED_KEY))
            .andExpect {
                status { isUnauthorized() }
            }
    }

    private fun getProject(
        projectId: String,
        token: String,
    ) = mockMvc.get("/test-support/projects/{projectId}", projectId) {
        header("Authorization", "Bearer $token")
    }

    private fun token(
        issuer: String = ISSUER,
        subject: String = "user-a",
        audience: String = AUDIENCE,
        scope: String = Permission.RELEASE_READ.scope,
        issuedAt: Instant = Instant.now().minusSeconds(5),
        expiresAt: Instant = Instant.now().plusSeconds(300),
        signingKey: KeyPair = TRUSTED_KEY,
    ): String {
        val rsaKey = RSAKey.Builder(signingKey.public as RSAPublicKey)
            .privateKey(signingKey.private as RSAPrivateKey)
            .keyID(KEY_ID)
            .build()
        val encoder = NimbusJwtEncoder(ImmutableJWKSet(JWKSet(rsaKey)))
        val headers = org.springframework.security.oauth2.jwt.JwsHeader
            .with(SignatureAlgorithm.RS256)
            .keyId(KEY_ID)
            .build()
        val claims = JwtClaimsSet.builder()
            .issuer(issuer)
            .subject(subject)
            .audience(listOf(audience))
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .claim("scope", scope)
            .claim("principal_type", "USER")
            .build()
        return encoder.encode(JwtEncoderParameters.from(headers, claims)).tokenValue
    }

    companion object {
        const val ISSUER = "https://idp.vsrqg.test"
        const val AUDIENCE = "vsrqg-api"
        const val KEY_ID = "vsrqg-test-key"
        val TRUSTED_KEY: KeyPair = generateKeyPair()
        val UNTRUSTED_KEY: KeyPair = generateKeyPair()

        private fun generateKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()
    }
}

@TestConfiguration(proxyBeanMethods = false)
class SecurityTestConfiguration {
    @Bean
    fun jwtDecoder(): JwtDecoder {
        val decoder = NimbusJwtDecoder
            .withPublicKey(SecurityAcceptanceTest.TRUSTED_KEY.public as RSAPublicKey)
            .build()
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(SecurityAcceptanceTest.ISSUER),
                JwtAudienceValidator(SecurityAcceptanceTest.AUDIENCE),
            ),
        )
        return decoder
    }
}

@RestController
@Profile("security-acceptance-test")
class SecurityProbeController(
    private val projectAuthorizer: ProjectAuthorizer,
    private val principalMapper: JwtPrincipalMapper,
) {
    @GetMapping("/test-support/projects/{projectId}")
    @PreAuthorize("hasAuthority('SCOPE_release:read')")
    fun readProject(
        @AuthenticationPrincipal jwt: Jwt,
        @PathVariable projectId: String,
    ): ResponseEntity<Void> {
        projectAuthorizer.require(
            principalMapper.map(jwt),
            projectId,
            Permission.RELEASE_READ,
        )
        return ResponseEntity.noContent().build()
    }
}
