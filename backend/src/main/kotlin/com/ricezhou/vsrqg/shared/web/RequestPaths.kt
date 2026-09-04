package com.ricezhou.vsrqg.shared.web

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpMethod
import org.springframework.web.util.UrlPathHelper

object RequestPaths {
    fun isExactPost(request: HttpServletRequest, path: String): Boolean =
        request.method == HttpMethod.POST.name() &&
            UrlPathHelper.defaultInstance.getPathWithinApplication(request) == path
}
