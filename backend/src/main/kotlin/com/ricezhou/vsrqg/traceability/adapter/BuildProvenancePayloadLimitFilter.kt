package com.ricezhou.vsrqg.traceability.adapter

import com.ricezhou.vsrqg.shared.problem.ProblemWriter
import com.ricezhou.vsrqg.shared.web.RequestPaths
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import kotlin.math.min
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class BuildProvenancePayloadLimitFilter(
    private val properties: BuildProvenanceIngestionProperties,
    private val problemWriter: ProblemWriter,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !RequestPaths.isExactPost(request, INGEST_PATH)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val body = readAtMost(request, properties.maxPayloadBytes + 1)
        if (body.size > properties.maxPayloadBytes) {
            problemWriter.write(
                request = request,
                response = response,
                status = HttpStatus.PAYLOAD_TOO_LARGE,
                code = "PAYLOAD_TOO_LARGE",
                title = "Payload too large",
                detail = "The request body exceeds the configured ingestion limit",
            )
            return
        }
        filterChain.doFilter(ReplayableRequest(request, body), response)
    }

    private fun readAtMost(request: HttpServletRequest, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(min(limit, BUFFER_SIZE))
        val buffer = ByteArray(BUFFER_SIZE)
        val input = request.inputStream
        while (output.size() < limit) {
            val read = input.read(buffer, 0, min(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private class ReplayableRequest(
        request: HttpServletRequest,
        private val body: ByteArray,
    ) : HttpServletRequestWrapper(request) {
        override fun getContentLength(): Int = body.size

        override fun getContentLengthLong(): Long = body.size.toLong()

        override fun getInputStream(): ServletInputStream = ReplayableServletInputStream(body)

        override fun getReader(): BufferedReader = BufferedReader(
            InputStreamReader(
                inputStream,
                characterEncoding?.let(Charset::forName) ?: StandardCharsets.UTF_8,
            ),
        )
    }

    private class ReplayableServletInputStream(body: ByteArray) : ServletInputStream() {
        private val input = ByteArrayInputStream(body)

        override fun read(): Int = input.read()

        override fun read(target: ByteArray, offset: Int, length: Int): Int =
            input.read(target, offset, length)

        override fun isFinished(): Boolean = input.available() == 0

        override fun isReady(): Boolean = true

        override fun setReadListener(readListener: ReadListener) {
            throw UnsupportedOperationException("ASYNC_REQUEST_BODY_READ_NOT_SUPPORTED")
        }
    }

    private companion object {
        const val INGEST_PATH = "/api/v1/traceability/facts:ingest"
        const val BUFFER_SIZE = 8 * 1024
    }
}
