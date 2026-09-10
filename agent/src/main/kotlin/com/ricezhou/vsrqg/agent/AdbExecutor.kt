package com.ricezhou.vsrqg.agent

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.*

 data class CommandOutput(val exitCode:Int,val stdout:ByteArray,val stderr:ByteArray)
class BoundedProcess(private val allowed:()->Boolean={true}) {
    fun run(command:List<String>,timeout:Duration,stdoutLimit:Long):CommandOutput {
        ensure(command.isNotEmpty() && timeout>Duration.ZERO && timeout<=Duration.ofSeconds(300) && stdoutLimit in 1..268435456,"PROCESS_BOUNDS_INVALID")
        ensure(allowed(),"LEASE_LOST")
        val deadline=System.nanoTime()+timeout.toNanos()
        val process=try {ProcessBuilder(command).start()} catch(_:java.io.IOException) {throw AgentFailure("PROCESS_START_FAILED")}
        process.outputStream.close()
        val readers=Executors.newVirtualThreadPerTaskExecutor()
        fun read(stream:InputStream,limit:Long)=readers.submit<ByteArray> {
            stream.use { input ->
                val out=ByteArrayOutputStream();val buffer=ByteArray(8192);var total=0L
                while(true) {val n=input.read(buffer);if(n<0) break;total+=n;ensure(total<=limit,"PROCESS_OUTPUT_LIMIT");out.write(buffer,0,n)}
                out.toByteArray()
            }
        }
        val stdout=read(process.inputStream,stdoutLimit);val stderr=read(process.errorStream,1048576)
        fun completedFailure(future:Future<ByteArray>) {if(future.isDone) future.get()}
        try {
            while(true) {
                ensure(allowed(),"LEASE_LOST")
                ensure(System.nanoTime()<deadline,"PROCESS_TIMEOUT")
                completedFailure(stdout);completedFailure(stderr)
                if(process.waitFor(20,TimeUnit.MILLISECONDS) && stdout.isDone && stderr.isDone) break
            }
            val result=CommandOutput(process.exitValue(),stdout.get(),stderr.get())
            ensure(result.exitCode==0,"PROCESS_EXIT_NONZERO")
            return result
        } catch(e:ExecutionException) {
            throw (e.cause as? AgentFailure ?: AgentFailure("PROCESS_READ_FAILED"))
        } catch(_:InterruptedException) {Thread.currentThread().interrupt();throw AgentFailure("PROCESS_INTERRUPTED")}
        finally {
            if(process.isAlive) {process.destroy();if(!process.waitFor(200,TimeUnit.MILLISECONDS)) process.destroyForcibly();ensure(process.waitFor(1000,TimeUnit.MILLISECONDS),"PROCESS_TERMINATION_FAILED")}
            process.inputStream.close();process.errorStream.close();stdout.cancel(true);stderr.cancel(true);readers.shutdownNow()
        }
    }
}
class AdbExecutor(private val executable:Path,private val selectedDevice:String,private val process:BoundedProcess=BoundedProcess()) {
    init {ensure(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}").matches(selectedDevice),"SELECTOR_INVALID")}
    fun run(arguments:List<String>,timeout:Duration,stdoutLimit:Long):CommandOutput {
        ensure(permitted(arguments),"ADB_COMMAND_DENIED")
        return process.run(listOf(executable.toString(),"-s",selectedDevice)+arguments,timeout,stdoutLimit)
    }
    private fun permitted(a:List<String>):Boolean {
        if(a in listOf(listOf("get-state"),listOf("shell","cat","/proc/sys/kernel/random/boot_id"),
            listOf("shell","getprop","ro.build.id"),listOf("shell","getprop","ro.build.fingerprint"),
            listOf("shell","pm","path",SmokeAssertions.PACKAGE),listOf("shell","dumpsys","activity","activities"),
            listOf("shell","pidof",SmokeAssertions.PACKAGE),listOf("exec-out","screencap","-p"))) return true
        if(a.size==3 && a.take(2)==listOf("install","-r")) return a[2].isNotBlank() && !a[2].contains('\u0000')
        if(a.size==3 && a.take(2)==listOf("exec-out","cat")) return xmlPath(a[2]) || Regex("/data/app/[A-Za-z0-9_~+=./-]+/base\\.apk").matches(a[2]) && !a[2].contains("..")
        if(a.size==4 && a.take(3)==listOf("shell","uiautomator","dump")) return xmlPath(a[3])
        if(a.size==4 && a.take(3)==listOf("shell","rm","--")) return xmlPath(a[3])
        if(a.size==7 && a.take(3)==listOf("logcat","-d","--pid") && Regex("[1-9][0-9]{0,8}").matches(a[3]) && a.drop(4)==listOf("-v","threadtime","*:V")) return true
        return a.size==12 && a.take(7)==listOf("shell","am","start","-W","-n",SmokeAssertions.COMPONENT,"--es") && a[7]=="attemptId" &&
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(a[8]) && a.subList(9,12)==listOf("--es","mode",a[11]) && a[11] in setOf("normal","assertion-failure")
    }
    private fun xmlPath(value:String)=Regex("/data/local/tmp/vsrqg-smoke-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.xml").matches(value)
}
