package com.ricezhou.vsrqg.testmanagement.application

import com.fasterxml.jackson.databind.JsonNode
import com.ricezhou.vsrqg.shared.application.ResourceConflict
import org.erdtman.jcs.JsonCanonicalizer
import java.security.MessageDigest
import java.util.HexFormat

class TestRunConflict(code:String) : ResourceConflict(code,"Test execution conflict",code)
object TestJson {
    fun canonical(node:JsonNode):ByteArray = JsonCanonicalizer(node.toString()).encodedUTF8
    fun sha256(bytes:ByteArray):String = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    fun digest(node:JsonNode):String = sha256(canonical(node))
}
class SmokeEnvironment(val agentId:String,val deviceId:String,bytes:ByteArray) {
    private val contents = bytes.copyOf()
    val configBytes:ByteArray get()=contents.copyOf()
}
fun interface SmokeEnvironmentSource { fun load():SmokeEnvironment }
