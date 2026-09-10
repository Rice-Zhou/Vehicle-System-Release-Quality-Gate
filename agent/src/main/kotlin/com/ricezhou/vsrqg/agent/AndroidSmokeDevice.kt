package com.ricezhou.vsrqg.agent

import com.fasterxml.jackson.databind.JsonNode
import java.nio.file.Path
import java.nio.file.Files
import java.time.Duration
import java.security.MessageDigest

interface SmokeDevice {
    fun boot():String
    fun verifyEnvironment(context:JsonNode)
    fun preflight(context:JsonNode)
    fun install()
    fun verifyInstalled(context:JsonNode)
    fun launch(attemptId:String,mode:String):Boolean
    fun foreground():Boolean
    fun ui(attemptId:String):ByteArray
    fun appLog():ByteArray
    fun screenshot():ByteArray
}
data class ApkIdentity(val version:Int,val signer:String)
fun interface ApkInspection {
    fun inspect(apk:Path):ApkIdentity
}
class ApkInspector(private val aapt:Path,private val signer:Path,private val process:BoundedProcess):ApkInspection {
    override fun inspect(apk:Path):ApkIdentity {
        SafeFiles.regular(apk)
        val badging=process.run(listOf(aapt.toString(),"dump","badging",apk.toString()),Duration.ofSeconds(20),1048576).stdout.toString(Charsets.UTF_8)
        val java=Path.of(System.getProperty("java.home"),"bin",if(System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val signature=process.run(listOf(java.toString(),"-jar",signer.toString(),"verify","--verbose","--print-certs",apk.toString()),Duration.ofSeconds(20),1048576).stdout.toString(Charsets.UTF_8)
        return parse(badging,signature)
    }
    companion object {
        fun parse(badging:String,signer:String):ApkIdentity {
            val packages=Regex("(?m)^package: name='([^']+)' versionCode='([0-9]+)'(?: |$)").findAll(badging).toList()
            ensure(packages.size==1 && packages[0].groupValues[1]==SmokeAssertions.PACKAGE && packages[0].groupValues[2]=="1","APK_IDENTITY_INVALID")
            val certs=Regex("(?m)^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]{64})\\s*$").findAll(signer).toList()
            ensure(certs.size==1 && certs[0].value.startsWith("Signer #1 "),"APK_SIGNER_INVALID")
            return ApkIdentity(1,"sha256:"+certs.single().groupValues[1].lowercase())
        }
        fun checksum(path:Path):String {
            val digest=MessageDigest.getInstance("SHA-256");var total=0L
            Files.newInputStream(SafeFiles.regular(path),java.nio.file.LinkOption.NOFOLLOW_LINKS).use {input ->
                val buffer=ByteArray(65536)
                while(true) {val n=input.read(buffer);if(n<0) break;total+=n;ensure(total<=268435456,"APK_SIZE_LIMIT");digest.update(buffer,0,n)}
            }
            ensure(total>0,"APK_EMPTY")
            return "sha256:"+digest.digest().joinToString("") {"%02x".format(it)}
        }
    }
}
class AndroidSmokeDevice(private val adb:AdbCommands,private val inspector:ApkInspection,private val apk:Path,private val spool:Path):SmokeDevice {
    private var verifiedApk:Path?=null
    private var attempt:String?=null
    private fun bindAttempt(context:JsonNode):String {
        attempt=null
        return SmokeAssertions.attempt(context.path("attemptId").asText()).also {attempt=it}
    }
    private fun text(args:List<String>,timeout:Long=10)=adb.run(args,Duration.ofSeconds(timeout),1048576).stdout.toString(Charsets.UTF_8).trim()
    override fun boot():String {
        ensure(text(listOf("get-state"))=="device","DEVICE_DISCONNECTED")
        return text(listOf("shell","cat","/proc/sys/kernel/random/boot_id")).also {SmokeAssertions.attempt(it)}
    }
    override fun verifyEnvironment(context:JsonNode) {
        val environment=context.path("environment")
        ensure(boot()==environment.path("bootSessionId").asText(),"ENVIRONMENT_IDENTITY_CHANGED")
        ensure(text(listOf("shell","getprop","ro.build.id"))==environment.path("buildId").asText() &&
            text(listOf("shell","getprop","ro.build.fingerprint"))==environment.path("buildFingerprint").asText(),"ENVIRONMENT_IDENTITY_CHANGED")
    }
    override fun preflight(context:JsonNode) {
        val id=bindAttempt(context)
        val sdk=text(listOf("shell","getprop","ro.build.version.sdk"))
        ensure(Regex("[1-9][0-9]*").matches(sdk),"DEVICE_API_LEVEL_INVALID")
        val apiLevel=sdk.toIntOrNull() ?: throw AgentFailure("DEVICE_API_LEVEL_INVALID")
        ensure(apiLevel>=MIN_SUPPORTED_API_LEVEL,"DEVICE_API_LEVEL_UNSUPPORTED")
        verifyEnvironment(context)
        val folder=SafeFiles.directory(spool.resolve(id))
        val copy=folder.resolve("source.apk")
        // Install the verified spool snapshot, never reopen a mutable source selected by the caller.
        SafeFiles.atomic(copy,SafeFiles.read(apk,268435456))
        verify(copy,context,true)
        val installed=existingBasePath()
        if(installed!=null) {
            val old=pull(installed,folder.resolve("installed-before.apk"))
            ensure(inspector.inspect(old).signer==context.path("apk").path("signingCertificateSha256").asText(),"APK_SIGNATURE_CONFLICT")
        }
        verifiedApk=copy
    }
    private fun existingBasePath():String? {
        val result=try {
            adb.run(listOf("shell","pm","path",SmokeAssertions.PACKAGE),Duration.ofSeconds(10),1048576)
        } catch(failure:ProcessExitFailure) {
            // AOSP pm path reports a missing package only as exit 1 with both streams empty.
            val output=failure.output
            if(output.exitCode==1 && output.stdout.isEmpty() && output.stderr.isEmpty()) return null
            throw failure
        }
        ensure(result.exitCode==0 && result.stderr.isEmpty(),"APK_BASE_UNAVAILABLE")
        return basePath(result.stdout.toString(Charsets.UTF_8).trim())
    }
    override fun install() {
        val path=checkNotNull(verifiedApk);SafeFiles.regular(path)
        ensure(text(listOf("install","-r",path.toString()),60).lineSequence().any {it=="Success"},"APK_INSTALL_FAILED")
    }
    override fun verifyInstalled(context:JsonNode) {
        val id=bindAttempt(context)
        val path=basePath(text(listOf("shell","pm","path",SmokeAssertions.PACKAGE)))
        verify(pull(path,spool.resolve(id).resolve("installed-after.apk")),context,true)
    }
    private fun pull(remote:String,target:Path):Path {
        val bytes=adb.run(listOf("exec-out","cat",remote),Duration.ofSeconds(30),268435456).stdout
        ensure(bytes.isNotEmpty(),"APK_UNREADABLE");SafeFiles.atomic(target,bytes);return target
    }
    private fun verify(path:Path,context:JsonNode,bytes:Boolean) {
        val expected=context.path("apk");val actual=inspector.inspect(path)
        ensure(actual.signer==expected.path("signingCertificateSha256").asText(),"APK_SIGNATURE_CONFLICT")
        ensure(actual.version==expected.path("versionCode").asInt(),"APK_VERSION_MISMATCH")
        if(bytes) ensure(ApkInspector.checksum(path)==expected.path("checksum").asText(),"APK_CHECKSUM_MISMATCH")
    }
    override fun launch(attemptId:String,mode:String):Boolean=SmokeAssertions.launch(text(listOf("shell","am","start","-W","-n",SmokeAssertions.COMPONENT,"--es","attemptId",SmokeAssertions.attempt(attemptId),"--es","mode",SmokeAssertions.mode(mode)),30))
    override fun foreground()=SmokeAssertions.foreground(text(listOf("shell","dumpsys","activity","activities")))
    override fun ui(attemptId:String):ByteArray {
        val remote=SmokeAssertions.remoteXml(attemptId)
        adb.run(listOf("shell","uiautomator","dump",remote),Duration.ofSeconds(15),1048576)
        try {return adb.run(listOf("exec-out","cat",remote),Duration.ofSeconds(10),SmokeAssertions.XML_LIMIT).stdout}
        finally {adb.run(listOf("shell","rm","--",remote),Duration.ofSeconds(5),1048576)}
    }
    override fun appLog():ByteArray {
        val pid=text(listOf("shell","pidof",SmokeAssertions.PACKAGE))
        ensure(Regex("[1-9][0-9]{0,8}").matches(pid),"APP_PID_UNAVAILABLE")
        return adb.run(listOf("logcat","-d","--pid",pid,"-v","threadtime","*:V"),Duration.ofSeconds(10),1048576).stdout
    }
    override fun screenshot():ByteArray {
        val remote=SmokeAssertions.remotePng(attempt ?: throw AgentFailure("ATTEMPT_BINDING_REQUIRED"))
        ensure(foreground(),"SCREENSHOT_FOREGROUND_REQUIRED")
        // File transport isolates PNG bytes from device screencap stdout diagnostics.
        return AutoCloseable {adb.run(listOf("shell","rm","--",remote),Duration.ofSeconds(5),1048576)}.use {
            adb.run(listOf("shell","screencap","-p",remote),Duration.ofSeconds(10),1048576)
            adb.run(listOf("exec-out","cat",remote),Duration.ofSeconds(10),8388608).stdout
        }
    }
    companion object {
        private const val MIN_SUPPORTED_API_LEVEL=26
        fun basePath(output:String):String {
            val lines=output.lineSequence().filter {it.isNotBlank()}.toList()
            ensure(lines.size==1 && lines[0].startsWith("package:"),"APK_BASE_UNAVAILABLE")
            val path=lines.single().removePrefix("package:")
            ensure(Regex("/data/app/[A-Za-z0-9_~+=./-]+/base\\.apk").matches(path) && !path.contains(".."),"APK_BASE_UNAVAILABLE")
            return path
        }
    }
}
