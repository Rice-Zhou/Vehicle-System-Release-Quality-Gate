package com.ricezhou.vsrqg.evidence.adapter

import com.ricezhou.vsrqg.evidence.application.*
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.util.UUID

class PayloadLimitExceeded:RuntimeException("PAYLOAD_LIMIT_EXCEEDED")

/** The directory is owned exclusively by the Backend service account. No client path is accepted. */
class ControlledPayloadStore(root:Path):PayloadStore {
    private val root=root.toAbsolutePath().normalize()
    private val rootKey:Any?
    init {
        checkDirectory()
        rootKey=Files.readAttributes(this.root,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS).fileKey()
    }
    private fun checkDirectory() {
        var path:Path?=root
        while(path!=null) {
            val attrs=Files.readAttributes(path,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
            if(!attrs.isDirectory || attrs.isSymbolicLink || attrs.isOther) fail("PAYLOAD_ROOT_UNSAFE")
            path=path.parent
        }
        val acl=Files.getFileAttributeView(root,java.nio.file.attribute.AclFileAttributeView::class.java,LinkOption.NOFOLLOW_LINKS)
        if(acl!=null) {
            val lookup=root.fileSystem.userPrincipalLookupService
            val trusted=setOf(lookup.lookupPrincipalByName(System.getProperty("user.name")),lookup.lookupPrincipalByName("NT AUTHORITY\\SYSTEM"),lookup.lookupPrincipalByGroupName("BUILTIN\\Administrators"))
            if(acl.owner !in trusted) fail("PAYLOAD_ROOT_PERMISSIONS")
            val access=setOf(java.nio.file.attribute.AclEntryPermission.READ_DATA,java.nio.file.attribute.AclEntryPermission.EXECUTE,
                java.nio.file.attribute.AclEntryPermission.WRITE_DATA,java.nio.file.attribute.AclEntryPermission.APPEND_DATA,
                java.nio.file.attribute.AclEntryPermission.DELETE,java.nio.file.attribute.AclEntryPermission.DELETE_CHILD,
                java.nio.file.attribute.AclEntryPermission.WRITE_ACL,java.nio.file.attribute.AclEntryPermission.WRITE_OWNER)
            if(acl.acl.any { it.type()==java.nio.file.attribute.AclEntryType.ALLOW &&
                    java.nio.file.attribute.AclEntryFlag.INHERIT_ONLY !in it.flags() && it.principal() !in trusted && it.permissions().any(access::contains) })
                fail("PAYLOAD_ROOT_PERMISSIONS")
        }
        if(Files.getFileStore(root).supportsFileAttributeView("posix")) {
            val permissions=Files.getPosixFilePermissions(root,LinkOption.NOFOLLOW_LINKS)
            if(permissions.any { it.name.startsWith("GROUP_") || it.name.startsWith("OTHERS_") }) fail("PAYLOAD_ROOT_PERMISSIONS")
        }
    }
    private fun path(id:String):Path {
        if(!Regex("^(upload_|upl_)[A-Za-z0-9_-]{1,96}$").matches(id)) fail("PAYLOAD_ID_INVALID")
        checkDirectory()
        if(Files.readAttributes(root,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS).fileKey()!=rootKey) fail("PAYLOAD_ROOT_CHANGED")
        return root.resolve("$id.payload")
    }
    private fun regular(path:Path):BasicFileAttributes {
        val attrs=Files.readAttributes(path,BasicFileAttributes::class.java,LinkOption.NOFOLLOW_LINKS)
        if(!attrs.isRegularFile || attrs.isSymbolicLink || attrs.isOther) fail("PAYLOAD_FILE_UNSAFE")
        if(Files.getFileStore(path).supportsFileAttributeView("unix") &&
            (Files.getAttribute(path,"unix:nlink",LinkOption.NOFOLLOW_LINKS) as Number).toLong()!=1L) fail("PAYLOAD_FILE_UNSAFE")
        return attrs
    }
    override fun receive(sessionId:String,expected:StoredPayload):PayloadReceiver = candidate(sessionId,expected.size,expected)
    override fun write(sessionId:String,input:InputStream,limit:Long):StoredPayload = candidate(sessionId,limit,null).use { receiver->
        val buffer=ByteArray(64*1024)
        while(true) {
            val count=input.read(buffer)
            if(count==-1) break
            if(count==0) throw java.io.IOException("PAYLOAD_STREAM_STALLED")
            receiver.append(buffer,count)
        }
        receiver.finish()
    }
    private fun candidate(sessionId:String,limit:Long,expected:StoredPayload?):PayloadReceiver {
        require(limit in 1..MAX_BYTES)
        val target=path(sessionId)
        val temporary=root.resolve("${UUID.randomUUID()}.partial")
        val output=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)
        return object:PayloadReceiver {
            private val digest=MessageDigest.getInstance("SHA-256")
            private var size=0L
            private var closed=false
            @Synchronized override fun append(bytes:ByteArray,count:Int) {
                check(!closed) { "PAYLOAD_RECEIVER_CLOSED" }
                require(count in 1..bytes.size)
                if(count>limit-size) throw PayloadLimitExceeded()
                size+=count
                digest.update(bytes,0,count)
                val buffer=ByteBuffer.wrap(bytes,0,count)
                while(buffer.hasRemaining()) output.write(buffer)
            }
            @Synchronized override fun finish():StoredPayload {
                check(!closed) { "PAYLOAD_RECEIVER_CLOSED" }
                try {
                    if(size==0L) fail("PAYLOAD_EMPTY")
                    val stored=StoredPayload(size,"sha256:"+digest.digest().joinToString(""){ "%02x".format(it) })
                    // Reject a bad first candidate before it can occupy the immutable Session filename.
                    if(expected!=null && stored!=expected) fail("PAYLOAD_INTEGRITY_ERROR")
                    output.force(true)
                    output.close()
                    path(sessionId)
                    try { Files.createLink(target,temporary) } catch(_:FileAlreadyExistsException) {
                        if(readDigest(sessionId,MAX_BYTES)!=stored) fail("PAYLOAD_CONFLICT")
                    }
                    return stored
                } finally { close() }
            }
            @Synchronized override fun close() {
                if(closed) return
                closed=true
                try { output.close() } finally { Files.deleteIfExists(temporary) }
            }
        }
    }
    private fun readDigest(id:String,limit:Long):StoredPayload = rawOpen(id).use { input ->
        val digest=MessageDigest.getInstance("SHA-256"); val buffer=ByteArray(64*1024); var size=0L
        while(true) { val count=input.read(buffer); if(count==-1) break; size+=count
            if(size>limit) fail("PAYLOAD_INTEGRITY_ERROR"); digest.update(buffer,0,count) }
        StoredPayload(size,"sha256:"+digest.digest().joinToString(""){ "%02x".format(it) })
    }
    override fun verify(sessionId:String,expected:StoredPayload):StoredPayload {
        val actual=readDigest(sessionId,expected.size)
        if(actual!=expected) fail("PAYLOAD_INTEGRITY_ERROR")
        return actual
    }
    private fun rawOpen(id:String):InputStream {
        val target=path(id); val before=regular(target)
        val channel=FileChannel.open(target,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)
        try {
            val after=regular(path(id))
            if(before.fileKey()!=after.fileKey() || before.size()!=after.size()) fail("PAYLOAD_FILE_CHANGED")
            return Channels.newInputStream(channel)
        } catch(e:Exception) { channel.close(); throw e }
    }
    override fun open(sessionId:String,expected:StoredPayload):InputStream {
        // Hash and stream the same open handle, never verify one file and reopen another.
        val target=path(sessionId); val before=regular(target)
        val channel=FileChannel.open(target,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)
        try {
            if(regular(path(sessionId)).fileKey()!=before.fileKey()) fail("PAYLOAD_FILE_CHANGED")
            val digest=MessageDigest.getInstance("SHA-256"); val bytes=ByteBuffer.allocate(64*1024); var size=0L
            while(true) { val count=channel.read(bytes); if(count==-1) break; size+=count
                if(size>expected.size) fail("PAYLOAD_INTEGRITY_ERROR")
                bytes.flip(); digest.update(bytes); bytes.clear() }
            if(StoredPayload(size,"sha256:"+digest.digest().joinToString(""){ "%02x".format(it) })!=expected) fail("PAYLOAD_INTEGRITY_ERROR")
            channel.position(0)
            return Channels.newInputStream(channel)
        } catch(e:Exception) { channel.close(); throw e }
    }
    override fun validateType(sessionId:String,expected:StoredPayload,type:String) {
        open(sessionId,expected).use { input ->
            when(type) {
                "LOG" -> try { java.io.InputStreamReader(input,Charsets.UTF_8.newDecoder()).use { reader ->
                    val chars=CharArray(8192); while(reader.read(chars)!=-1) Unit
                } } catch(_:java.nio.charset.CharacterCodingException) { fail("PAYLOAD_TYPE_INVALID") }
                "SCREENSHOT" -> if(!input.readNBytes(8).contentEquals(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))) fail("PAYLOAD_TYPE_INVALID")
                else -> fail("PAYLOAD_TYPE_INVALID")
            }
        }
    }
    private fun fail(code:String):Nothing=throw EvidenceConflict(code)
    companion object { const val MAX_BYTES=8L*1024*1024 }
}
