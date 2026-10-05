package com.example.databasecopier.ui

import com.example.databasecopier.Connections
import com.example.databasecopier.adapter.ConnectionConfig
import com.example.databasecopier.adapter.DbType
import com.example.databasecopier.connection.ConnectionRepository
import com.example.databasecopier.connection.ConnectionRecord
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.control.ListView
import javafx.scene.layout.VBox
import javafx.stage.Stage
import javafx.stage.Window
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import kotlin.io.path.createTempFile

class ConnectionPickerTest {
    companion object {
        @BeforeAll
        @JvmStatic
        fun initJavaFxToolkit() {
            val latch = CountDownLatch(1)
            try {
                Platform.startup { latch.countDown() }
            } catch (_: IllegalStateException) {
                Platform.runLater { latch.countDown() }
            }
            latch.await()
        }
    }

    private val config = ConnectionConfig(DbType.MYSQL, "localhost", 3306, "test", "root", null)

    @BeforeEach
    fun setUp() {
        val file = createTempFile("connection-picker-", ".sqlite")
        Database.connect("jdbc:sqlite:$file", driver = "org.sqlite.JDBC")
        transaction { SchemaUtils.create(Connections) }
    }

    @Test
    fun `refresh keeps displayed value and highlighted selection on the same connection`() {
        val firstId = ConnectionRepository.save("first", config)
        val selectedId = ConnectionRepository.save("selected", config)
        ConnectionRepository.save("third", config)

        onFx {
            Platform.setImplicitExit(false)
            val picker = ConnectionPicker()
            val stage = Stage().apply {
                scene = Scene(VBox(picker.combo))
                show()
            }
            try {
                picker.combo.selectionModel.select(picker.combo.items.indexOfFirst { it.id == selectedId })
                val notifications = mutableListOf<DbType?>()
                picker.onSelectionChanged { notifications += it }

                // Editing a connection changes list positions while the dialog is open.
                ConnectionRepository.update(firstId, "zzz-last", config)
                picker.refresh()
                assertEquals(selectedId, picker.combo.value?.id)
                assertEquals(selectedId, picker.combo.selectionModel.selectedItem?.id)
                assertEquals(picker.combo.value, picker.combo.selectionModel.selectedItem)
                assertEquals(picker.combo.items.indexOfFirst { it.id == selectedId }, picker.combo.selectionModel.selectedIndex)
                assertEquals(emptyList<DbType?>(), notifications)
                picker.combo.show()
                val popup = Window.getWindows().first { it !== stage && it.isShowing }
                val list = popup.scene.root.lookup(".list-view") as ListView<*>
                assertEquals(selectedId, (list.selectionModel.selectedItem as ConnectionRecord).id)
                picker.combo.hide()

                ConnectionRepository.delete(selectedId)
                picker.refresh()
                val fallbackId = picker.combo.items.first().id
                assertEquals(fallbackId, picker.combo.value?.id)
                assertEquals(listOf(DbType.MYSQL), notifications)
                picker.combo.show()
                assertEquals(fallbackId, (list.selectionModel.selectedItem as ConnectionRecord).id)
                picker.combo.hide()
            } finally {
                stage.close()
            }
        }
    }

    private fun onFx(block: () -> Unit) = runBlocking {
        val completed = CompletableDeferred<Unit>()
        Platform.runLater {
            try {
                block()
                completed.complete(Unit)
            } catch (e: Throwable) {
                completed.completeExceptionally(e)
            }
        }
        completed.await()
    }
}
