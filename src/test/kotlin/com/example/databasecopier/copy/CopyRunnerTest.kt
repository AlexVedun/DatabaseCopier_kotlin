package com.example.databasecopier.copy

import com.example.databasecopier.CopySessionTables
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.sql.DriverManager
import kotlin.io.path.createTempFile

/**
 * Источник и приёмник — заведомо разные базы данных (MySQL -> PostgreSQL) с одинаковым именем
 * таблицы "items", как это происходит при реальном копировании (CopyRunner всегда пишет в
 * target под тем же именем таблицы, под которым читает из source).
 */
@Testcontainers
class CopyRunnerTest {

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }

    private lateinit var serviceDbFile: File
    private lateinit var sourceConfig: ConnectionConfig
    private lateinit var targetConfig: ConnectionConfig
    private val totalRows = 47
    private val batchSize = 10

    @BeforeEach
    fun setUp() {
        serviceDbFile = createTempFile("copier-test-", ".sqlite").toFile()
        Database.connect("jdbc:sqlite:${serviceDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.createMissingTablesAndColumns(Connections, CopySessions, CopySessionTables)
        }

        sourceConfig = ConnectionConfig(
            type = DbType.MYSQL,
            host = mysql.host,
            port = mysql.getMappedPort(3306),
            database = mysql.databaseName,
            username = mysql.username,
            password = mysql.password,
        )
        targetConfig = ConnectionConfig(
            type = DbType.POSTGRESQL,
            host = postgres.host,
            port = postgres.getMappedPort(5432),
            database = postgres.databaseName,
            username = postgres.username,
            password = postgres.password,
        )

        DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS items")
                stmt.execute("CREATE TABLE items (id INTEGER PRIMARY KEY, value VARCHAR(255) NOT NULL)")
                for (i in 1..totalRows) {
                    stmt.execute("INSERT INTO items (id, value) VALUES ($i, 'value-$i')")
                }
            }
        }
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("DROP TABLE IF EXISTS items") }
        }
    }

    @AfterEach
    fun tearDown() {
        serviceDbFile.delete()
        DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password).use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("DROP TABLE IF EXISTS items") }
        }
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("DROP TABLE IF EXISTS items") }
        }
    }

    @Test
    fun `resumes copy from a saved cursor after a simulated pause without duplicating rows`() = runBlocking {
        val sessionId = CopySessionRepository.createSession(
            name = "test session",
            sourceType = "connection",
            sourceConnectionId = null,
            sourceDumpPath = null,
            sourceDumpDialect = null,
            targetConnectionId = 1,
            copyMode = "structure_and_data",
            batchSize = batchSize,
        )
        val tableId = CopySessionRepository.addTable(sessionId, "items")

        // Фаза 1: скопировать структуру и один батч данных "вручную" — эмуляция того, что
        // приложение было прервано (например, закрыто) после первого батча.
        run {
            val source = JdbcSourceAdapter(sourceConfig).apply { connect() }
            val target = JdbcTargetAdapter(targetConfig).apply { connect() }
            try {
                val structure = source.getTableStructure("items")
                target.createTable(structure)
                CopySessionRepository.markStructureCopied(tableId)

                val firstBatch = source.readBatch("items", null, batchSize)
                target.insertBatch("items", firstBatch.rows)
                CopySessionRepository.updateTableProgress(
                    tableId,
                    rowsCopied = firstBatch.rows.size.toLong(),
                    cursorJson = firstBatch.nextCursor?.toString(),
                )
                CopySessionRepository.updateTableStatus(tableId, "in_progress")
            } finally {
                source.close()
                target.close()
            }
        }
        CopySessionRepository.updateSessionStatus(sessionId, "paused")

        val afterPause = CopySessionRepository.getTables(sessionId).first()
        assertEquals(batchSize.toLong(), afterPause.rowsCopied)
        assertTrue(afterPause.rowsCopied < totalRows)

        // Фаза 2: пользователь нажимает "Продолжить" — статус меняется на running, CopyRunner
        // запускается заново и должен подхватить сохранённый курсор, а не начать с начала.
        CopySessionRepository.updateSessionStatus(sessionId, "running")
        val resumedSource = JdbcSourceAdapter(sourceConfig).apply { connect() }
        val resumedTarget = JdbcTargetAdapter(targetConfig).apply { connect() }
        try {
            CopyRunner().run(sessionId, resumedSource, resumedTarget)
        } finally {
            resumedSource.close()
            resumedTarget.close()
        }

        val finalSession = CopySessionRepository.getSession(sessionId)!!
        val finalTable = CopySessionRepository.getTables(sessionId).first()
        assertEquals("completed", finalSession.status)
        assertEquals("done", finalTable.status)
        assertEquals(totalRows.toLong(), finalTable.rowsCopied)

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM items").use { rs ->
                    rs.next()
                    // PRIMARY KEY на items.id гарантирует: если бы строки первого батча были
                    // скопированы повторно, вставка упала бы с ошибкой дубликата ключа.
                    assertEquals(totalRows, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `does not proceed when session status is paused before starting`() = runBlocking {
        val sessionId = CopySessionRepository.createSession(
            name = "paused session",
            sourceType = "connection",
            sourceConnectionId = null,
            sourceDumpPath = null,
            sourceDumpDialect = null,
            targetConnectionId = 1,
            copyMode = "structure_and_data",
            batchSize = batchSize,
        )
        CopySessionRepository.addTable(sessionId, "items")
        CopySessionRepository.updateSessionStatus(sessionId, "paused")

        val source = JdbcSourceAdapter(sourceConfig).apply { connect() }
        val target = JdbcTargetAdapter(targetConfig).apply { connect() }
        try {
            CopyRunner().run(sessionId, source, target)
        } finally {
            source.close()
            target.close()
        }

        val table = CopySessionRepository.getTables(sessionId).first()
        assertEquals("pending", table.status)
        assertEquals(0L, table.rowsCopied)
        assertEquals("paused", CopySessionRepository.getSession(sessionId)!!.status)
    }
}
