plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}
group = "com.ricezhou.vsrqg"
version = "0.1.0"
java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
repositories { mavenCentral() }
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.spring.boot.get()}"))
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("com.networknt:json-schema-validator:2.0.4")
    implementation("io.github.erdtman:java-json-canonicalization:1.1")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
application { mainClass.set("com.ricezhou.vsrqg.agent.AgentMainKt") }
tasks.test { useJUnitPlatform(); systemProperty("junit.jupiter.execution.timeout.default", "60 s") }
tasks.processResources {
    from(listOf("../schemas/v0.2/agent-protocol.schema.json", "../schemas/v0.2/agent-execution-context.schema.json", "../contracts/openapi/v0.2/openapi.json")) { into("contracts") }
}
tasks.processTestResources { from("../contracts/examples/v0.2/agent") { into("contracts/examples") } }


tasks.test { doFirst { systemProperty("fixture.classpath", sourceSets.test.get().runtimeClasspath.asPath) } }
