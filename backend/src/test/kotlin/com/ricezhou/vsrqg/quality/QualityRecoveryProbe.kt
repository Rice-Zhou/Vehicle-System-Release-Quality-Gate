package com.ricezhou.vsrqg.quality

import com.ricezhou.vsrqg.evidence.EvidenceFixture
import com.ricezhou.vsrqg.evidence.ownedTestRoot
import com.ricezhou.vsrqg.evidence.application.EvidenceInventoryItem
import com.ricezhou.vsrqg.shared.PostgresIntegrationTest.Companion.postgres
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.datasource.DriverManagerDataSource

object QualityRecoveryProbe {
    fun withRestoredDatabase(inventory: List<EvidenceInventoryItem>, verify: (JdbcClient, Path) -> Unit) {
        val restoredRoot = ownedTestRoot(Files.createTempDirectory("quality-restored-payload-"))
            .toAbsolutePath().normalize()
        val copiedFiles = inventory.map { item ->
            restoredRoot.resolve(item.uploadId + ".payload").normalize().also {
                check(it.parent == restoredRoot)
            }
        }
        val database = "quality_restore_" + UUID.randomUUID().toString().replace("-", "")
        val dump = "/tmp/$database.dump"
        var databaseCreated = false
        fun command(vararg arguments: String) {
            val result = postgres.execInContainer(*arguments)
            assertThat(result.exitCode).describedAs(result.stderr).isZero()
        }
        try {
            inventory.zip(copiedFiles).forEach { (item, target) ->
                Files.copy(EvidenceFixture.storage.resolve(item.uploadId + ".payload"), target)
            }
            command("pg_dump", "-U", postgres.username, "-d", postgres.databaseName, "-Fc", "-f", dump)
            command("createdb", "-U", postgres.username, database)
            databaseCreated = true
            command("pg_restore", "-U", postgres.username, "-d", database, dump)
            val restoredJdbc = JdbcClient.create(DriverManagerDataSource(
                postgres.jdbcUrl.substringBeforeLast('/') + "/" + database,
                postgres.username, postgres.password))
            assertThat(restoredJdbc.sql("SELECT current_database()").query(String::class.java).single())
                .isEqualTo(database).isNotEqualTo(postgres.databaseName)
            assertThat(restoredRoot).isNotEqualTo(EvidenceFixture.storage.toAbsolutePath().normalize())
            verify(restoredJdbc, restoredRoot)
        } finally {
            try {
                if (databaseCreated) command("dropdb", "-U", postgres.username, "--force", database)
            } finally {
                command("rm", "-f", "--", dump)
                copiedFiles.forEach(Files::deleteIfExists)
                Files.delete(restoredRoot)
            }
        }
    }
}
