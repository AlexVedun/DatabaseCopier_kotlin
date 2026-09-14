package com.example.databasecopier.ui

import com.example.databasecopier.connection.ConnectionRecord
import com.example.databasecopier.connection.ConnectionRepository
import com.example.databasecopier.i18n.Messages
import javafx.beans.property.SimpleStringProperty
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonType
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window
import javafx.util.Callback

/**
 * Модальное окно управления сохранёнными подключениями: создание, изменение, копирование
 * (интерфейс копии — тот же диалог редактирования, но с пустым названием) и удаление. Открывается
 * из главного меню ("Файл" -> "Подключения", см. Main.kt); после закрытия вызывающая сторона сама
 * обновляет выпадающие списки подключений на экране копирования (список мог измениться).
 */
class ConnectionsView(owner: Window?) {

    private val table = TableView<ConnectionRecord>().apply {
        columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY
        prefWidth = 640.0
        prefHeight = 320.0
        val nameColumn = TableColumn<ConnectionRecord, String>(Messages.get("connections.column.name")).apply {
            cellValueFactory = Callback { SimpleStringProperty(it.value.name) }
            prefWidth = 160.0
        }
        val typeColumn = TableColumn<ConnectionRecord, String>(Messages.get("connections.column.type")).apply {
            cellValueFactory = Callback { SimpleStringProperty(it.value.config.type.name.lowercase()) }
            prefWidth = 90.0
        }
        val hostColumn = TableColumn<ConnectionRecord, String>("Host").apply {
            cellValueFactory = Callback { SimpleStringProperty(it.value.config.host ?: "") }
            prefWidth = 140.0
        }
        val databaseColumn = TableColumn<ConnectionRecord, String>("Database").apply {
            cellValueFactory = Callback { SimpleStringProperty(it.value.config.database) }
            prefWidth = 160.0
        }
        val usernameColumn = TableColumn<ConnectionRecord, String>("Username").apply {
            cellValueFactory = Callback { SimpleStringProperty(it.value.config.username ?: "") }
            prefWidth = 120.0
        }
        columns.addAll(nameColumn, typeColumn, hostColumn, databaseColumn, usernameColumn)
    }

    private val createButton = Button(Messages.get("connections.create")).apply { setOnAction { openCreateDialog() } }
    private val editButton = Button(Messages.get("connections.edit")).apply { isDisable = true; setOnAction { openEditDialog() } }
    private val duplicateButton = Button(Messages.get("connections.duplicate")).apply { isDisable = true; setOnAction { openDuplicateDialog() } }
    private val deleteButton = Button(Messages.get("connections.delete")).apply { isDisable = true; setOnAction { deleteSelected() } }

    private val stage = Stage().apply {
        title = Messages.get("connections.title")
        initModality(Modality.WINDOW_MODAL)
        if (owner != null) initOwner(owner)
    }

    init {
        table.selectionModel.selectedItemProperty().addListener { _, _, selected ->
            val hasSelection = selected != null
            editButton.isDisable = !hasSelection
            duplicateButton.isDisable = !hasSelection
            deleteButton.isDisable = !hasSelection
        }
        refresh()

        val root = VBox(
            8.0,
            table,
            HBox(8.0, createButton, editButton, duplicateButton, deleteButton),
        ).apply { padding = Insets(8.0) }
        stage.scene = Scene(root)
    }

    fun showAndWait() = stage.showAndWait()

    private fun refresh() {
        val selectedId = table.selectionModel.selectedItem?.id
        table.items.setAll(ConnectionRepository.list())
        table.selectionModel.select(table.items.find { it.id == selectedId })
    }

    private fun openCreateDialog() {
        ConnectionEditDialog(stage, Messages.get("connections.dialog.new"), "", null, excludingId = null) { name, config ->
            ConnectionRepository.save(name, config)
            refresh()
        }.showAndWait()
    }

    private fun openEditDialog() {
        val record = table.selectionModel.selectedItem ?: return
        ConnectionEditDialog(stage, Messages.get("connections.dialog.edit"), record.name, record.config, excludingId = record.id) { name, config ->
            ConnectionRepository.update(record.id, name, config)
            refresh()
        }.showAndWait()
    }

    private fun openDuplicateDialog() {
        val record = table.selectionModel.selectedItem ?: return
        ConnectionEditDialog(stage, Messages.get("connections.dialog.duplicate", record.name), "", record.config, excludingId = null) { name, config ->
            ConnectionRepository.save(name, config)
            refresh()
        }.showAndWait()
    }

    private fun deleteSelected() {
        val record = table.selectionModel.selectedItem ?: return
        val usageCount = ConnectionRepository.sessionsUsingCount(record.id)
        val message = if (usageCount > 0) {
            Messages.get("connections.delete.confirmUsed", record.name, usageCount)
        } else {
            Messages.get("connections.delete.confirm", record.name)
        }
        val alert = Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.YES, ButtonType.NO)
        alert.title = Messages.get("connections.delete.title")
        alert.headerText = null
        alert.showAndWait().ifPresent { button ->
            if (button == ButtonType.YES) {
                ConnectionRepository.delete(record.id)
                refresh()
            }
        }
    }
}
