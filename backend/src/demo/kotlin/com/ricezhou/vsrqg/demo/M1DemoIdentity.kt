package com.ricezhou.vsrqg.demo

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.time.Instant
import java.util.Date
import org.springframework.security.oauth2.jwt.JwtDecoder

// Deliberately has no Spring stereotype: only the explicit launcher installs this identity.
class M1DemoIdentity {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    val decoder: JwtDecoder = DemoJwtDecoder.from(keys.public as RSAPublicKey)

    fun token(
        subject: String,
        issuedAt: Instant = Instant.now(),
        issuer: String = ISSUER,
        audience: String = AUDIENCE,
        notBefore: Instant = issuedAt,
        scopes: String = "release:create release:read manifest:write manifest:lock",
        principalType: String? = null,
        projectReference: String? = null,
    ): String {
        val claims = JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(audience)
            .issueTime(Date.from(issuedAt)).notBeforeTime(Date.from(notBefore))
            .expirationTime(Date.from(issuedAt.plusSeconds(600)))
            .claim("scope", scopes)
            .apply {
                principalType?.let { claim("principal_type", it) }
                projectReference?.let { claim("project", it) }
            }.build()
        return SignedJWT(JWSHeader(JWSAlgorithm.RS256), claims).apply {
            sign(RSASSASigner(keys.private))
        }.serialize()
    }

    companion object {
        const val ISSUER = "http://localhost/vsrqg-demo"
        const val AUDIENCE = "vsrqg-m1-demo"
    }
}
