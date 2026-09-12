package com.example.databasecopier.copy

import com.example.databasecopier.CopySessionTables
import com.example.databasecopier.CopySessionRoutines
import com.example.databasecopier.CopySessionViews
import com.example.databasecopier.CopySessions
import com.example.databasecopier.Connections
import com.example.databasecopier.adapter.ConnectionConfig
import com.example.databasecopier.adapter.DbType
import com.example.databasecopier.adapter.JdbcSourceAdapter
import com.example.databasecopier.adapter.JdbcTargetAdapter
import com.example.databasecopier.session.CopySessionRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.sql.DriverManager
import kotlin.io.path.createTempFile

/** Шаг 8: SQLite как источник и как приёмник — без Testcontainers, просто два локальных файла. */
class SqliteCopyRunnerTest {

    private lateinit var serviceDbFile: File
    private lateinit var sourceConfig: ConnectionConfig
    private lateinit var targetConfig: ConnectionConfig
    private val totalRows = 40
    private val batchSize = 7

    private fun tempSqliteFile(prefix: String): File =
        createTempFile(prefix, ".sqlite").toFile().apply { delete(); deleteOnExit() }

    @BeforeEach
    fun setUp() {
        serviceDbFile = tempSqliteFile("copier-sqlite-service-")
        Database.connect("jdbc:sqlite:${serviceDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.createMissingTablesAndColumns(Connections, CopySessions, CopySessionTables, CopySessionViews, CopySessionRoutines)
        }

        val sourceFile = tempSqliteFile("copier-sqlite-source-")
        val targetFile = tempSqliteFile("copier-sqlite-target-")
        sourceConfig = ConnectionConfig(type = DbType.SQLITE, database = sourceFile.absolutePath)
        targetConfig = ConnectionConfig(type = DbType.SQLITE, database = targetFile.absolutePath)

        DriverManager.getConnection("jdbc:sqlite:${sourceFile.absolutePath}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                for (i in 1..totalRows) {
                    stmt.execute("INSERT INTO items (id, value) VALUES ($i, 'value-$i')")
                }
            }
        }
    }

    @Test
    fun `copies all rows from one SQLite file to another`() = runBlocking {
        val sessionId = CopySessionRepository.createSession(
            name = "sqlite to sqlite",
            sourceType = "connection",
            sourceConnectionId = null,
            sourceDumpPath = null,
            sourceDumpDialect = null,
            targetConnectionId = 1,
            copyMode = "structure_and_data",
            batchSize = batchSize,
        )
        CopySessionRepository.addTable(sessionId, "items")
        CopySessionRepository.updateSessionStatus(sessionId, "running")

        val source = JdbcSourceAdapter(sourceConfig).apply { connect() }
        val target = JdbcTargetAdapter(targetConfig).apply { connect() }
        try {
            CopyRunner().run(sessionId, source, target)
        } finally {
            source.close()
            target.close()
        }

        val session = CopySessionRepository.getSession(sessionId)!!
        val table = CopySessionRepository.getTables(sessionId).first()
        assertEquals("completed", session.status, "lastError=${session.lastError}")
        assertEquals("done", table.status)
        assertEquals(totalRows.toLong(), table.rowsCopied)

        DriverManager.getConnection("jdbc:sqlite:${targetConfig.database}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM items").use { rs ->
                    rs.next()
                    assertEquals(totalRows, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `copies a selected view when source and target dialects match`() = runBlocking {
        DriverManager.getConnection("jdbc:sqlite:${sourceConfig.database}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE VIEW items_view AS SELECT id, value FROM items WHERE id <= 5")
            }
        }

        val sessionId = CopySessionRepository.createSession(
            name = "sqlite to sqlite with view",
            sourceType = "connection",
            sourceConnectionId = null,
            sourceDumpPath = null,
            sourceDumpDialect = null,
            targetConnectionId = 1,
            copyMode = "structure_and_data",
            batchSize = batchSize,
        )
        CopySessionRepository.addTable(sessionId, "items")
        CopySessionRepository.addView(sessionId, "items_view", isSelected = true)
        CopySessionRepository.updateSessionStatus(sessionId, "running")

        val source = JdbcSourceAdapter(sourceConfig).apply { connect() }
        val target = JdbcTargetAdapter(targetConfig).apply { connect() }
        try {
            CopyRunner().run(sessionId, source, target)
        } finally {
            source.close()
            target.close()
        }

        val session = CopySessionRepository.getSession(sessionId)!!
        assertEquals("completed", session.status, "lastError=${session.lastError}")
        val view = CopySessionRepository.getViews(sessionId).first()
        assertEquals("done", view.status)

        DriverManager.getConnection("jdbc:sqlite:${targetConfig.database}").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM items_view").use { rs ->
                    rs.next()
                    assertEquals(5, rs.getInt(1))
                }
            }
        }
    }
}
