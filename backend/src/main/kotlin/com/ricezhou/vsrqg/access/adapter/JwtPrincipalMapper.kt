package com.ricezhou.vsrqg.access.adapter

import com.ricezhou.vsrqg.access.application.AuthenticatedPrincipalResolver
import com.ricezhou.vsrqg.access.domain.Principal
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component

@Component
class JwtPrincipalMapper : AuthenticatedPrincipalResolver {
    fun map(jwt: Jwt): Principal = resolve(
        jwt.issuer?.toString(),
        jwt.subject,
        jwt.getClaimAsString(PRINCIPAL_TYPE_CLAIM),
    )

    override fun resolve(
        issuer: String?,
        subject: String?,
        principalType: String?,
    ): Principal {
        val resolvedIssuer = issuer
            ?: throw BadCredentialsException("JWT issuer is required")
        val resolvedSubject = subject?.takeIf { it.isNotBlank() }
            ?: throw BadCredentialsException("JWT subject is required")
        return Principal(
            issuer = resolvedIssuer,
            subject = resolvedSubject,
            service = principalType == SERVICE_PRINCIPAL_TYPE,
        )
    }

    private companion object {
        const val PRINCIPAL_TYPE_CLAIM = "principal_type"
        const val SERVICE_PRINCIPAL_TYPE = "SERVICE"
    }
}
