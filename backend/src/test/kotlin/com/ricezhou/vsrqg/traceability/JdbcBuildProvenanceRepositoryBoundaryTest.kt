package com.ricezhou.vsrqg.traceability

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.application.SafeValidationDiagnostic
import com.ricezhou.vsrqg.shared.id.IdGenerator
import com.ricezhou.vsrqg.traceability.adapter.JdbcBuildProvenanceRepository
import com.ricezhou.vsrqg.traceability.application.BuildProvenanceInvalid
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.jdbc.core.simple.JdbcClient

class JdbcBuildProvenanceRepositoryBoundaryTest {
    @Test
    fun `repository rejects duplicate requested checksum without returning endpoints`() {
        val repository = JdbcBuildProvenanceRepository(
            jdbc = mock(JdbcClient::class.java),
            objectMapper = ObjectMapper(),
            idGenerator = IdGenerator { error("ID generation must not be reached") },
        )

        assertThatThrownBy {
            repository.resolveArtifacts("project", listOf(DIGEST_A, DIGEST_A))
        }.isInstanceOfSatisfying(BuildProvenanceInvalid::class.java) {
            assertThat(it.diagnostic).isEqualTo(SafeValidationDiagnostic.BUILD_PROVENANCE_INVALID)
            assertThat(it.violationCodes).containsExactly("ARTIFACT_SHA256_DUPLICATE")
        }
    }

    private companion object {
        const val DIGEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
