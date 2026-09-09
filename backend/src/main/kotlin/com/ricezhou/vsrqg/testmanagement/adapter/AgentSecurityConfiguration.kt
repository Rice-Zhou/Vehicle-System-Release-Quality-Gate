package com.ricezhou.vsrqg.testmanagement.adapter

import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.HexFormat
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.userdetails.User
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.preauth.x509.X509PrincipalExtractor

class CertificateFingerprintExtractor : X509PrincipalExtractor {
    override fun extractPrincipal(certificate: X509Certificate): Any {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.encoded))
    }
}

@Configuration
class AgentSecurityConfiguration {
    @Bean
    @Order(1)
    fun agentSecurityFilterChain(http: HttpSecurity, problems: ProblemWriter): SecurityFilterChain {
        http.securityMatcher("/agent-api/**")
            .csrf { it.disable() }
            .requestCache { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { it.anyRequest().authenticated() }
            // The servlet container supplies certificates only after TLS trust validation.
            // Binding and current scope are checked transactionally by AgentAccess for every operation.
            .x509 { it.x509PrincipalExtractor(CertificateFingerprintExtractor()).userDetailsService { fingerprint ->
                User.withUsername(fingerprint).password("").authorities("AGENT_CERTIFICATE").build()
            } }
            .exceptionHandling {
                it.authenticationEntryPoint { request, response, _ ->
                    problems.write(request, response, HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication required", "A trusted client certificate is required")
                }
                it.accessDeniedHandler { request, response, _ ->
                    problems.write(request, response, HttpStatus.FORBIDDEN, "ACCESS_DENIED", "Access denied", "The Agent is not allowed to perform this operation")
                }
            }
        return http.build()
    }
}
