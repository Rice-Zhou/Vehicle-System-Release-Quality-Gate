package com.ricezhou.vsrqg.access.adapter

import com.ricezhou.vsrqg.access.domain.Principal
import org.springframework.security.authentication.BadCredentialsException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component

@Component
class JwtPrincipalMapper {
    fun map(jwt: Jwt): Principal {
        val issuer = jwt.issuer?.toString()
            ?: throw BadCredentialsException("JWT issuer is required")
        val subject = jwt.subject?.takeIf { it.isNotBlank() }
            ?: throw BadCredentialsException("JWT subject is required")
        return Principal(
            issuer = issuer,
            subject = subject,
            service = jwt.getClaimAsString(PRINCIPAL_TYPE_CLAIM) == SERVICE_PRINCIPAL_TYPE,
        )
    }

    private companion object {
        const val PRINCIPAL_TYPE_CLAIM = "principal_type"
        const val SERVICE_PRINCIPAL_TYPE = "SERVICE"
    }
}
