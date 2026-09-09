package com.ricezhou.vsrqg.testmanagement.adapter

import com.ricezhou.vsrqg.testmanagement.application.SmokeEnvironment
import com.ricezhou.vsrqg.testmanagement.application.SmokeEnvironmentSource
import com.ricezhou.vsrqg.testmanagement.application.TestRunConflict
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Base64

@Component
class ConfiguredSmokeEnvironment(
    @param:Value("\${vsrqg.demo.smoke.agent-id:}") private val agentId:String,
    @param:Value("\${vsrqg.demo.smoke.device-id:}") private val deviceId:String,
    @param:Value("\${vsrqg.demo.smoke.environment-config-base64:}") private val encoded:String,
):SmokeEnvironmentSource {
    override fun load():SmokeEnvironment {
        if(agentId.isBlank() || agentId.length>40 || deviceId.isBlank() || deviceId.length>128 ||
            encoded.isEmpty() || encoded.length>87384) throw TestRunConflict("SMOKE_ENVIRONMENT_UNCONFIGURED")
        val bytes=try { Base64.getDecoder().decode(encoded) } catch(_:IllegalArgumentException) {
            throw TestRunConflict("ENVIRONMENT_CONFIG_INVALID")
        }
        if(bytes.isEmpty() || bytes.size>TestWire.MAX_BYTES) throw TestRunConflict("ENVIRONMENT_CONFIG_INVALID")
        return SmokeEnvironment(agentId,deviceId,bytes)
    }
}
