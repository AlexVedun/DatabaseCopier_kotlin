package com.example.databasecopier.ui

import com.example.databasecopier.AppScope
import com.example.databasecopier.adapter.ConnectionConfig
import com.example.databasecopier.adapter.JdbcSourceAdapter
import com.example.databasecopier.adapter.SourceAdapter
import com.example.databasecopier.connection.ConnectionRepository
import com.example.databasecopier.dump.DumpDialect
import com.example.databasecopier.dump.MysqlDumpSourceAdapter
import com.example.databasecopier.dump.PostgresDumpSourceAdapter
import javafx.beans.property.SimpleBooleanProperty
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.control.ToggleGroup
import javafx.scene.control.cell.CheckBoxTableCell
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Раздел "Источник копирования": либо живая БД (MySQL/PostgreSQL), либо файл SQL-дампа —
 * выбор через переключатель, оба режима заканчиваются одним и тем же списком таблиц с чекбоксами.
 */
class SourcePanelController {

    private val form = ConnectionForm()

    private val modeGroup = ToggleGroup()
    private val connectionModeRadio = RadioButton("Подключение к БД").apply {
        toggleGroup = modeGroup
        isSelected = true
    }
    private val dumpModeRadio = RadioButton("SQL-дамп").apply { toggleGroup = modeGroup }

    private val dialectCombo = ComboBox<DumpDialect>().apply {
        items.addAll(DumpDialect.MYSQL, DumpDialect.POSTGRESQL)
        value = DumpDialect.MYSQL
    }
    private var selectedDumpFile: File? = null
    private val selectedFileLabel = Label("Файл не выбран")
    private val chooseFileButton = Button("Выбрать файл дампа...").apply {
        setOnAction { chooseDumpFile() }
    }
    private val loadDumpButton = Button("Загрузить дамп").apply {
        setOnAction { loadDump() }
    }
    private val dumpStatusLabel = Label()

    private val dumpBox = VBox(
        8.0,
        HBox(8.0, Label("Диалект дампа:"), dialectCombo),
        HBox(8.0, chooseFileButton, selectedFileLabel),
        HBox(8.0, loadDumpButton, dumpStatusLabel),
    ).apply { isVisible = false; isManaged = false }

    private val connectionBox = VBox(form.grid)

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

    private var selection: SourceSelection? = null

    val connectedProperty = SimpleBooleanProperty(false)

    val view = VBox(
        8.0,
        Label("Источник копирования"),
        HBox(16.0, connectionModeRadio, dumpModeRadio),
        connectionBox,
        dumpBox,
        HBox(8.0, selectAllButton, selectNoneButton),
        tablesTable,
    ).apply { padding = Insets(8.0) }

    init {
        form.testButton.setOnAction { testConnection() }
        modeGroup.selectedToggleProperty().addListener { _, _, newToggle ->
            val isDump = newToggle == dumpModeRadio
            connectionBox.isVisible = !isDump
            connectionBox.isManaged = !isDump
            dumpBox.isVisible = isDump
            dumpBox.isManaged = isDump
            connectedProperty.set(false)
            selection = null
            tablesTable.items.clear()
        }
    }

    fun selectedTables(): List<String> = tablesTable.items.filter { it.isSelected }.map { it.name }

    fun currentSelection(): SourceSelection? = selection

    /** Создаёт новый экземпляр адаптера для реального копирования (тестовый уже закрыт после проверки). */
    fun createAdapter(): SourceAdapter = when (val sel = selection) {
        is SourceSelection.Connection -> JdbcSourceAdapter(sel.config)
        is SourceSelection.Dump -> when (sel.dialect) {
            DumpDialect.MYSQL -> MysqlDumpSourceAdapter(sel.file)
            DumpDialect.POSTGRESQL -> PostgresDumpSourceAdapter(sel.file)
        }
        null -> error("Источник не настроен")
    }

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
                    selection = SourceSelection.Connection(config, savedId)
                    tablesTable.items.setAll(tables.keys.sorted().map { TableSelection(it, true) })
                    form.statusLabel.text = "Подключено. Таблиц: ${tables.size}"
                    connectedProperty.set(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    selection = null
                    connectedProperty.set(false)
                    form.statusLabel.text = "Ошибка: ${e.message}"
                }
            } finally {
                adapter.close()
                withContext(Dispatchers.Main) { form.testButton.isDisable = false }
            }
        }
    }

    private fun chooseDumpFile() {
        val chooser = FileChooser().apply {
            title = "Выбрать файл SQL-дампа"
            extensionFilters.add(FileChooser.ExtensionFilter("SQL dump", "*.sql"))
        }
        val window = chooseFileButton.scene?.window
        val file = chooser.showOpenDialog(window) ?: return
        selectedDumpFile = file
        selectedFileLabel.text = file.absolutePath
    }

    private fun loadDump() {
        val file = selectedDumpFile
        if (file == null) {
            dumpStatusLabel.text = "Сначала выберите файл"
            return
        }
        val dialect = dialectCombo.value
        loadDumpButton.isDisable = true
        dumpStatusLabel.text = "Разбор дампа..."

        AppScope.scope.launch {
            val adapter = when (dialect) {
                DumpDialect.MYSQL -> MysqlDumpSourceAdapter(file)
                DumpDialect.POSTGRESQL -> PostgresDumpSourceAdapter(file)
            }
            try {
                adapter.connect()
                val tables = adapter.listTables()
                withContext(Dispatchers.Main) {
                    selection = SourceSelection.Dump(file, dialect)
                    tablesTable.items.setAll(tables.keys.sorted().map { TableSelection(it, true) })
                    dumpStatusLabel.text = "Разобрано. Таблиц: ${tables.size}"
                    connectedProperty.set(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    selection = null
                    connectedProperty.set(false)
                    dumpStatusLabel.text = "Ошибка: ${e.message}"
                }
            } finally {
                adapter.close()
                withContext(Dispatchers.Main) { loadDumpButton.isDisable = false }
            }
        }
    }
}
