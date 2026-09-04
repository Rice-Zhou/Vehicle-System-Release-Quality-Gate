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
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.support.TestPropertySourceUtils

class PostgresIntegrationPoolBudgetTest {
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
        val testClasses = ClassFileImporter().importPackages("com.ricezhou.vsrqg")
            .filter {
                it.isAssignableTo(PostgresIntegrationTest::class.java) &&
                    !it.isEquivalentTo(PostgresIntegrationTest::class.java)
            }
            .map { it.reflect() }

        assertThat(testClasses).isNotEmpty()
        testClasses.forEach { testClass ->
            val merged = BootstrapUtils.resolveTestContextBootstrapper(testClass)
                .buildMergedContextConfiguration()
            val properties = TestPropertySourceUtils.convertInlinedPropertiesToMap(
                *merged.propertySourceProperties,
            )

            assertThat(properties["spring.datasource.hikari.maximum-pool-size"])
                .describedAs("%s effective maximum pool size", testClass.simpleName)
                .isEqualTo("3")
            assertThat(properties["spring.datasource.hikari.minimum-idle"])
                .describedAs("%s effective minimum idle", testClass.simpleName)
                .isEqualTo("0")
        }
    }
}
