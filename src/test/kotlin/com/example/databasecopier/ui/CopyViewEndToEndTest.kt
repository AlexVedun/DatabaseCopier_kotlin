package com.example.databasecopier.ui

import com.example.databasecopier.CopySessionTables
import com.example.databasecopier.CopySessionRoutines
import com.example.databasecopier.CopySessionViews
import com.example.databasecopier.CopySessions
import com.example.databasecopier.Connections
import com.example.databasecopier.adapter.ConnectionConfig
import com.example.databasecopier.adapter.DbType
import com.example.databasecopier.connection.ConnectionRepository
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
 * Сквозной прогон Шага 5: те же действия, что пользователь выполняет руками в UI
 * (подключиться к источнику, выбрать таблицы, подключиться к приёмнику, выбрать режим,
 * нажать "Запустить"), но вызванные программно через методы контроллеров — реальный JavaFX
 * toolkit инициализируется headless-режимом (JFXPanel), реальные MySQL/PostgreSQL через
 * Testcontainers.
 */
@Testcontainers
class CopyViewEndToEndTest {

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
                // toolkit уже запущен (например, повторный запуск тестов в том же процессе)
                Platform.runLater { latch.countDown() }
            }
            latch.await()
        }
    }

    private lateinit var serviceDbFile: File
    private val totalRows = 25

    @BeforeEach
    fun setUp() {
        serviceDbFile = createTempFile("copier-e2e-", ".sqlite").toFile()
        Database.connect("jdbc:sqlite:${serviceDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.createMissingTablesAndColumns(Connections, CopySessions, CopySessionTables, CopySessionViews, CopySessionRoutines)
        }

        DriverManager.getConnection(mysql.jdbcUrl, mysql.username, mysql.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DROP TABLE IF EXISTS people")
                stmt.execute("CREATE TABLE people (id INTEGER PRIMARY KEY, name VARCHAR(255) NOT NULL)")
                for (i in 1..totalRows) {
                    stmt.execute("INSERT INTO people (id, name) VALUES ($i, 'person-$i')")
                }
            }
        }
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("DROP TABLE IF EXISTS people") }
        }
    }

    @Test
    fun `runs a full copy through the UI controllers end to end`() {
        // Подключения теперь создаются заранее (см. ConnectionsView) и выбираются из выпадающего
        // списка, а не вводятся прямо в форме — сохраняем их напрямую через репозиторий, как это
        // делал бы диалог "Подключения" до открытия экрана копирования.
        ConnectionRepository.save(
            "mysql-source",
            ConnectionConfig(
                type = DbType.MYSQL,
                host = mysql.host,
                port = mysql.getMappedPort(3306),
                database = mysql.databaseName,
                username = mysql.username,
                password = mysql.password,
            ),
        )
        ConnectionRepository.save(
            "postgres-target",
            ConnectionConfig(
                type = DbType.POSTGRESQL,
                host = postgres.host,
                port = postgres.getMappedPort(5432),
                database = postgres.databaseName,
                username = postgres.username,
                password = postgres.password,
            ),
        )

        lateinit var view: CopyView
        val readyLatch = CountDownLatch(1)
        Platform.runLater {
            view = CopyView()
            readyLatch.countDown()
        }
        readyLatch.await()

        selectConnection(view.sourcePanel, "mysql-source")
        clickTestConnection(view.sourcePanel)
        waitUntil { view.sourcePanel.connectedProperty.get() }

        selectConnection(view.targetPanel, "postgres-target")
        clickTestConnection(view.targetPanel)
        waitUntil { view.targetPanel.connectedProperty.get() }

        runOnFx { clickStartButton(view.progressPanel) }

        waitUntil(timeoutMs = 20_000) {
            view.progressPanel.sessionId?.let { id ->
                com.example.databasecopier.session.CopySessionRepository.getSession(id)?.status in
                    setOf("completed", "failed")
            } ?: false
        }

        val sessionId = view.progressPanel.sessionId!!
        val session = com.example.databasecopier.session.CopySessionRepository.getSession(sessionId)!!
        assertEquals("completed", session.status, "lastError=${session.lastError}")

        // Для живых БД-подключений точный COUNT(*) уже известен из listTables() в момент
        // "Проверить подключение" — он должен быть сохранён в сессии как rowsTotal, чтобы работал
        // второй (по конкретной таблице) ProgressBar, а не только "N из M таблиц".
        val tableRecord = com.example.databasecopier.session.CopySessionRepository.getTables(sessionId).first()
        assertEquals(totalRows.toLong(), tableRecord.rowsTotal, "rowsTotal должен быть известен заранее для живого подключения")

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM people").use { rs ->
                    rs.next()
                    assertEquals(totalRows, rs.getInt(1))
                }
            }
        }
    }

    // --- вспомогательные функции для управления приватным полем "picker" контроллеров через
    // reflection, т.к. в тесте эмулируется выбор пользователя в реальном JavaFX ComboBox. ---

    private fun getPicker(controller: Any): ConnectionPicker {
        val field = controller.javaClass.getDeclaredField("picker")
        field.isAccessible = true
        return field.get(controller) as ConnectionPicker
    }

    private fun selectConnection(controller: Any, name: String) {
        val picker = getPicker(controller)
        runOnFx {
            picker.combo.value = picker.combo.items.find { it.name == name }
                ?: error("Connection '$name' not found among ${picker.combo.items.map { it.name }}")
        }
    }

    private fun clickTestConnection(controller: Any) {
        runOnFx { getPicker(controller).testButton.fire() }
    }

    private fun clickStartButton(progressPanel: ProgressPanelController) {
        val field = progressPanel.javaClass.getDeclaredField("startButton")
        field.isAccessible = true
        val button = field.get(progressPanel) as javafx.scene.control.Button
        button.fire()
    }

    private fun runOnFx(block: () -> Unit) {
        val latch = CountDownLatch(1)
        Platform.runLater {
            block()
            latch.countDown()
        }
        latch.await()
    }

    private fun waitUntil(timeoutMs: Long = 10_000, condition: () -> Boolean) = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return@runBlocking
            delay(50)
        }
        assertTrue(condition(), "Condition not met within ${timeoutMs}ms")
    }
}
