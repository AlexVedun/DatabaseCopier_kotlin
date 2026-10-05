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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
            SchemaUtils.createMissingTablesAndColumns(Connections, CopySessions, CopySessionTables, CopySessionViews, CopySessionRoutines)
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

        DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS fk_child")
                stmt.execute("DROP TABLE IF EXISTS fk_parent")
                stmt.execute("CREATE TABLE fk_parent (id INTEGER PRIMARY KEY)")
                stmt.execute(
                    "CREATE TABLE fk_child (id INTEGER PRIMARY KEY, parent_id INTEGER, " +
                        "FOREIGN KEY (parent_id) REFERENCES fk_parent(id) ON DELETE CASCADE)"
                )
                stmt.execute("INSERT INTO fk_parent (id) VALUES (1)")
                stmt.execute("INSERT INTO fk_child (id, parent_id) VALUES (100, 1)")
            }
        }
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS fk_child")
                stmt.execute("DROP TABLE IF EXISTS fk_parent")
            }
        }
    }

    @AfterEach
    fun tearDown() {
        serviceDbFile.delete()
        DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS items")
                stmt.execute("DROP TABLE IF EXISTS fk_child")
                stmt.execute("DROP TABLE IF EXISTS fk_parent")
                stmt.execute("DROP TABLE IF EXISTS flaky")
            }
        }
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS items")
                stmt.execute("DROP TABLE IF EXISTS fk_child")
                stmt.execute("DROP TABLE IF EXISTS fk_parent")
                stmt.execute("DROP TABLE IF EXISTS flaky")
            }
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
        assertEquals(totalRows.toLong(), finalTable.rowsTotal)

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
    fun `resuming after data fully copied but a later step failed does not re-copy or duplicate rows`() = runBlocking {
        // CHECK-ограничение с MySQL-специфичным REGEXP переносится на target как есть (см. Шаг 11
        // инструкции — сложные выражения не транслируются между диалектами) и упадёт на Postgres
        // (там нет оператора REGEXP), но только ПОСЛЕ того, как данные таблицы уже полностью
        // скопированы — это и воспроизводит баг: cursorJson становится null и у "данные ещё не
        // копировались", и у "данные скопированы полностью", если не различать их отдельным флагом.
        val rowCount = 20
        DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS flaky")
                stmt.execute(
                    "CREATE TABLE flaky (id INTEGER PRIMARY KEY, value VARCHAR(255) NOT NULL, " +
                        "CHECK (value REGEXP '^value-'))"
                )
                for (i in 1..rowCount) stmt.execute("INSERT INTO flaky (id, value) VALUES ($i, 'value-$i')")
            }
        }
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("DROP TABLE IF EXISTS flaky") }
        }

        val sessionId = CopySessionRepository.createSession(
            name = "flaky session",
            sourceType = "connection",
            sourceConnectionId = null,
            sourceDumpPath = null,
            sourceDumpDialect = null,
            targetConnectionId = 1,
            copyMode = "structure_and_data",
            batchSize = batchSize,
        )
        CopySessionRepository.addTable(sessionId, "flaky")
        CopySessionRepository.updateSessionStatus(sessionId, "running")

        run {
            val source = JdbcSourceAdapter(sourceConfig).apply { connect() }
            val target = JdbcTargetAdapter(targetConfig).apply { connect() }
            try {
                CopyRunner().run(sessionId, source, target)
            } finally {
                source.close()
                target.close()
            }
        }

        val afterFirstRun = CopySessionRepository.getSession(sessionId)!!
        assertEquals("failed", afterFirstRun.status)
        val tableAfterFirstRun = CopySessionRepository.getTables(sessionId).first()
        assertTrue(tableAfterFirstRun.dataCopied, "data should be fully copied despite the later CHECK failure")
        assertEquals(rowCount.toLong(), tableAfterFirstRun.rowsCopied)

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM flaky").use { rs ->
                    rs.next()
                    assertEquals(rowCount, rs.getInt(1))
                }
            }
        }

        // Повторный запуск (как при нажатии "Запустить" после failed): без dataCopied-флага это
        // упало бы с "Duplicate entry" при попытке вставить уже скопированные строки заново.
        CopySessionRepository.updateSessionStatus(sessionId, "running")
        run {
            val source = JdbcSourceAdapter(sourceConfig).apply { connect() }
            val target = JdbcTargetAdapter(targetConfig).apply { connect() }
            try {
                CopyRunner().run(sessionId, source, target)
            } finally {
                source.close()
                target.close()
            }
        }

        val afterSecondRun = CopySessionRepository.getSession(sessionId)!!
        // CHECK по-прежнему падает на том же REGEXP (это ожидаемо и не чинится этим тестом) —
        // важно, что причина падения не изменилась на дубликат ключа.
        assertEquals("failed", afterSecondRun.status)
        assertFalse(
            afterSecondRun.lastError?.contains("Duplicate", ignoreCase = true) == true,
            "lastError should not be a duplicate-key error: ${afterSecondRun.lastError}"
        )

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM flaky").use { rs ->
                    rs.next()
                    assertEquals(rowCount, rs.getInt(1), "row count must stay the same, not be re-inserted")
                }
            }
        }
    }

    @Test
    fun `creates foreign key with ON DELETE CASCADE on target after copying both tables`() = runBlocking {
        val sessionId = CopySessionRepository.createSession(
            name = "fk session",
            sourceType = "connection",
            sourceConnectionId = null,
            sourceDumpPath = null,
            sourceDumpDialect = null,
            targetConnectionId = 1,
            copyMode = "structure_and_data",
            batchSize = batchSize,
        )
        // Порядок добавления таблиц в сессию намеренно "неправильный" (child раньше parent) —
        // FK всё равно должен создаться корректно, т.к. создание FK идёт отдельным проходом
        // после того, как ВСЕ таблицы уже существуют на target.
        CopySessionRepository.addTable(sessionId, "fk_child")
        CopySessionRepository.addTable(sessionId, "fk_parent")
        CopySessionRepository.updateSessionStatus(sessionId, "running")

        val source = JdbcSourceAdapter(sourceConfig).apply { connect() }
        val target = JdbcTargetAdapter(targetConfig).apply { connect() }
        try {
            CopyRunner().run(sessionId, source, target)
        } finally {
            source.close()
            target.close()
        }

        assertEquals("completed", CopySessionRepository.getSession(sessionId)!!.status)

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("DELETE FROM fk_parent WHERE id = 1") }
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM fk_child").use { rs ->
                    rs.next()
                    // Если бы FOREIGN KEY ... ON DELETE CASCADE не был реально создан на target,
                    // строка fk_child осталась бы (или DELETE упал бы из-за отсутствия FK вообще).
                    assertEquals(0, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `marks view as requiring manual adaptation when source and target dialects differ`() = runBlocking {
        // Обратные кавычки — синтаксис квотирования идентификаторов, специфичный для MySQL и
        // синтаксически невалидный в Postgres, что гарантированно провоцирует ошибку CREATE VIEW.
        DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE VIEW items_view AS SELECT `id`, `value` FROM `items`")
            }
        }

        val sessionId = CopySessionRepository.createSession(
            name = "view session",
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

        // Провал создания одной view не должен ронять копирование остальных объектов сессии.
        assertEquals("completed", CopySessionRepository.getSession(sessionId)!!.status)
        val view = CopySessionRepository.getViews(sessionId).first()
        assertEquals("manual_adaptation_required", view.status)

        DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password).use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("DROP VIEW IF EXISTS items_view") }
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
