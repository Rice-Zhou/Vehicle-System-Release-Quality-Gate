package com.ricezhou.vsrqg.shared

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.zaxxer.hikari.HikariConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Bindable
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.test.context.BootstrapUtils
import org.springframework.test.context.ContextCustomizer
import org.springframework.test.context.MergedContextConfiguration
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.support.TestPropertySourceUtils

class PostgresIntegrationPoolBudgetTest {
    @Test
    fun `shared postgres contexts bind the fixed test oidc authority`() {
        val annotation = AnnotatedElementUtils.findMergedAnnotation(
            PostgresIntegrationTest::class.java,
            TestPropertySource::class.java,
        )
        assertThat(annotation)
            .describedAs("shared PostgreSQL test OIDC authority")
            .isNotNull()
        val properties = requireNotNull(annotation).properties.associate { property ->
            property.substringBefore('=') to property.substringAfter('=')
        }

        assertThat(properties[OIDC_ISSUER_PROPERTY]).isEqualTo(TEST_OIDC_ISSUER)
        assertThat(properties[OIDC_AUDIENCE_PROPERTY]).isEqualTo(TEST_OIDC_AUDIENCE)
    }

    @Test
    fun `shared postgres contexts bind the concurrency peak without retaining idle connections`() {
        val annotation = AnnotatedElementUtils.findMergedAnnotation(
            PostgresIntegrationTest::class.java,
            TestPropertySource::class.java,
        )
        assertThat(annotation)
            .describedAs("shared PostgreSQL test pool authority")
            .isNotNull()
        val properties = requireNotNull(annotation).properties.associate { property ->
            property.substringBefore('=') to property.substringAfter('=')
        }
        val configuration = HikariConfig().also { hikari ->
            Binder(MapConfigurationPropertySource(properties)).bind(
                "spring.datasource.hikari",
                Bindable.ofInstance(hikari),
            )
        }

        assertThat(configuration.maximumPoolSize).isEqualTo(3)
        assertThat(configuration.minimumIdle).isZero()
    }

    @Test
    fun `every postgres context inherits the shared pool budget without an override`() {
        val sharedDynamicProperties = mergedConfiguration(PostgresIntegrationTest::class.java)
            .dynamicPropertiesCustomizer()
        assertThat(sharedDynamicProperties)
            .describedAs("shared PostgreSQL dynamic property authority")
            .isNotNull()
        val testClasses = postgresTestClasses()

        assertThat(testClasses).isNotEmpty()
        testClasses.forEach { testClass ->
            val merged = mergedConfiguration(testClass)
            val properties = TestPropertySourceUtils.convertInlinedPropertiesToMap(
                *merged.propertySourceProperties,
            )

            assertThat(properties["spring.datasource.hikari.maximum-pool-size"])
                .describedAs("%s effective maximum pool size", testClass.simpleName)
                .isEqualTo("3")
            assertThat(properties["spring.datasource.hikari.minimum-idle"])
                .describedAs("%s effective minimum idle", testClass.simpleName)
                .isEqualTo("0")
            assertThat(merged.dynamicPropertiesCustomizer())
                .describedAs("%s dynamic property authority", testClass.simpleName)
                .isEqualTo(sharedDynamicProperties)
        }
    }

    @Test
    fun `every postgres context merges shared authority without an override or lost local properties`() {
        val testClasses = postgresTestClasses()

        assertThat(testClasses).isNotEmpty()
        testClasses.forEach { testClass ->
            val mergedProperties = TestPropertySourceUtils.convertInlinedPropertiesToMap(
                *mergedConfiguration(testClass).propertySourceProperties,
            )
            val declaredProperties = testClass.getDeclaredAnnotation(TestPropertySource::class.java)
                ?.properties
                ?.map { it.substringBefore('=') }
                .orEmpty()

            assertThat(mergedProperties[OIDC_ISSUER_PROPERTY])
                .describedAs("%s effective OIDC issuer", testClass.simpleName)
                .isEqualTo(TEST_OIDC_ISSUER)
            assertThat(mergedProperties[OIDC_AUDIENCE_PROPERTY])
                .describedAs("%s effective OIDC audience", testClass.simpleName)
                .isEqualTo(TEST_OIDC_AUDIENCE)
            assertThat(declaredProperties)
                .describedAs("%s must not override shared OIDC authority", testClass.simpleName)
                .doesNotContain(OIDC_ISSUER_PROPERTY, OIDC_AUDIENCE_PROPERTY)
        }
        EXPECTED_INCREMENTAL_PROPERTIES.forEach { (className, expectedProperties) ->
            val testClass = testClasses.single { it.simpleName == className }
            val mergedProperties = TestPropertySourceUtils.convertInlinedPropertiesToMap(
                *mergedConfiguration(testClass).propertySourceProperties,
            )

            assertThat(mergedProperties)
                .describedAs("%s effective incremental properties", className)
                .containsAllEntriesOf(expectedProperties)
        }
    }

    private fun postgresTestClasses(): List<Class<*>> =
        ClassFileImporter().importPackages("com.ricezhou.vsrqg")
            .filter {
                it.isAssignableTo(PostgresIntegrationTest::class.java) &&
                    !it.isEquivalentTo(PostgresIntegrationTest::class.java)
            }
            .map { it.reflect() }

    private fun mergedConfiguration(testClass: Class<*>): MergedContextConfiguration =
        BootstrapUtils.resolveTestContextBootstrapper(testClass)
            .buildMergedContextConfiguration()

    private fun MergedContextConfiguration.dynamicPropertiesCustomizer(): ContextCustomizer? =
        contextCustomizers.singleOrNull { it.javaClass.name == DYNAMIC_PROPERTIES_CUSTOMIZER }

    private companion object {
        const val OIDC_ISSUER_PROPERTY = "spring.security.oauth2.resourceserver.jwt.issuer-uri"
        const val OIDC_AUDIENCE_PROPERTY = "spring.security.oauth2.resourceserver.jwt.audiences[0]"
        const val TEST_OIDC_ISSUER = "https://idp.vsrqg.test"
        const val TEST_OIDC_AUDIENCE = "vsrqg-api"
        val EXPECTED_INCREMENTAL_PROPERTIES = mapOf(
            "M1EndToEndTest" to mapOf(
                "vsrqg.manifest.trusted-validator-versions" to "m1-acceptance-validator/1",
            ),
            "ManifestLockConcurrencyTest" to mapOf(
                "vsrqg.manifest.trusted-validator-versions" to "trusted-artifact-fixture/1",
            ),
            "BuildProvenanceIntegrationTest" to mapOf(
                "vsrqg.traceability.ingestion.enabled" to "true",
            ),
            "BuildProvenanceDisabledIntegrationTest" to mapOf(
                "vsrqg.traceability.ingestion.enabled" to "false",
            ),
            "BuildProvenanceTransactionFailureTest" to mapOf(
                "vsrqg.traceability.ingestion.enabled" to "true",
            ),
            "TraceabilityVerificationStartIntegrationTest" to mapOf(
                "vsrqg.traceability.verification.enabled" to "true",
            ),
            "TraceabilityVerificationStartFailureTest" to mapOf(
                "vsrqg.traceability.verification.enabled" to "true",
            ),
            "TraceabilityVerificationWorkerPostgresTest" to mapOf(
                "vsrqg.traceability.verification.enabled" to "true",
            ),
        )
        const val DYNAMIC_PROPERTIES_CUSTOMIZER =
            "org.springframework.test.context.support.DynamicPropertiesContextCustomizer"
    }
}
