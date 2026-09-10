package com.ricezhou.vsrqg.testmanagement

import com.fasterxml.jackson.databind.ObjectMapper
import com.ricezhou.vsrqg.shared.application.ResourceNotFound
import com.ricezhou.vsrqg.shared.id.UuidV7IdGenerator
import com.ricezhou.vsrqg.testmanagement.adapter.JdbcTestRunRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.*
import org.springframework.jdbc.core.simple.JdbcClient
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import javax.sql.DataSource

@Timeout(60)
class TerminalSnapshotJdbcTest {
    private val rows=mock(ResultSet::class.java)
    private val statement=mock(PreparedStatement::class.java)
    private val connection=mock(Connection::class.java)
    private val source=mock(DataSource::class.java)
    private val repository=JdbcTestRunRepository(JdbcClient.create(source),ObjectMapper(),UuidV7IdGenerator())

    init {
        `when`(source.connection).thenReturn(connection)
        `when`(connection.prepareStatement(anyString())).thenReturn(statement)
        `when`(statement.executeQuery()).thenReturn(rows)
        `when`(rows.next()).thenReturn(true,false)
        `when`(rows.getTimestamp("finished_at")).thenReturn(Timestamp.from(Instant.parse("2026-09-09T00:00:00Z")))
    }

    @Test fun `existing legacy row with SQL NULL reaches the nullable repository contract`() {
        assertThat(repository.terminalSnapshot("legacy")).isNull()
        verify(statement).setString(1,"legacy")
    }

    @Test fun `absent Run remains an explicit missing resource`() {
        `when`(rows.next()).thenReturn(false)
        assertThatThrownBy { repository.terminalSnapshot("missing") }
            .isInstanceOf(ResourceNotFound::class.java).extracting("code").isEqualTo("TEST_RESOURCE_NOT_FOUND")
    }

    @Test fun `new terminal row with SQL NULL remains an integrity failure`() {
        `when`(rows.getBoolean("snapshot_required")).thenReturn(true)
        assertThatThrownBy { repository.terminalSnapshot("new-terminal") }
            .isInstanceOf(IllegalStateException::class.java).hasMessage("Terminal Run snapshot is missing")
    }
}
