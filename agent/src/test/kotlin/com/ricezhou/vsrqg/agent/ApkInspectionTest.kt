package com.ricezhou.vsrqg.agent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
class ApkInspectionTest {
    @Test fun `badging fixes package and version and signer must be unique`() {
        val badging="package: name='com.ricezhou.vsrqg.smoke' versionCode='1' versionName='1.0'"
        val cert="ab".repeat(32)
        val identity=ApkInspector.parse(badging,"Signer #1 certificate SHA-256 digest: $cert")
        assertEquals("sha256:$cert",identity.signer)
        assertEquals(1,identity.version)
        listOf("Signer #1 certificate SHA-256 digest: $cert\nSigner #2 certificate SHA-256 digest: $cert", "unrecognized output", "Signer #1 certificate SHA-256 digest: none").forEach { signer -> assertThrows(AgentFailure::class.java) {ApkInspector.parse(badging,signer)} }
        assertThrows(AgentFailure::class.java) {ApkInspector.parse(badging.replace("versionCode='1'","versionCode='2'"),"Signer #1 certificate SHA-256 digest: $cert")}
        assertThrows(AgentFailure::class.java) {ApkInspector.parse(badging.replace("com.ricezhou.vsrqg.smoke","other.app"),"Signer #1 certificate SHA-256 digest: $cert")}
    }
    @Test fun `base APK path requires exactly one safe readable base`() {
        assertEquals("/data/app/~~token/pkg-token/base.apk",AndroidSmokeDevice.basePath("package:/data/app/~~token/pkg-token/base.apk\n"))
        listOf("package:/data/app/pkg/base.apk\npackage:/data/app/pkg/split.apk", "package:/data/app/pkg/../base.apk", "package:/data/app/pkg/base.apk;reboot", "garbage").forEach { assertThrows(AgentFailure::class.java) {AndroidSmokeDevice.basePath(it)} }
    }
}
