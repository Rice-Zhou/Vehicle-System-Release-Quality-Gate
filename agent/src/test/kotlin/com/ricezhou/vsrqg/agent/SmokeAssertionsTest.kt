package com.ricezhou.vsrqg.agent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class SmokeAssertionsTest {
    private val id="01992560-aaab-7000-8000-123456789abc"
    private fun xml(text:String)="<hierarchy><node package='com.ricezhou.vsrqg.smoke' text='$text'/></hierarchy>".toByteArray()
    @Test fun `current READY line inside actual APK multiline label passes`() {
        assertTrue(SmokeAssertions.ready(xml("SYNTHETIC_DEMO&#10;VSRQG_SMOKE_READY:$id"),id))
    }
    @Test fun `stale substring or negative marker never pass`() {
        listOf("VSRQG_SMOKE_NOT_READY:$id", "prefixVSRQG_SMOKE_READY:$id", "VSRQG_SMOKE_READY:01992560-aaab-7000-8000-123456789abd").forEach { assertFalse(SmokeAssertions.ready(xml(it),id)) }
        assertFalse(SmokeAssertions.ready(xml("VSRQG_SMOKE_READY:$id").toString(Charsets.UTF_8).replace("com.ricezhou.vsrqg.smoke", "other.package").toByteArray(),id))
    }
    @Test fun `XXE malformed and oversized XML fail visibly`() {
        assertThrows(AgentFailure::class.java) { SmokeAssertions.ready("<!DOCTYPE x [<!ENTITY e SYSTEM 'file:///missing'>]><hierarchy>&e;</hierarchy>".toByteArray(),id) }
        assertThrows(AgentFailure::class.java) { SmokeAssertions.ready(ByteArray(1048577),id) }
        assertThrows(AgentFailure::class.java) { SmokeAssertions.ready("<bad>".toByteArray(),id) }
    }
    @Test fun `shell metacharacters and arbitrary mode are rejected`() {
        listOf("$id;reboot", "../x", "", "x\n").forEach { assertThrows(AgentFailure::class.java) { SmokeAssertions.attempt(it) } }
        assertThrows(AgentFailure::class.java) { SmokeAssertions.mode("normal;id") }
    }
    @Test fun `foreground and launch require exact component and successful launch`() {
        assertTrue(SmokeAssertions.foreground("mResumedActivity: ActivityRecord{abc u0 com.ricezhou.vsrqg.smoke/.SmokeActivity t3}"))
        assertFalse(SmokeAssertions.foreground("mResumedActivity: ActivityRecord{abc u0 other/.SmokeActivity t3} com.ricezhou.vsrqg.smoke/.SmokeActivity"))
        assertTrue(SmokeAssertions.launch("Status: ok\nActivity: com.ricezhou.vsrqg.smoke/.SmokeActivity\nComplete"))
        assertFalse(SmokeAssertions.launch("Error: Activity not started\nStatus: ok"))
    }
    @Test fun `foreground accepts actual top resumed equals record`() {
        assertTrue(SmokeAssertions.foreground(" topResumedActivity=ActivityRecord{24c3e6d u0 com.ricezhou.vsrqg.smoke/.SmokeActivity t2467}"))
    }
    @Test fun `foreground accepts actual resumed activity colon record`() {
        assertTrue(SmokeAssertions.foreground(" ResumedActivity: ActivityRecord{24c3e6d u0 com.ricezhou.vsrqg.smoke/.SmokeActivity t2467}"))
    }
    @Test fun `foreground keeps both existing colon formats`() {
        assertTrue(SmokeAssertions.foreground("mResumedActivity: ActivityRecord{abc u0 com.ricezhou.vsrqg.smoke/.SmokeActivity t3}"))
        assertTrue(SmokeAssertions.foreground("  topResumedActivity: ActivityRecord{abc u10 com.ricezhou.vsrqg.smoke/.SmokeActivity t30}"))
        assertTrue(SmokeAssertions.foreground("mResumedActivity: ActivityRecord{abc u0 com.ricezhou.vsrqg.smoke/.SmokeActivity t3 f}"))
    }
    @Test fun `foreground rejects inactive records embedded labels and incomplete identities`() {
        listOf(
            "",
            "topResumedActivity=null",
            "ResumedActivity: null",
            "mPausedActivity: ActivityRecord{abc u0 com.ricezhou.vsrqg.smoke/.SmokeActivity t3}",
            "Hist #0: ActivityRecord{abc u0 com.ricezhou.vsrqg.smoke/.SmokeActivity t3}",
            "previous topResumedActivity=ActivityRecord{abc u0 com.ricezhou.vsrqg.smoke/.SmokeActivity t3}",
            "lastResumedActivity: ActivityRecord{abc u0 com.ricezhou.vsrqg.smoke/.SmokeActivity t3}",
            "ResumedActivity: ActivityRecord{abc u0 com.ricezhou.vsrqg.smoke.extra/.SmokeActivity t3}",
            "ResumedActivity: ActivityRecord{abc u0 com.ricezhou.vsrqg.smoke/.SmokeActivityExtra t3}",
            "topResumedActivity=ActivityRecord{abc u0x com.ricezhou.vsrqg.smoke/.SmokeActivity t3}",
            "topResumedActivity=ActivityRecord{abc u0 com.ricezhou.vsrqg.smoke/.SmokeActivity t3x}",
            "mResumedActivity: ActivityRecord{abc u0 com.ricezhou.vsrqg.smoke/.SmokeActivity t3x}",
            "mResumedActivity: ActivityRecord{abc u0 com.ricezhou.vsrqg.smoke/.SmokeActivity}",
            "ResumedActivity: ActivityRecord{abc u0 other/.SmokeActivity t3} com.ricezhou.vsrqg.smoke/.SmokeActivity",
            "topResumedActivity=ActivityRecord{abc u0 com.ricezhou.vsrqg.smoke/.SmokeActivity t3",
        ).forEach { assertFalse(SmokeAssertions.foreground(it), it) }
    }
}
