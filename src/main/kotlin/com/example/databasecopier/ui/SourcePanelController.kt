package com.example.databasecopier.ui

import com.example.databasecopier.AppScope
import com.example.databasecopier.adapter.ConnectionConfig
import com.example.databasecopier.adapter.JdbcSourceAdapter
import com.example.databasecopier.connection.ConnectionRepository
import javafx.beans.property.SimpleBooleanProperty
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.cell.CheckBoxTableCell
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Раздел "Источник копирования": подключение к живой БД (MySQL/PostgreSQL) + выбор таблиц. */
class SourcePanelController {

    private val form = ConnectionForm()

    private val tablesTable = TableView<TableSelection>().apply {
        isEditable = true
        val selectedColumn = TableColumn<TableSelection, Boolean>("").apply {
            cellValueFactory = javafx.util.Callback { it.value.selectedProperty }
            cellFactory = CheckBoxTableCell.forTableColumn(this)
            isEditable = true
            prefWidth = 40.0
        }
        val nameColumn = TableColumn<TableSelection, String>("Таблица").apply {
            cellValueFactory = javafx.util.Callback { it.value.nameProperty }
            prefWidth = 260.0
        }
        columns.addAll(selectedColumn, nameColumn)
    }

    private val selectAllButton = Button("Выбрать все").apply {
        setOnAction { tablesTable.items.forEach { it.selectedProperty.set(true) } }
    }
    private val selectNoneButton = Button("Снять все").apply {
        setOnAction { tablesTable.items.forEach { it.selectedProperty.set(false) } }
    }

    var connectionConfig: ConnectionConfig? = null
        private set
    var connectionId: Int? = null
        private set

    val connectedProperty = SimpleBooleanProperty(false)

    val view = VBox(
        8.0,
        Label("Источник копирования"),
        form.grid,
        HBox(8.0, selectAllButton, selectNoneButton),
        tablesTable,
    ).apply { padding = Insets(8.0) }

    init {
        form.testButton.setOnAction { testConnection() }
    }

    fun selectedTables(): List<String> = tablesTable.items.filter { it.isSelected }.map { it.name }

    private fun testConnection() {
        val config = ConnectionConfig(
            type = form.dbTypeCombo.value,
            host = form.hostField.text,
            port = form.portOrNull(),
            database = form.databaseField.text,
            username = form.usernameField.text,
            password = form.passwordField.text,
        )
        form.testButton.isDisable = true
        form.statusLabel.text = "Проверка..."

        AppScope.scope.launch {
            val adapter = JdbcSourceAdapter(config)
            try {
                adapter.connect()
                val tables = adapter.listTables()
                val savedId = ConnectionRepository.save("${config.host}:${config.database}", config)
                withContext(Dispatchers.Main) {
                    connectionConfig = config
                    connectionId = savedId
                    tablesTable.items.setAll(tables.keys.sorted().map { TableSelection(it, true) })
                    form.statusLabel.text = "Подключено. Таблиц: ${tables.size}"
                    connectedProperty.set(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    connectionConfig = null
                    connectionId = null
                    connectedProperty.set(false)
                    form.statusLabel.text = "Ошибка: ${e.message}"
                }
            } finally {
                adapter.close()
                withContext(Dispatchers.Main) { form.testButton.isDisable = false }
            }
        }
    }
}
