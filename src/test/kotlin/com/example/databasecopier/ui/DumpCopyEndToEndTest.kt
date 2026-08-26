package com.example.databasecopier.ui

import com.example.databasecopier.CopySessionTables
import com.example.databasecopier.CopySessionViews
import com.example.databasecopier.CopySessions
import com.example.databasecopier.Connections
import com.example.databasecopier.dump.DumpDialect
import javafx.application.Platform
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
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
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.sql.DriverManager
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText

/**
 * Сквозной прогон Шага 7: источник — файл SQL-дампа (MySQL-диалект), приёмник — живая PostgreSQL.
 * Действия пользователя (переключение на режим "SQL-дамп", выбор файла, "Загрузить дамп",
 * "Запустить") эмулируются вызовом тех же обработчиков, что стоят за кнопками контроллеров.
 */
@Testcontainers
class DumpCopyEndToEndTest {

    companion object {
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
    private lateinit var dumpFile: File
    private val totalRows = 20

    @BeforeEach
    fun setUp() {
        serviceDbFile = createTempFile("copier-dump-e2e-", ".sqlite").toFile()
        Database.connect("jdbc:sqlite:${serviceDbFile.absolutePath}", driver = "org.sqlite.JDBC")
        transaction {
            SchemaUtils.createMissingTablesAndColumns(Connections, CopySessions, CopySessionTables, CopySessionViews)
        }

        val values = (1..totalRows).joinToString(",") { "($it,'person-$it')" }
        val sql = """
            CREATE TABLE `people` (
              `id` int(11) NOT NULL,
              `name` varchar(255) NOT NULL,
              PRIMARY KEY (`id`)
            );
            INSERT INTO `people` (`id`, `name`) VALUES $values;
        """.trimIndent()
        dumpFile = createTempFile("dump-e2e-", ".sql").toFile()
        dumpFile.toPath().writeText(sql)

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt -> stmt.execute("DROP TABLE IF EXISTS people") }
        }
    }

    @Test
    fun `copies from a MySQL-dialect dump file into PostgreSQL through the UI controllers`() {
        lateinit var view: CopyView
        val readyLatch = CountDownLatch(1)
        Platform.runLater {
            view = CopyView()
            readyLatch.countDown()
        }
        readyLatch.await()

        runOnFx {
            getPrivateField<javafx.scene.control.RadioButton>(view.sourcePanel, "dumpModeRadio").isSelected = true
            setField(view.sourcePanel, "selectedDumpFile", dumpFile)
            getPrivateField<ComboBox<DumpDialect>>(view.sourcePanel, "dialectCombo").value = DumpDialect.MYSQL
        }
        runOnFx { getPrivateField<Button>(view.sourcePanel, "loadDumpButton").fire() }
        waitUntil { view.sourcePanel.connectedProperty.get() }

        fillConnectionForm(
            form = getPrivateField(view.targetPanel, "form"),
            type = com.example.databasecopier.adapter.DbType.POSTGRESQL,
            host = postgres.host,
            port = postgres.getMappedPort(5432),
            database = postgres.databaseName,
            username = postgres.username,
            password = postgres.password,
        )
        runOnFx { getPrivateField<ConnectionForm>(view.targetPanel, "form").testButton.fire() }
        waitUntil { view.targetPanel.connectedProperty.get() }

        runOnFx { getPrivateField<Button>(view.progressPanel, "startButton").fire() }

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
            form.hostField.value = host
            form.portField.value = port.toString()
            form.databaseField.value = database
            form.usernameField.value = username
            form.passwordField.text = password
        }
    }

    private fun <T> getPrivateField(target: Any, name: String): T {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(target) as T
    }

    private fun setField(target: Any, name: String, value: Any?) {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
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
