package com.ricezhou.vsrqg.testmanagement

import com.ricezhou.vsrqg.testmanagement.domain.AttemptIds
import com.ricezhou.vsrqg.testmanagement.domain.LeaseWindow
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.time.Instant

@Timeout(60)
class LeaseWindowTest {
    @Test fun `lease expiry is an exclusive boundary and rejects old generations and terminal attempts`() {
        val expiry=Instant.parse("2026-09-09T00:01:30Z")
        assertThat(LeaseWindow.writable(expiry,expiry,4,4,false)).isFalse()
        assertThat(LeaseWindow.writable(expiry.minusNanos(1),expiry,4,4,false)).isTrue()
        assertThat(LeaseWindow.writable(expiry.minusSeconds(1),expiry,3,4,false)).isFalse()
        assertThat(LeaseWindow.writable(expiry.minusSeconds(1),expiry,4,4,true)).isFalse()
    }
    @Test fun `generated attempt identity is stored as one standard UUID`() {
        assertThat(AttemptIds.fromGenerated("att_01992560aaab70008000123456789abc"))
            .isEqualTo("01992560-aaab-7000-8000-123456789abc")
    }
    @Test fun `invalid generated values cannot become an Attempt identity`() {
        for(value in listOf("01992560-aaab-7000-8000-123456789abc","att_SHORT","att_"+"G".repeat(32),"run_"+"a".repeat(32))) {
            assertThatThrownBy { AttemptIds.fromGenerated(value) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
