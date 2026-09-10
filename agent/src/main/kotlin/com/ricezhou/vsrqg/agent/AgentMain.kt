package com.ricezhou.vsrqg.agent

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import javax.net.ssl.*

class AgentConfig private constructor(val server:URI,val tlsFile:Path,val device:String,val adbFile:Path,val apk:Path,val spool:Path,val untilAttemptAcked:String?) {
    companion object {
        fun parse(args:Array<String>):AgentConfig {
            val values=linkedMapOf<String,String>()
            args.forEach { argument ->
                ensure(argument.startsWith("--") && argument.contains('='),"CLI_INVALID")
                val (key,value)=argument.removePrefix("--").split('=',limit=2)
                ensure(key in setOf("server","tls-config","device","adb-config","apk","spool","until-attempt-acked") && value.isNotBlank() && values.put(key,value)==null,"CLI_INVALID")
            }
            ensure(values.keys-setOf("until-attempt-acked")==setOf("server","tls-config","device","adb-config","apk","spool"),"CLI_REQUIRED_ARGUMENTS")
            val target=values["until-attempt-acked"]?.let(SmokeAssertions::attempt)
            val server=try {URI(values.getValue("server"))} catch(_:java.net.URISyntaxException) {throw AgentFailure("SERVER_ORIGIN_INVALID")}
            ensure(server.scheme=="https" && server.host!=null && server.userInfo==null && server.rawQuery==null && server.rawFragment==null && server.path in listOf("","/"),"SERVER_ORIGIN_INVALID")
            return AgentConfig(server,SafeFiles.regular(Path.of(values.getValue("tls-config"))),Wire.id(values.getValue("device")),
                SafeFiles.regular(Path.of(values.getValue("adb-config"))),SafeFiles.regular(Path.of(values.getValue("apk"))),SafeFiles.directory(Path.of(values.getValue("spool"))),target)
        }
    }
    fun adb():AdbConfig {
        val json=Wire.parse(SafeFiles.read(adbFile,Wire.MAX_BYTES))
        ensure(json.fieldNames().asSequence().toSet()==setOf("serial","adbExecutable","aaptExecutable","apksignerJar"),"ADB_CONFIG_INVALID")
        val serial=json.path("serial").asText()
        ensure(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}").matches(serial) && serial !in setOf("first","auto"),"SELECTOR_INVALID")
        fun file(field:String):Path {val path=Path.of(json.path(field).asText());ensure(path.isAbsolute,"CONFIG_PATH_INVALID");return SafeFiles.regular(path)}
        return AdbConfig(serial,file("adbExecutable"),file("aaptExecutable"),file("apksignerJar"))
    }
    fun tls():SSLContext {
        val json=Wire.parse(SafeFiles.read(tlsFile,Wire.MAX_BYTES))
        ensure(json.fieldNames().asSequence().toSet()==setOf("keyStore","trustStore","keyStorePasswordFile","trustStorePasswordFile"),"TLS_CONFIG_INVALID")
        fun path(name:String):Path {val value=Path.of(json.path(name).asText());ensure(value.isAbsolute,"CONFIG_PATH_INVALID");return SafeFiles.regular(value)}
        fun password(name:String)=SafeFiles.read(path(name),4096).toString(Charsets.UTF_8).trimEnd('\r','\n').toCharArray().also {ensure(it.isNotEmpty(),"TLS_PASSWORD_EMPTY")}
        val keyPassword=password("keyStorePasswordFile");val trustPassword=password("trustStorePasswordFile")
        try {
            val keyStore=KeyStore.getInstance("PKCS12").apply {SafeFiles.read(path("keyStore"),1048576).inputStream().use {load(it,keyPassword)}}
            ensure(keyStore.aliases().asSequence().count {keyStore.isKeyEntry(it)}==1,"TLS_IDENTITY_INVALID")
            val trustStore=KeyStore.getInstance("PKCS12").apply {SafeFiles.read(path("trustStore"),1048576).inputStream().use {load(it,trustPassword)}}
            val keys=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {init(keyStore,keyPassword)}
            val trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {init(trustStore)}
            return SSLContext.getInstance("TLS").apply {init(keys.keyManagers,trust.trustManagers,null)}
        } finally {keyPassword.fill('\u0000');trustPassword.fill('\u0000')}
    }
}
data class AdbConfig(val serial:String,val executable:Path,val aapt:Path,val signer:Path)
fun main(args:Array<String>) {
    try {
        val config=AgentConfig.parse(args);val adbConfig=config.adb();val tls=config.tls()
        val lockRoot=Path.of(System.getProperty("user.home"),".vsrqg-agent-locks")
        DeviceLock.acquire(lockRoot,adbConfig.serial).use {
            ExecutionJournal(config.spool).use { journal ->
                val lease=LeaseGuard()
                val adb=AdbExecutor(adbConfig.executable,adbConfig.serial,BoundedProcess {lease.allowsProcess()})
                val device=AndroidSmokeDevice(adb,ApkInspector(adbConfig.aapt,adbConfig.signer,BoundedProcess {lease.allowsProcess()}),config.apk,journal.root)
                val loop=AgentLoop(AgentClient(config.server,tls),journal,device,config.device,lease)
                if(config.untilAttemptAcked==null) loop.run() else loop.runUntilAcknowledged(config.untilAttemptAcked)
            }
        }
    } catch(e:AgentFailure) {System.err.println(e.code);kotlin.system.exitProcess(1)}
    catch(e:Exception) {System.err.println("AGENT_FAILURE_${e.javaClass.simpleName}");kotlin.system.exitProcess(1)}
}
