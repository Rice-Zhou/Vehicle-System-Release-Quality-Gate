package com.ricezhou.vsrqg

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ArchitectureTest {
    private val classes: JavaClasses = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages(BASE_PACKAGE)

    @Test
    fun `required module roots exist`() {
        val modulePackages = classes
            .filter { it.simpleName == "PackageMarker" }
            .map { it.packageName }

        assertThat(modulePackages).containsExactlyInAnyOrderElementsOf(REQUIRED_MODULE_PACKAGES)
    }

    @Test
    fun `business modules are free of cycles`() {
        slices()
            .matching("$BASE_PACKAGE.(*)..")
            .should()
            .beFreeOfCycles()
            .check(classes)
    }

    @Test
    fun `domain is independent of frameworks and adapters`() {
        noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "..adapter..")
            .allowEmptyShould(true)
            .check(classes)
    }

    @Test
    fun `application is independent of adapters`() {
        noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..")
            .allowEmptyShould(true)
            .check(classes)
    }

    @Test
    fun `module adapters do not depend on other module adapters`() {
        assertAdapterIsolation("access", "release", "manifest")
        assertAdapterIsolation("release", "access", "manifest")
        assertAdapterIsolation("manifest", "access", "release")
    }

    private fun assertAdapterIsolation(module: String, vararg otherModules: String) {
        noClasses()
            .that().resideInAPackage("..$module.adapter..")
            .should().dependOnClassesThat().resideInAnyPackage(
                *otherModules.map { "..$it.adapter.." }.toTypedArray(),
            )
            .allowEmptyShould(true)
            .check(classes)
    }

    private companion object {
        const val BASE_PACKAGE = "com.ricezhou.vsrqg"
        val REQUIRED_MODULE_PACKAGES = listOf(
            "$BASE_PACKAGE.access",
            "$BASE_PACKAGE.release",
            "$BASE_PACKAGE.manifest",
            "$BASE_PACKAGE.shared",
        )
    }
}
