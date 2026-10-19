package com.ricezhou.vsrqg.demo

import java.security.interfaces.RSAPublicKey
import java.time.Duration
import java.time.Instant
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.jwt.JwtClaimValidator
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtIssuerValidator
import org.springframework.security.oauth2.jwt.JwtTimestampValidator
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder

object DemoJwtDecoder {
    fun from(key: RSAPublicKey): JwtDecoder = NimbusJwtDecoder.withPublicKey(key).build().apply {
        setJwtValidator(DelegatingOAuth2TokenValidator(
            JwtTimestampValidator(Duration.ZERO),
            JwtIssuerValidator(M1DemoIdentity.ISSUER),
            JwtClaimValidator<List<String>>("aud") { it == listOf(M1DemoIdentity.AUDIENCE) },
            JwtClaimValidator<Instant>("exp") { it != null },
            JwtClaimValidator<Instant>("nbf") { it != null },
        ))
    }
}
