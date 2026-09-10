package com.ricezhou.vsrqg.evidence

import com.ricezhou.vsrqg.evidence.adapter.*
import org.assertj.core.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

@Timeout(60)
class ControlledPayloadStoreTest {
    @TempDir lateinit var root:Path
    @org.junit.jupiter.api.BeforeEach fun secureRoot() { ownedTestRoot(root) }
    @Test fun `an oversized stream never replaces a completed payload`() {
        val store=ControlledPayloadStore(root); val first=store.write("upload_test_01",ByteArrayInputStream(byteArrayOf(1)),1)
        assertThatThrownBy { store.write("upload_test_01",ByteArrayInputStream(byteArrayOf(1,2)),1) }.isInstanceOf(PayloadLimitExceeded::class.java)
        assertThat(store.verify("upload_test_01",first)).isEqualTo(first)
    }
    @Test fun `same bytes replay and different bytes cannot overwrite`() {
        val store=ControlledPayloadStore(root); val bytes="hello".toByteArray()
        val first=store.write("upload_test_01",bytes.inputStream(),5)
        assertThat(store.write("upload_test_01",bytes.inputStream(),5)).isEqualTo(first)
        assertThatThrownBy { store.write("upload_test_01","other".byteInputStream(),5) }.hasMessage("PAYLOAD_CONFLICT")
        assertThat(store.verify("upload_test_01",first)).isEqualTo(first)
    }
    @Test fun `empty interrupted and unsafe IDs do not leave payloads`() {
        val store=ControlledPayloadStore(root)
        assertThatThrownBy { store.write("upload_test_01",byteArrayOf().inputStream(),5) }.hasMessage("PAYLOAD_EMPTY")
        assertThatThrownBy { store.write("upload_test_01",object:InputStream(){ override fun read():Int=throw IOException("synthetic disconnect") },5) }.isInstanceOf(IOException::class.java)
        listOf("../escape","x/y","CON","upload_x:stream","/absolute").forEach {
            assertThatThrownBy { store.write(it,"x".byteInputStream(),5) }.hasMessage("PAYLOAD_ID_INVALID")
        }
        Files.list(root).use { assertThat(it.count()).isZero() }
        assertThat(store.write("upload_test_01","retry".byteInputStream(),5).size).isEqualTo(5)
    }
    @Test fun `tampering truncation and non regular payload are rejected`() {
        val store=ControlledPayloadStore(root); val first=store.write("upload_test_01","hello".byteInputStream(),5)
        Files.write(root.resolve("upload_test_01.payload"),"bad".toByteArray())
        assertThatThrownBy { store.verify("upload_test_01",first) }.hasMessage("PAYLOAD_INTEGRITY_ERROR")
        Files.createDirectory(root.resolve("upload_test_02.payload"))
        assertThatThrownBy { store.write("upload_test_02","x".byteInputStream(),1) }.hasMessage("PAYLOAD_FILE_UNSAFE")
    }
    @Test fun `LOG requires strict UTF8 and SCREENSHOT requires PNG signature`() {
        val store=ControlledPayloadStore(root)
        val bad=store.write("upload_test_01",byteArrayOf(0xc3.toByte(),0x28).inputStream(),2)
        assertThatThrownBy { store.validateType("upload_test_01",bad,"LOG") }.hasMessage("PAYLOAD_TYPE_INVALID")
        val text=store.write("upload_test_02","text".byteInputStream(),4)
        assertThatThrownBy { store.validateType("upload_test_02",text,"SCREENSHOT") }.hasMessage("PAYLOAD_TYPE_INVALID")
    }
    @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.LINUX)
    fun `symlinks hardlinks and writable shared roots fail closed`() {
        val store=ControlledPayloadStore(root)
        val first=store.write("upload_test_01","hello".byteInputStream(),5)
        val outside=Files.createTempFile("outside-evidence-",".payload")
        try {
            Files.write(outside,"outside".toByteArray())
            Files.createSymbolicLink(root.resolve("upload_link.payload"),outside)
            assertThatThrownBy { store.write("upload_link","hello".byteInputStream(),5) }.hasMessage("PAYLOAD_FILE_UNSAFE")
            assertThat(Files.readString(outside)).isEqualTo("outside")
            Files.createLink(root.resolve("upload_hard.payload"),root.resolve("upload_test_01.payload"))
            assertThatThrownBy { store.verify("upload_test_01",first) }.hasMessage("PAYLOAD_FILE_UNSAFE")
            val permissions=Files.getPosixFilePermissions(root)
            Files.setPosixFilePermissions(root,permissions+java.nio.file.attribute.PosixFilePermission.OTHERS_WRITE)
            assertThatThrownBy { ControlledPayloadStore(root) }.hasMessage("PAYLOAD_ROOT_PERMISSIONS")
            Files.setPosixFilePermissions(root,permissions)
        } finally { Files.delete(outside) }
    }
    @Test @org.junit.jupiter.api.condition.EnabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    fun `Windows ACL rejects an additional writable principal`() {
        val acl=Files.getFileAttributeView(root,java.nio.file.attribute.AclFileAttributeView::class.java)
        val original=acl.acl
        val everyone=root.fileSystem.userPrincipalLookupService.lookupPrincipalByName("Everyone")
        val extra=java.nio.file.attribute.AclEntry.newBuilder().setType(java.nio.file.attribute.AclEntryType.ALLOW)
            .setPrincipal(everyone).setPermissions(java.nio.file.attribute.AclEntryPermission.WRITE_DATA).build()
        try { acl.acl=original+extra; assertThatThrownBy { ControlledPayloadStore(root) }.hasMessage("PAYLOAD_ROOT_PERMISSIONS") }
        finally { acl.acl=original }
    }
    @Test fun `exact LOG limit is accepted and a late interruption preserves previously completed bytes`() {
        val store=ControlledPayloadStore(root);val bytes=ByteArray(1024*1024) { 65 }
        val stored=store.write("upload_boundary",bytes.inputStream(),bytes.size.toLong())
        assertThat(stored.size).isEqualTo(1024*1024)
        val interrupted=object:InputStream() {
            var count=0
            override fun read():Int { if(++count>70000) throw IOException("synthetic interruption");return 66 }
        }
        assertThatThrownBy { store.write("upload_boundary",interrupted,bytes.size.toLong()) }.isInstanceOf(IOException::class.java)
        assertThat(store.verify("upload_boundary",stored)).isEqualTo(stored)
    }
}
