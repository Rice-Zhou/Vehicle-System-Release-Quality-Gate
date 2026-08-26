package com.ricezhou.vsrqg.shared.archive

import com.ricezhou.vsrqg.shared.adapter.archive.FilesystemStagingArchiveAdapter
import com.ricezhou.vsrqg.shared.application.archive.ArchiveAdapter
import com.ricezhou.vsrqg.shared.application.archive.ArchiveAuthorization
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCapabilityReport
import com.ricezhou.vsrqg.shared.application.archive.ArchiveCommand
import com.ricezhou.vsrqg.shared.application.archive.ArchiveEvidence
import com.ricezhou.vsrqg.shared.application.archive.ArchivePolicy
import com.ricezhou.vsrqg.shared.application.archive.ArchiveResult
import com.ricezhou.vsrqg.shared.application.archive.EvaluateArchiveCapability
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredMemberFunctions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ArchiveBoundaryTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages(BASE_PACKAGE)

    @Test
    fun `public facade exposes only archive command and keeps trusted construction internal`() {
        assertThat(ArchiveEvidence::class.visibility).isEqualTo(KVisibility.PUBLIC)
        assertThat(ArchiveEvidence::class.constructors).allMatch { it.visibility == KVisibility.INTERNAL }
        val publicFunctions = ArchiveEvidence::class.declaredMemberFunctions
            .filter { it.visibility == KVisibility.PUBLIC }

        assertThat(publicFunctions.map { it.name }).containsExactly("archive")
        assertThat(publicFunctions.single().parameters.mapNotNull { it.type.classifier })
            .containsExactly(ArchiveEvidence::class, ArchiveCommand::class)
        assertThat(publicFunctions.single().returnType.classifier).isEqualTo(ArchiveResult::class)
        assertThat(publicFunctions.single().parameters.map { it.type.classifier })
            .doesNotContain(ArchivePolicy::class, ArchiveCapabilityReport::class, ArchiveAuthorization::class)
    }

    @Test
    fun `adapter evaluator authorization and concrete filesystem implementation remain internal`() {
        assertThat(ArchiveAdapter::class.visibility).isEqualTo(KVisibility.INTERNAL)
        assertThat(EvaluateArchiveCapability::class.visibility).isEqualTo(KVisibility.INTERNAL)
        assertThat(ArchiveAuthorization::class.visibility).isEqualTo(KVisibility.INTERNAL)
        assertThat(FilesystemStagingArchiveAdapter::class.visibility).isEqualTo(KVisibility.INTERNAL)
        assertThat(ArchiveAuthorization::class.constructors).allMatch { it.visibility == KVisibility.INTERNAL }
    }

    @Test
    fun `only evaluator probes only facade archives and only evaluator constructs authorization`() {
        val methodCalls = classes.flatMap { it.methodCallsFromSelf }
        val probeCallers = methodCalls
            .filter { it.target.owner.name == ArchiveAdapter::class.java.name && it.target.name == "probe" }
            .map { it.originOwner.name }
            .toSet()
        val archiveCallers = methodCalls
            .filter { it.target.owner.name == ArchiveAdapter::class.java.name && it.target.name == "archive" }
            .map { it.originOwner.name }
            .toSet()
        val authorizationConstructors = classes
            .flatMap { it.constructorCallsFromSelf }
            .filter { it.target.owner.name == ArchiveAuthorization::class.java.name }
            .map { it.originOwner.name }
            .toSet()

        assertThat(probeCallers).containsExactly(EvaluateArchiveCapability::class.java.name)
        assertThat(archiveCallers).containsExactly(ArchiveEvidence::class.java.name)
        assertThat(authorizationConstructors).containsExactly(EvaluateArchiveCapability::class.java.name)
    }

    @Test
    fun `application has no concrete adapter dependency or second capability source`() {
        val applicationClasses = classes.filter { it.packageName.startsWith(APPLICATION_PACKAGE) }
        val concreteAdapterDependencies = applicationClasses
            .flatMap { it.directDependenciesFromSelf }
            .filter { it.targetClass.name == FilesystemStagingArchiveAdapter::class.java.name }
        val evaluators = classes.filter { it.simpleName == "EvaluateArchiveCapability" }
        val archiveCaches = classes.filter {
            it.packageName.contains("archive") && it.simpleName.contains("Cache", ignoreCase = true)
        }

        assertThat(concreteAdapterDependencies).isEmpty()
        assertThat(evaluators).hasSize(1)
        assertThat(archiveCaches).isEmpty()
    }

    @Test
    fun `forged report or authorization cannot cross the public facade`() {
        val archiveMethods = ArchiveEvidence::class.java.methods.filter { it.name == "archive" }

        assertThat(archiveMethods).hasSize(1)
        assertThat(archiveMethods.single().parameterTypes).containsExactly(ArchiveCommand::class.java)
        assertThat(archiveMethods.single().parameterTypes)
            .doesNotContain(ArchiveCapabilityReport::class.java, ArchiveAuthorization::class.java)
    }

    private companion object {
        const val BASE_PACKAGE = "com.ricezhou.vsrqg"
        const val APPLICATION_PACKAGE = "$BASE_PACKAGE.shared.application.archive"
    }
}
