package com.ricezhou.vsrqg.shared.web

import com.ricezhou.vsrqg.shared.id.IdGenerator
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter(
    private val idGenerator: IdGenerator,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = idGenerator.nextId("req_")
        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId)
        response.setHeader(REQUEST_ID_HEADER, requestId)
        filterChain.doFilter(request, response)
    }

    companion object {
        const val REQUEST_ID_ATTRIBUTE = "vsrqg.requestId"
        const val REQUEST_ID_HEADER = "X-Request-Id"

        fun from(request: HttpServletRequest): String =
            requireNotNull(request.getAttribute(REQUEST_ID_ATTRIBUTE) as? String) {
                "Request ID filter did not initialize the request"
            }
    }
}
