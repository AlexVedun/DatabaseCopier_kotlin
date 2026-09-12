package com.example.databasecopier.ui

import com.example.databasecopier.CopySessionTables
import com.example.databasecopier.CopySessionRoutines
import com.example.databasecopier.CopySessionViews
import com.example.databasecopier.CopySessions
import com.example.databasecopier.Connections
import com.example.databasecopier.adapter.ConnectionConfig
import com.example.databasecopier.adapter.DbType
import com.example.databasecopier.adapter.JdbcSourceAdapter
import com.example.databasecopier.adapter.JdbcTargetAdapter
import com.example.databasecopier.connection.ConnectionRepository
import com.example.databasecopier.session.CopySessionRepository
import javafx.application.Platform
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createTempFile

/**
 * Проверка Шага 6: восстановление сессии после некорректного завершения приложения.
 *
 * Сценарий: сессия скопировала первый батч и осталась в статусе "running" (эмуляция того, что
 * приложение было закрыто во время копирования — ровно то состояние, которое
 * pauseAllRunningSessions() при следующем старте переводит в "paused"). После "перезапуска"
 * (тот же вызов, что делает Main.kt при старте) сессия должна появиться в SessionsView, а по
 * клику "Продолжить" — докопироваться до конца без дублирования уже скопированных строк.
 */
@Testcontainers
class SessionsViewRestartRecoveryTest {

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> = MySQLContainer("mysql:8.0")

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")

