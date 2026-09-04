package com.ricezhou.vsrqg.shared.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpMethod
import org.springframework.web.util.UrlPathHelper

object RequestPaths {
    fun isExactPost(request: HttpServletRequest, path: String): Boolean =
        request.method == HttpMethod.POST.name() &&
            pathWithinApplication(request) == path

    fun isPostMatching(request: HttpServletRequest, pathPattern: Regex): Boolean =
        request.method == HttpMethod.POST.name() &&
            pathPattern.matches(pathWithinApplication(request))

    private fun pathWithinApplication(request: HttpServletRequest): String =
        UrlPathHelper.defaultInstance.getPathWithinApplication(request)
}
