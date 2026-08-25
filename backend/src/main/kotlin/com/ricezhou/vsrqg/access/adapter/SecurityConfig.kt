package com.ricezhou.vsrqg.access.adapter

import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableMethodSecurity
class SecurityConfig {
    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        problemWriter: ProblemWriter,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                it.anyRequest().authenticated()
            }
            .exceptionHandling {
                it.authenticationEntryPoint { request, response, _ ->
                    problemWriter.write(
                        request,
                        response,
                        HttpStatus.UNAUTHORIZED,
                        "AUTHENTICATION_REQUIRED",
                        "Authentication required",
                        "A valid bearer token is required",
                    )
                }
                it.accessDeniedHandler { request, response, _ ->
                    problemWriter.write(
                        request,
                        response,
                        HttpStatus.FORBIDDEN,
                        "ACCESS_DENIED",
                        "Access denied",
                        "The authenticated principal is not allowed to perform this operation",
                    )
                }
            }
            .oauth2ResourceServer {
                it.jwt {}
                it.authenticationEntryPoint { request, response, _ ->
                    problemWriter.write(
                        request,
                        response,
                        HttpStatus.UNAUTHORIZED,
                        "AUTHENTICATION_REQUIRED",
                        "Authentication required",
                        "A valid bearer token is required",
                    )
                }
            }
        return http.build()
    }
}
