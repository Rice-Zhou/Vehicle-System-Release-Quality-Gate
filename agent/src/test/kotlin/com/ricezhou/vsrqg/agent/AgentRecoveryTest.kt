package com.ricezhou.vsrqg.agent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class AgentRecoveryTest {
    @Test fun `uncertain installation is never replayed`() {
        assertEquals(RecoveryAction.WAIT_FOR_DEADLINE, RecoveryPolicy.decide(Phase.INSTALL_INTENT, true, true))
        assertEquals(RecoveryAction.WAIT_FOR_DEADLINE, RecoveryPolicy.decide(Phase.LAUNCH_INTENT, true, true))
        assertEquals(RecoveryAction.REPORT_ONLY, RecoveryPolicy.decide(Phase.UPLOADED, true, true))
        assertEquals(RecoveryAction.REPORT_ONLY, RecoveryPolicy.decide(Phase.OBSERVED, true, true))
        assertEquals(RecoveryAction.DIAGNOSTICS_ONLY, RecoveryPolicy.decide(Phase.UPLOADED, false, true))
    }
    @Test fun `only untouched actions can start under same boot and valid lease`() {
        listOf(Phase.RECEIVED, Phase.ACKED, Phase.INSTALLED).forEach { assertEquals(RecoveryAction.START, RecoveryPolicy.decide(it, true, true)) }
        Phase.entries.filter { it != Phase.RESULT_ACKED }.forEach {
            assertEquals(RecoveryAction.DIAGNOSTICS_ONLY, RecoveryPolicy.decide(it, false, true))
            assertEquals(RecoveryAction.DIAGNOSTICS_ONLY, RecoveryPolicy.decide(it, true, false))
        }
        assertEquals(RecoveryAction.DONE, RecoveryPolicy.decide(Phase.RESULT_ACKED, false, false))
    }
}
