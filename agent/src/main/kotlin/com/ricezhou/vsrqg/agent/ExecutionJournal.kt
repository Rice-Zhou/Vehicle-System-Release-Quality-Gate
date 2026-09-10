package com.ricezhou.vsrqg.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.*
import java.nio.file.StandardOpenOption.*
import java.util.UUID

data class JournalEntry(val attemptId:String,val commandId:String,val leaseId:String,val bootSessionId:String,
    val fencingToken:Long,val lastSequence:Long,val phase:Phase,val evidenceIds:Set<String>,val resultDigest:String?)
object SafeFiles {
    fun check(path:Path):Path {
        val absolute=path.toAbsolutePath().normalize()
        var current:Path?=absolute
        while(current!=null) {
            if(Files.exists(current,LinkOption.NOFOLLOW_LINKS)) {
                val attributes=Files.readAttributes(current,java.nio.file.attribute.BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
                ensure(!attributes.isSymbolicLink && !attributes.isOther,"SYMLINK_DENIED")
            }
            current=current.parent
        }
        return absolute
    }
    fun directory(path:Path):Path {val safe=check(path);if(Files.exists(safe,LinkOption.NOFOLLOW_LINKS)) ensure(Files.isDirectory(safe,LinkOption.NOFOLLOW_LINKS),"DIRECTORY_REQUIRED");Files.createDirectories(safe);ensure(Files.isDirectory(safe,LinkOption.NOFOLLOW_LINKS),"DIRECTORY_REQUIRED");return safe}
    fun regular(path:Path):Path {val safe=check(path);ensure(Files.isRegularFile(safe,LinkOption.NOFOLLOW_LINKS),"FILE_REQUIRED");return safe}
    fun read(path:Path,limit:Int):ByteArray=Files.newInputStream(regular(path),LinkOption.NOFOLLOW_LINKS).use { input ->
        val bytes=input.readNBytes(limit+1);ensure(bytes.size<=limit,"FILE_LIMIT");bytes
    }
    fun atomic(path:Path,bytes:ByteArray) {
        val target=check(path);val parent=directory(target.parent)
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) regular(target)
        val temp=parent.resolve(".${UUID.randomUUID()}.tmp")
        try {
            FileChannel.open(temp,CREATE_NEW,WRITE,LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining()) channel.write(buffer);channel.force(true)
            }
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
            // Directory fsync is unavailable through the Windows JDK. File bytes are forced before atomic rename.
            if(!System.getProperty("os.name").startsWith("Windows")) FileChannel.open(parent,READ).use {it.force(true)}
        } finally { Files.deleteIfExists(temp) }
    }
}
class DeviceLock private constructor(private val channel:FileChannel,private val lock:FileLock):AutoCloseable {
    override fun close() {try {lock.release()} finally {channel.close()}}
    companion object {
        fun acquire(root:Path,selector:String):DeviceLock=acquireFile(SafeFiles.directory(root).resolve(Wire.sha256(selector.toByteArray()).removePrefix("sha256:")+".lock"),"DEVICE_LOCKED")
        internal fun acquireFile(path:Path,code:String):DeviceLock {
            SafeFiles.check(path);if(Files.exists(path,LinkOption.NOFOLLOW_LINKS)) SafeFiles.regular(path)
            val channel=FileChannel.open(path,CREATE,WRITE,LinkOption.NOFOLLOW_LINKS)
            val lock=try {channel.tryLock()} catch(_:OverlappingFileLockException) {null}
            if(lock==null) {channel.close();throw AgentFailure(code)}
            return DeviceLock(channel,lock)
        }
    }
}
class ExecutionJournal(root:Path):AutoCloseable {
    val root:Path=SafeFiles.directory(root)
    private val lock=DeviceLock.acquireFile(this.root.resolve(".journal.lock"),"JOURNAL_LOCKED")
    fun folder(attemptId:String)=root.resolve(SmokeAssertions.attempt(attemptId))
    fun load(attemptId:String):JournalEntry? {
        val path=folder(attemptId).resolve("journal.json");SafeFiles.check(path)
        if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS)) {
            ensure(!Files.exists(folder(attemptId)),"JOURNAL_CORRUPT")
            return null
        }
        try {
            val json=Wire.parse(SafeFiles.read(path,Wire.MAX_BYTES))
            ensure(json.path("fencingToken").isIntegralNumber && json.path("lastSequence").isIntegralNumber,"JOURNAL_CORRUPT")
            val entry=Wire.mapper.treeToValue(json,JournalEntry::class.java)
            ensure(entry.attemptId==attemptId && entry.commandId.isNotBlank() && entry.lastSequence>=0,"JOURNAL_CORRUPT")
            if(entry.phase!=Phase.RECEIVED) ensure(entry.leaseId.isNotBlank() && entry.fencingToken>0 && entry.bootSessionId.isNotBlank(),"JOURNAL_CORRUPT")
            if(entry.phase in setOf(Phase.UPLOADED,Phase.RESULT_ACKED)) ensure(entry.resultDigest?.matches(Regex("sha256:[0-9a-f]{64}"))==true,"JOURNAL_CORRUPT")
            return entry
        } catch(e:AgentFailure) {if(e.code=="SYMLINK_DENIED") throw e;throw AgentFailure("JOURNAL_CORRUPT")}
        catch(_:com.fasterxml.jackson.core.JacksonException) {throw AgentFailure("JOURNAL_CORRUPT")}
    }
    fun save(entry:JournalEntry) {SafeFiles.atomic(folder(entry.attemptId).resolve("journal.json"),Wire.mapper.writeValueAsBytes(entry))}
    fun write(attempt:String,name:String,node:JsonNode) {SafeFiles.atomic(file(attempt,name),Wire.mapper.writeValueAsBytes(node))}
    fun read(attempt:String,name:String):JsonNode=Wire.parse(SafeFiles.read(file(attempt,name),Wire.MAX_BYTES))
    fun exists(attempt:String,name:String)=Files.exists(SafeFiles.check(file(attempt,name)),LinkOption.NOFOLLOW_LINKS)
    fun file(attempt:String,name:String):Path {ensure(Regex("[a-z-]+\\.json").matches(name),"JOURNAL_NAME_INVALID");return folder(attempt).resolve(name)}
    fun entries():List<JournalEntry> = Files.list(root).use { paths -> paths.filter {Files.isDirectory(it,LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(it)}.map { path ->
        SafeFiles.check(path);load(path.fileName.toString()) ?: throw AgentFailure("JOURNAL_CORRUPT")
    }.toList() }
    override fun close()=lock.close()
}
