package com.ricezhou.vsrqg.agent

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Files
import java.nio.file.Path
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import java.io.ByteArrayOutputStream

/** Explicit test source only: real AgentLoop and HTTPS protocol, no ADB or actual installation. */
object M3FixtureAgentMain {
    @JvmStatic fun main(args:Array<String>) {
        try {
            check(System.getenv("VSRQG_M3_EXECUTION_MODE")=="CI_FIXTURE") { "FIXTURE_MODE_REQUIRED" }
            val config=AgentConfig.parse(args)
            val target=config.untilAttemptAcked ?: throw AgentFailure("FIXTURE_TARGET_REQUIRED")
            val environment=Wire.parse(SafeFiles.read(Path.of(requireNotNull(System.getenv("VSRQG_M3_ENVIRONMENT"))),65536))
            ExecutionJournal(config.spool).use { journal ->
                val previous=journal.entries().size
                AgentLoop(AgentClient(config.server,config.tls()),journal,FixtureDevice(environment,config.apk),config.device,LeaseGuard()).runUntilAcknowledged(target)
                check(journal.load(target)?.phase==Phase.RESULT_ACKED && journal.entries().size==previous+1 && journal.entries().all { it.phase==Phase.RESULT_ACKED }) { "FIXTURE_RESULT_NOT_ACKNOWLEDGED" }
            }
        } catch(e:Exception) {
            System.err.println(if(e is AgentFailure) e.code else "FIXTURE_EXECUTION_FAILED")
            kotlin.system.exitProcess(1)
        }
    }
    private class FixtureDevice(private val environment:JsonNode,private val apk:Path):SmokeDevice {
        private var installed=false
        private var launched=false
        private var currentAttempt=""
        private var currentMode=""
        override fun boot()=environment.path("bootSessionId").asText()
        override fun verifyEnvironment(context:JsonNode) { check(context.path("environment")==environment) }
        override fun preflight(context:JsonNode) {
            verifyEnvironment(context)
            check(Wire.sha256(Files.readAllBytes(apk))==context.path("apk").path("checksum").asText())
            check(context.path("apk").path("packageName").asText()==SmokeAssertions.PACKAGE)
        }
        override fun install() { check(!installed); installed=true }
        override fun verifyInstalled(context:JsonNode) { check(installed);preflight(context) }
        override fun launch(attemptId:String,mode:String):Boolean {
            check(installed && !launched); currentAttempt=SmokeAssertions.attempt(attemptId);currentMode=SmokeAssertions.mode(mode);launched=true;return true
        }
        override fun foreground()=launched
        override fun ui(attemptId:String):ByteArray {
            check(launched && attemptId==currentAttempt)
            val marker=if(currentMode=="normal") "VSRQG_SMOKE_READY" else "VSRQG_SMOKE_NOT_READY"
            return """<hierarchy><node package="${SmokeAssertions.PACKAGE}" text="$marker:$currentAttempt"/></hierarchy>""".toByteArray()
        }
        override fun appLog()="SYNTHETIC_DEMO CI_FIXTURE $currentAttempt $currentMode".toByteArray()
        override fun screenshot():ByteArray = ByteArrayOutputStream().use { output ->
            check(launched);check(ImageIO.write(BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"PNG",output));output.toByteArray()
        }
    }
}
