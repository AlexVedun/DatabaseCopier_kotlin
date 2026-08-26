package com.example.databasecopier.ui

import com.example.databasecopier.CopySessionTables
import com.example.databasecopier.CopySessionViews
import com.example.databasecopier.CopySessions
import com.example.databasecopier.Connections
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
            SchemaUtils.createMissingTablesAndColumns(Connections, CopySessions, CopySessionTables, CopySessionViews)
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
        lateinit var view: CopyView
        val readyLatch = CountDownLatch(1)
        Platform.runLater {
            view = CopyView()
            readyLatch.countDown()
        }
        readyLatch.await()

        fillConnectionForm(
            form = getForm(view.sourcePanel),
            type = com.example.databasecopier.adapter.DbType.MYSQL,
            host = mysql.host,
            port = mysql.getMappedPort(3306),
            database = mysql.databaseName,
            username = mysql.username,
            password = mysql.password,
        )
        clickTestConnection(getForm(view.sourcePanel))
        waitUntil { view.sourcePanel.connectedProperty.get() }

        fillConnectionForm(
            form = getForm(view.targetPanel),
            type = com.example.databasecopier.adapter.DbType.POSTGRESQL,
            host = postgres.host,
            port = postgres.getMappedPort(5432),
            database = postgres.databaseName,
            username = postgres.username,
            password = postgres.password,
        )
        clickTestConnection(getForm(view.targetPanel))
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

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM people").use { rs ->
                    rs.next()
                    assertEquals(totalRows, rs.getInt(1))
                }
            }
        }
    }

    // --- вспомогательные функции для управления приватными полями контроллеров через reflection,
    // т.к. в тесте эмулируется ввод пользователя в реальные JavaFX-поля формы. ---

    private fun getForm(controller: Any): ConnectionForm {
        val field = controller.javaClass.getDeclaredField("form")
        field.isAccessible = true
        return field.get(controller) as ConnectionForm
    }

    private fun fillConnectionForm(
        form: ConnectionForm,
        type: com.example.databasecopier.adapter.DbType,
        host: String,
        port: Int,
        database: String,
        username: String,
        password: String,
    ) {
        runOnFx {
            form.dbTypeCombo.value = type
            form.hostField.text = host
            form.portField.text = port.toString()
            form.databaseField.text = database
            form.usernameField.text = username
            form.passwordField.text = password
        }
    }

    private fun clickTestConnection(form: ConnectionForm) {
        runOnFx { form.testButton.fire() }
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
