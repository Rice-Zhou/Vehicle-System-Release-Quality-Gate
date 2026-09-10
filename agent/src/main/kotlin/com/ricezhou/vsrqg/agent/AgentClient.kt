package com.ricezhou.vsrqg.agent

import com.fasterxml.jackson.databind.JsonNode
import java.net.URI
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.*
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext

class AgentClient(private val server:URI,private val tls:SSLContext,private val timeout:Duration=Duration.ofSeconds(30)) {
    init {
        ensure(server.scheme=="https" && server.host!=null && server.userInfo==null && server.rawQuery==null && server.rawFragment==null && server.path in listOf("","/"),"SERVER_ORIGIN_INVALID")
        ensure(timeout>Duration.ZERO && timeout<=Duration.ofSeconds(30),"HTTP_TIMEOUT_INVALID")
    }
    private val routes=Wire.openApi.path("paths").properties().asSequence().filter {it.key.startsWith("/agent-api/v1/")}.flatMap { (path,node) ->
        node.fieldNames().asSequence().filter {it.uppercase() in setOf("GET","POST","PUT")}.map {method ->
            Triple(method.uppercase(),Regex(path.split(Regex("\\{[^}]+}")).joinToString("[A-Za-z0-9_-]{1,128}") {Regex.escape(it)}),node.path(method))
        }
    }.toList()
    private fun target(method:String,path:String):Pair<URI,JsonNode> {
        val uri=try {URI(path)} catch(_:java.net.URISyntaxException) {throw AgentFailure("HTTP_PATH_INVALID")}
        ensure(!uri.isAbsolute && path.startsWith("/") && !path.startsWith("//") && uri.rawQuery==null && uri.rawFragment==null && !path.contains('%') && !path.contains(".."),"HTTP_PATH_INVALID")
        val route=routes.singleOrNull {it.first==method && it.second.matches(path)} ?: throw AgentFailure("HTTP_PATH_INVALID")
        val full=server.resolve(uri)
        ensure(full.scheme==server.scheme && full.host==server.host && full.port==server.port && full.userInfo==null,"HTTP_ORIGIN_INVALID")
        return full to route.third
    }
    fun call(method:String,path:String,body:JsonNode?,key:String?):JsonNode {
        val (uri,route)=target(method,path)
        ensure(method!="GET" || body==null,"HTTP_BODY_INVALID")
        if(method!="GET") {
            ensure(key!=null && Regex("[A-Za-z0-9_:-]{1,128}").matches(key),"IDEMPOTENCY_KEY_INVALID")
            val requestBody=route.path("requestBody").let { node -> if(node.has("${'$'}ref")) Wire.openApi.at(node.path("${'$'}ref").asText().removePrefix("#")) else node }
            val ref=requestBody.path("content").path("application/json").path("schema").path("${'$'}ref").asText()
            val schema=ref.substringAfterLast('/')
            ensure(body!=null && schema.isNotBlank(),"HTTP_BODY_INVALID")
            Wire.validate(checkNotNull(body),schema)
        }
        val bytes=exchange(method,uri,body?.let(Wire.mapper::writeValueAsBytes),null,key)
        return Wire.parse(bytes)
    }
    fun putPayload(path:String,file:Path) {
        val (uri,_)=target("PUT",path);ensure(path.endsWith("/payload"),"HTTP_PATH_INVALID")
        SafeFiles.regular(file);ensure(java.nio.file.Files.size(file) in 1..8388608,"PAYLOAD_SIZE_INVALID")
        exchange("PUT",uri,null,file,null)
    }
    private fun exchange(method:String,uri:URI,json:ByteArray?,file:Path?,key:String?):ByteArray {
        val connection=uri.toURL().openConnection() as HttpsURLConnection
        connection.sslSocketFactory=tls.socketFactory;connection.instanceFollowRedirects=false
        connection.connectTimeout=timeout.toMillis().toInt();connection.readTimeout=timeout.toMillis().toInt();connection.requestMethod=method
        connection.setRequestProperty("Accept","application/json")
        if(key!=null) connection.setRequestProperty("Idempotency-Key",key)
        val executor=Executors.newVirtualThreadPerTaskExecutor()
        val task=executor.submit<ByteArray> {
            if(json!=null || file!=null) {
                connection.doOutput=true
                connection.setRequestProperty("Content-Type",if(file!=null) "application/octet-stream" else "application/json; charset=UTF-8")
                val size=if(file!=null) java.nio.file.Files.size(file) else checkNotNull(json).size.toLong()
                connection.setFixedLengthStreamingMode(size)
                connection.outputStream.use { out ->
                    if(file==null) out.write(json) else java.nio.file.Files.newInputStream(SafeFiles.regular(file),java.nio.file.LinkOption.NOFOLLOW_LINKS).use { input ->
                        val buffer=ByteArray(65536);var total=0L
                        while(true) {val n=input.read(buffer);if(n<0) break;total+=n;ensure(total<=size && total<=8388608,"PAYLOAD_SIZE_INVALID");out.write(buffer,0,n)}
                        ensure(total==size,"PAYLOAD_SIZE_INVALID")
                    }
                }
            }
            val status=connection.responseCode
            ensure(status in 200..299,"HTTP_STATUS_$status")
            ensure(connection.contentLengthLong<=Wire.MAX_BYTES,"HTTP_RESPONSE_LIMIT")
            connection.inputStream.use { input ->val bytes=input.readNBytes(Wire.MAX_BYTES+1);ensure(bytes.size<=Wire.MAX_BYTES,"HTTP_RESPONSE_LIMIT");bytes}
        }
        try {return task.get(timeout.toMillis(),TimeUnit.MILLISECONDS)}
        catch(_:TimeoutException) {throw AgentFailure("HTTP_TIMEOUT")}
        catch(e:ExecutionException) {throw (e.cause as? AgentFailure ?: AgentFailure("HTTP_IO_ERROR"))}
        catch(_:InterruptedException) {Thread.currentThread().interrupt();throw AgentFailure("HTTP_INTERRUPTED")}
        finally {task.cancel(true);connection.disconnect();executor.shutdownNow()}
    }
}