        @BeforeAll
        @JvmStatic
        fun initJavaFxToolkit() {
            val latch = CountDownLatch(1)
            try {
                Platform.startup { latch.countDown() }
            } catch (e: IllegalStateException) {
                Platform.runLater { latch.countDown() }
            }
            latch.await()
        }
    }

    private lateinit var serviceDbFile: File
    private lateinit var sourceConfig: ConnectionConfig
    private lateinit var targetConfig: ConnectionConfig
    private val totalRows = 30
    private val batchSize = 10

    @BeforeEach
    fun setUp() {
        serviceDbFile = createTempFile("copier-restart-", ".sqlite").toFile()
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
    }

    @Test
    fun `appears in SessionsView after an interrupted run and resumes without duplicating rows`() {
        val sourceConnId = ConnectionRepository.save("mysql-source", sourceConfig)
        val targetConnId = ConnectionRepository.save("postgres-target", targetConfig)

        val sessionId = CopySessionRepository.createSession(
            name = "interrupted session",
            sourceType = "connection",
            sourceConnectionId = sourceConnId,
            sourceDumpPath = null,
            sourceDumpDialect = null,
            targetConnectionId = targetConnId,
            copyMode = "structure_and_data",
            batchSize = batchSize,
        )
        val tableId = CopySessionRepository.addTable(sessionId, "items")

        // Эмулируем: приложение скопировало структуру и первый батч, затем было закрыто без
        // явной паузы — сессия осталась в статусе "running".
        run {
            val source = JdbcSourceAdapter(sourceConfig).apply { connect() }
            val target = JdbcTargetAdapter(targetConfig).apply { connect() }
            try {
                target.createTable(source.getTableStructure("items"))
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
        CopySessionRepository.updateSessionStatus(sessionId, "running")

        // "Перезапуск приложения" — та же функция, что вызывает Main.kt при старте.
        CopySessionRepository.pauseAllRunningSessions()
        assertEquals("paused", CopySessionRepository.getSession(sessionId)!!.status)

        lateinit var sessionsView: SessionsView
        val readyLatch = CountDownLatch(1)
        Platform.runLater {
            sessionsView = SessionsView(onContinue = {}, onSkip = {})
            readyLatch.countDown()
        }
        readyLatch.await()

        val listLatch = CountDownLatch(1)
        var listed: List<com.example.databasecopier.session.CopySessionRecord> = emptyList()
        Platform.runLater {
            sessionsView.refresh()
            listed = com.example.databasecopier.session.CopySessionRepository.listResumable()
            listLatch.countDown()
        }
        listLatch.await()
        assertTrue(listed.any { it.id == sessionId && it.status == "paused" }, "session should be listed as resumable")

        lateinit var resumedView: CopyView
        val continueLatch = CountDownLatch(1)
        Platform.runLater {
            // эквивалент клика "Продолжить" в SessionsView
            resumedView = CopyView(existingSessionId = sessionId)
            continueLatch.countDown()
        }
        continueLatch.await()

        waitUntil(timeoutMs = 20_000) {
            CopySessionRepository.getSession(sessionId)?.status in setOf("completed", "failed")
        }

        val finalSession = CopySessionRepository.getSession(sessionId)!!
        val finalTable = CopySessionRepository.getTables(sessionId).first()
        assertEquals("completed", finalSession.status, "lastError=${finalSession.lastError}")
        assertEquals("done", finalTable.status)
        assertEquals(totalRows.toLong(), finalTable.rowsCopied)

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM items").use { rs ->
                    rs.next()
                    assertEquals(totalRows, rs.getInt(1))
                }
            }
        }
    }

    @Test
    fun `clicking start again after a resumed session finishes retries the same session, not the new-session flow`() {
        val sourceConnId = ConnectionRepository.save("mysql-source-2", sourceConfig)
        val targetConnId = ConnectionRepository.save("postgres-target-2", targetConfig)

        val sessionId = CopySessionRepository.createSession(
            name = "retry session",
            sourceType = "connection",
            sourceConnectionId = sourceConnId,
            sourceDumpPath = null,
            sourceDumpDialect = null,
            targetConnectionId = targetConnId,
            copyMode = "structure_and_data",
            batchSize = batchSize,
        )
        CopySessionRepository.addTable(sessionId, "items")
        CopySessionRepository.updateSessionStatus(sessionId, "paused")

        lateinit var resumedView: CopyView
        val continueLatch = CountDownLatch(1)
        Platform.runLater {
            // Как и при клике "Продолжить" в SessionsView — CopyView(existingSessionId) не создаёт
            // видимых source/target панелей, только вызывает resumeSession() в init.
            resumedView = CopyView(existingSessionId = sessionId)
            continueLatch.countDown()
        }
        continueLatch.await()

        waitUntil(timeoutMs = 20_000) {
            CopySessionRepository.getSession(sessionId)?.status in setOf("completed", "failed")
        }
        // Возвращаем сессию в "не running" статус, чтобы повторный запуск был осмысленным шагом,
        // а не просто повторной проверкой уже completed-сессии.
        CopySessionRepository.updateSessionStatus(sessionId, "paused")

        val retryLatch = CountDownLatch(1)
        Platform.runLater {
            val startButton = resumedView.progressPanel.javaClass.getDeclaredField("startButton")
                .apply { isAccessible = true }
                .get(resumedView.progressPanel) as javafx.scene.control.Button
            startButton.fire()
            retryLatch.countDown()
        }
        retryLatch.await()

        // updateSessionStatus(id, "running") в launchCopy() выполняется синхронно, до запуска
        // фоновой корутины — если бы клик "Запустить" всё ещё вызывал start() (баг: там нет
        // настроенных source/target панелей в режиме продолжения), он вернулся бы после showError()
        // с алертом "источник не настроен", не тронув статус сессии — тот остался бы "paused".
        // Проверяем "не paused", а не строго "running", т.к. копирование одной уже готовой таблицы
        // может успеть завершиться до этой проверки — важен сам факт смены статуса.
        val statusAfterRetry = CopySessionRepository.getSession(sessionId)!!.status
        assertTrue(statusAfterRetry != "paused", "expected status to change from paused, was: $statusAfterRetry")
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return@runBlocking
            delay(50)
        }
        assertTrue(condition(), "Condition not met within ${timeoutMs}ms")
    }
}
