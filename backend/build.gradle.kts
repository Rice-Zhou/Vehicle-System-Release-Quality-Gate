import java.nio.file.Files
import java.util.UUID

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
}

group = "com.ricezhou.vsrqg"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

springBoot {
    mainClass.set("com.ricezhou.vsrqg.VsrqgApplicationKt")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(org.springframework.boot.gradle.plugin.SpringBootPlugin.BOM_COORDINATES))
    implementation(platform("software.amazon.awssdk:bom:2.54.4"))
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.flywaydb:flyway-core")
    implementation("com.networknt:json-schema-validator:2.0.4")
    implementation("io.github.erdtman:java-json-canonicalization:1.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:sts")
    implementation("software.amazon.awssdk:url-connection-client")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("com.tngtech.archunit:archunit-junit5:${libs.versions.archunit.get()}")
}

tasks.test {
    useJUnitPlatform()
}

// Package the authoritative wire schema without maintaining a backend copy.
tasks.processResources {
    from("../schemas/v0.2/agent-protocol.schema.json") { into("contracts") }
    from("../schemas/v0.2/agent-execution-context.schema.json") { into("contracts") }
    from("../contracts/openapi/v0.2/openapi.json") { into("contracts") }
}

tasks.register<JavaExec>("evidenceArchiveOperation") {
    group = "application"
    description = "Runs the controlled Evidence Archive work package operation"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.ricezhou.vsrqg.shared.adapter.archive.operations.EvidenceArchiveOperationMain")
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(21))
        },
    )
    doFirst {
        val prefix = "VSRQG_EVIDENCE_OPERATION_"
        val commandName = "${prefix}COMMAND"
        val workPackageName = "${prefix}WORK_PACKAGE"
        val sourceRootName = "${prefix}SOURCE_ROOT"
        val archiveReportName = "${prefix}ARCHIVE_REPORT"
        val recoveryRootName = "${prefix}RECOVERY_ROOT"
        val outputName = "${prefix}OUTPUT"
        val knownNames = setOf(
            commandName,
            workPackageName,
            sourceRootName,
            archiveReportName,
            recoveryRootName,
            outputName,
        )
        val supplied = System.getenv().filterKeys { it.startsWith(prefix) }
        if (supplied.isEmpty()) return@doFirst
        if (supplied.keys.any { it !in knownNames } || supplied.values.any(String::isBlank)) {
            throw GradleException("EVIDENCE_OPERATION_ENV_INVALID")
        }

        val command = supplied[commandName]
        val required = when (command) {
            "archive" -> setOf(commandName, workPackageName, sourceRootName, outputName)
            "verify" -> setOf(commandName, workPackageName, archiveReportName, recoveryRootName, outputName)
            else -> throw GradleException("EVIDENCE_OPERATION_ENV_INVALID")
        }
        if (supplied.keys != required) throw GradleException("EVIDENCE_OPERATION_ENV_INVALID")

        val operationArgs = when (command) {
            "archive" -> listOf(
                "archive",
                "--work-package=${supplied.getValue(workPackageName)}",
                "--source-root=${supplied.getValue(sourceRootName)}",
                "--output=${supplied.getValue(outputName)}",
            )
            "verify" -> listOf(
                "verify",
                "--work-package=${supplied.getValue(workPackageName)}",
                "--archive-report=${supplied.getValue(archiveReportName)}",
                "--recovery-root=${supplied.getValue(recoveryRootName)}",
                "--output=${supplied.getValue(outputName)}",
            )
            else -> error("validated command was lost")
        }
        setArgs(operationArgs)
    }
}

val demo by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[demo.implementationConfigurationName].extendsFrom(configurations.implementation.get())
configurations[demo.runtimeOnlyConfigurationName].extendsFrom(configurations.runtimeOnly.get())
sourceSets.test {
    compileClasspath += demo.output
    runtimeClasspath += demo.output
}

tasks.test {
    dependsOn(tasks.bootJar)
    systemProperty("demo.productionJar", tasks.bootJar.get().archiveFile.get().asFile.absolutePath)
}

tasks.register<JavaExec>("m1Demo") {
    group = "application"
    description = "Runs the isolated synthetic M1 HTTP demonstration"
    classpath = demo.runtimeClasspath
    mainClass.set("com.ricezhou.vsrqg.demo.M1DemoMain")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

tasks.register<JavaExec>("m3Demo") {
    group = "application"
    description = "Runs the isolated single-device synthetic M3 HTTP demonstration"
    classpath = demo.runtimeClasspath
    mainClass.set("com.ricezhou.vsrqg.demo.M3DemoMain")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

// Cross-module fixtures are opt-in; default Backend tests never require Agent/APK/SDK.
val m3Integration by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + demo.output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + demo.output + sourceSets.test.get().output
}
configurations[m3Integration.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[m3Integration.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
tasks.register<Test>("m3IntegrationTest") {
    group = "verification"
    description = "Runs explicit CI_FIXTURE against real PostgreSQL and mTLS Backend APIs"
    testClassesDirs = m3Integration.output.classesDirs
    classpath = m3Integration.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
    // Live PostgreSQL, APK bytes and invocation Subject must produce fresh evidence.
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("External runtime fixture evidence belongs to one invocation") { true }
    doFirst {
        val invocation = System.getenv("VSRQG_M3_INVOCATION") ?: UUID.randomUUID().toString()
        require(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(invocation)) { "M3_INVOCATION_INVALID" }
        val root = layout.buildDirectory.dir("m3/fixtures/$invocation").get().asFile.toPath()
        Files.createDirectories(root.parent)
        Files.createDirectory(root)
        systemProperty("m3.artifactRoot", root.toAbsolutePath().toString())
        layout.buildDirectory.file("m3/current-invocation.txt").get().asFile.writeText(invocation)
    }
}
