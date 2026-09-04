package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.traceability.adapter.TraceabilityVerifyRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TraceabilityVerificationDtoTest {
    private val mapper = ObjectMapper().findAndRegisterModules()

    @Test
    fun `identifier sourceId means issue source and unknown fields fail`() {
        val request = mapper.readValue("""{"sourceId":"jira-main"}""", TraceabilityVerifyRequest::class.java)

        assertThat(request.issueSourceId).isEqualTo("jira-main")
        assertThatThrownBy {
            mapper.readValue(
                """{"sourceId":"jira-main","edgeSource":"latest"}""",
                TraceabilityVerifyRequest::class.java,
            )
        }.hasMessageContaining("INVALID_TRACEABILITY_VERIFY_REQUEST")
    }
}
