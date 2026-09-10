package com.ricezhou.vsrqg.demo

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import javax.tools.ToolProvider

@Timeout(60)
class M3AgentClasspathTest {
    @TempDir lateinit var temp: Path

    @Test fun `production classpath loads main and dependency jars from directory with spaces`() {
        val libs = Files.createDirectory(temp.resolve("agent install lib"))
        val classes = Files.createDirectory(temp.resolve("classes"))
        val main = temp.resolve("ClasspathProbe.java")
        val dependency = temp.resolve("ProbeDependency.java")
        Files.writeString(main, """
            public class ClasspathProbe {
                public static void main(String[] args) {
                    System.out.print(ProbeDependency.message());
                }
            }
        """.trimIndent())
        Files.writeString(dependency, """
            public class ProbeDependency {
                public static String message() { return "JVM_WILDCARD_LOADED"; }
            }
        """.trimIndent())
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "JDK_COMPILER_REQUIRED" }
        assertThat(compiler.run(null, null, null, "-d", classes.toString(), main.toString(), dependency.toString())).isZero()
        listOf("ClasspathProbe", "ProbeDependency").forEach { name ->
            JarOutputStream(Files.newOutputStream(libs.resolve("$name.jar"))).use { jar ->
                jar.putNextEntry(JarEntry("$name.class"))
                Files.copy(classes.resolve("$name.class"), jar)
                jar.closeEntry()
            }
        }
        val java = Path.of(System.getProperty("java.home"), "bin",
            if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val output = temp.resolve("jvm-output.txt")
        val process = ProcessBuilder(java.toString(), "-cp", M3AgentProcess.runtimeClasspath(libs), "ClasspathProbe")
            .redirectErrorStream(true).redirectOutput(output.toFile()).start()
        try {
            assertThat(process.waitFor(10, TimeUnit.SECONDS)).describedAs("JVM exits within timeout").isTrue()
            val actual = Files.readString(output)
            assertThat(process.exitValue()).describedAs(actual).isZero()
            assertThat(actual).isEqualTo("JVM_WILDCARD_LOADED")
        } finally {
            if (process.isAlive) {
                process.destroyForcibly()
                check(process.waitFor(5, TimeUnit.SECONDS)) { "TEST_PROCESS_CLEANUP_FAILED" }
            }
        }
    }
}
