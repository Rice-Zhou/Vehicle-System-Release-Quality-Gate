package com.ricezhou.vsrqg.shared.archive.operations

import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveAclEvaluator
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveDirectoryAccessControl
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveDirectoryAccessReader
import com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveTrustedAclPrincipals
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.UserPrincipal
import java.nio.file.attribute.UserPrincipalLookupService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class EvidenceArchiveDirectoryAccessReaderTest {
    @Test
    fun `actual workspace acl is accepted on Windows`() {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val workspace = Path.of("").toAbsolutePath().normalize()
        val view = checkNotNull(Files.getFileAttributeView(workspace, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS))
        val trusted = EvidenceArchiveTrustedAclPrincipals.resolve(
            view.owner,
            workspace.fileSystem.userPrincipalLookupService,
        )
        assertThat(view.acl.map { it.principal() }).allMatch { it in trusted }

        assertThat(EvidenceArchiveDirectoryAccessReader.nio().read(workspace))
            .isEqualTo(EvidenceArchiveDirectoryAccessControl.OPERATOR_CONTROLLED_ACL)
    }

    @Test
    fun `unknown allow with mutating permission fails closed even when deny exists`() {
        val owner = Principal("owner")
        val everyone = Principal("everyone")
        val entries = listOf(
            entry(AclEntryType.DENY, everyone, AclEntryPermission.WRITE_DATA),
            entry(AclEntryType.ALLOW, everyone, AclEntryPermission.WRITE_DATA),
        )

        assertThat(EvidenceArchiveAclEvaluator.isOperatorControlled(owner, entries, setOf(owner))).isFalse()
    }

    @Test
    fun `read only unknown principal is allowed`() {
        val owner = Principal("owner")
        val unknown = Principal("localized-users-name")

        assertThat(
            EvidenceArchiveAclEvaluator.isOperatorControlled(
                owner,
                listOf(entry(AclEntryType.ALLOW, unknown, AclEntryPermission.READ_DATA)),
                setOf(owner),
            ),
        ).isTrue()
    }

    @Test
    fun `owner system and administrators are trusted by resolved principal equality`() {
        val owner = Principal("localized-owner")
        val system = Principal("localized-system")
        val administrators = Principal("localized-administrators")
        val lookup = Lookup(mapOf("SYSTEM" to system, "BUILTIN\\Administrators" to administrators))
        val trusted = EvidenceArchiveTrustedAclPrincipals.resolve(owner, lookup)
        val entries = listOf(
            entry(AclEntryType.ALLOW, owner, AclEntryPermission.WRITE_DATA),
            entry(AclEntryType.ALLOW, system, AclEntryPermission.WRITE_ACL),
            entry(AclEntryType.ALLOW, administrators, AclEntryPermission.DELETE_CHILD),
        )

        assertThat(trusted).containsExactlyInAnyOrder(owner, system, administrators)
        assertThat(EvidenceArchiveAclEvaluator.isOperatorControlled(owner, entries, trusted)).isTrue()
    }

    @Test
    fun `lookup failure does not trust a same-name principal`() {
        val owner = Principal("owner")
        val guessedSystem = Principal("SYSTEM")
        val trusted = EvidenceArchiveTrustedAclPrincipals.resolve(owner, Lookup(emptyMap(), fail = true))

        assertThat(trusted).containsExactly(owner)
        assertThat(
            EvidenceArchiveAclEvaluator.isOperatorControlled(
                owner,
                listOf(entry(AclEntryType.ALLOW, guessedSystem, AclEntryPermission.WRITE_OWNER)),
                trusted,
            ),
        ).isFalse()
    }

    @Test
    fun `missing acl view and owner or acl read failures fail closed`() {
        val lookup = Lookup(emptyMap())

        listOf<AclFileAttributeView?>(
            null,
            FailingAclView(failOwner = true),
            FailingAclView(failAcl = true),
        ).forEach { view ->
            assertThatThrownBy { EvidenceArchiveAclEvaluator.requireOperatorControlled(view, lookup) }
                .isInstanceOf(IOException::class.java)
        }
    }

    private fun entry(type: AclEntryType, principal: UserPrincipal, permission: AclEntryPermission): AclEntry =
        AclEntry.newBuilder().setType(type).setPrincipal(principal).setPermissions(permission).build()

    private data class Principal(private val id: String) : UserPrincipal {
        override fun getName(): String = id
    }

    private class Lookup(
        private val principals: Map<String, UserPrincipal>,
        private val fail: Boolean = false,
    ) : UserPrincipalLookupService() {
        override fun lookupPrincipalByName(name: String): UserPrincipal {
            if (fail) throw IOException("lookup unavailable")
            return principals[name] ?: throw java.nio.file.attribute.UserPrincipalNotFoundException(name)
        }

        override fun lookupPrincipalByGroupName(group: String): GroupPrincipal =
            throw java.nio.file.attribute.UserPrincipalNotFoundException(group)
    }

    private class FailingAclView(
        private val failOwner: Boolean = false,
        private val failAcl: Boolean = false,
    ) : AclFileAttributeView {
        override fun name(): String = "acl"

        override fun getOwner(): UserPrincipal {
            if (failOwner) throw IOException("owner unavailable")
            return Principal("owner")
        }

        override fun setOwner(owner: UserPrincipal) = Unit

        override fun getAcl(): MutableList<AclEntry> {
            if (failAcl) throw IOException("acl unavailable")
            return mutableListOf()
        }

        override fun setAcl(acl: MutableList<AclEntry>) = Unit
    }
}

internal fun prepareControlledTestDirectory(path: Path) {
    val view = Files.getFileAttributeView(path, AclFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) ?: return
    val trusted = EvidenceArchiveTrustedAclPrincipals.resolve(
        view.owner,
        path.fileSystem.userPrincipalLookupService,
    )
    view.acl = view.acl.filter { it.principal() in trusted }
    check(EvidenceArchiveAclEvaluator.isOperatorControlled(view.owner, view.acl, trusted))
}
