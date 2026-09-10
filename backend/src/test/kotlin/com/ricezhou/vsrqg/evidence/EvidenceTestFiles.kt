package com.ricezhou.vsrqg.evidence

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView

/** Harden only freshly-created, test-owned fixtures; production never silently changes an operator directory. */
fun ownedTestRoot(path:Path):Path {
    val acl=Files.getFileAttributeView(path,AclFileAttributeView::class.java,LinkOption.NOFOLLOW_LINKS)
    if(acl!=null) {
        val lookup=path.fileSystem.userPrincipalLookupService
        val owners=listOf(lookup.lookupPrincipalByName(System.getProperty("user.name")),
            lookup.lookupPrincipalByName("NT AUTHORITY\\SYSTEM"),lookup.lookupPrincipalByGroupName("BUILTIN\\Administrators"))
        acl.acl=owners.map { principal-> AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal)
            .setPermissions(AclEntryPermission.entries.toSet()).setFlags(java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT,
                java.nio.file.attribute.AclEntryFlag.FILE_INHERIT).build() }
    }
    return path
}

class EvidenceStorageTestInitializer:org.springframework.context.ApplicationContextInitializer<org.springframework.context.ConfigurableApplicationContext> {
    override fun initialize(context:org.springframework.context.ConfigurableApplicationContext) {
        org.springframework.boot.test.util.TestPropertyValues.of("vsrqg.demo.evidence.root="+EvidenceFixture.storage).applyTo(context.environment)
    }
}
