package com.example.databasecopier.ui

import com.example.databasecopier.AppScope
import com.example.databasecopier.adapter.DbType
import com.example.databasecopier.adapter.JdbcSourceAdapter
import com.example.databasecopier.adapter.RoutineRef
import com.example.databasecopier.adapter.SourceAdapter
import com.example.databasecopier.dump.DumpDialect
import com.example.databasecopier.i18n.Messages
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
import javafx.scene.layout.Priority
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

    private val picker = ConnectionPicker()

    private val modeGroup = ToggleGroup()
    private val connectionModeRadio = RadioButton(Messages.get("source.mode.connection")).apply {
        toggleGroup = modeGroup
        isSelected = true
    }
    private val dumpModeRadio = RadioButton(Messages.get("source.mode.dump")).apply { toggleGroup = modeGroup }

    private val dialectCombo = ComboBox<DumpDialect>().apply {
        items.addAll(DumpDialect.MYSQL, DumpDialect.POSTGRESQL)
        value = DumpDialect.MYSQL
    }
    private var selectedDumpFile: File? = null
    private val selectedFileLabel = Label(Messages.get("source.dump.noFileChosen"))
    private val chooseFileButton = Button(Messages.get("source.dump.chooseFile")).apply {
        setOnAction { chooseDumpFile() }
    }
    private val loadDumpButton = Button(Messages.get("source.dump.load")).apply {
        setOnAction { loadDump() }
    }
    private val dumpStatusLabel = Label()

    private val dumpBox = VBox(
        8.0,
        HBox(8.0, Label(Messages.get("source.dump.dialect")), dialectCombo),
        HBox(8.0, chooseFileButton, selectedFileLabel),
        HBox(8.0, loadDumpButton, dumpStatusLabel),
    ).apply { isVisible = false; isManaged = false }

    private val connectionBox = VBox(picker.row)

    private val tablesTable = TableView<TableSelection>().apply {
        isEditable = true
        prefHeight = 100.0
        maxWidth = Double.MAX_VALUE
        // CONSTRAINED_RESIZE_POLICY растягивает колонки на всю ширину TableView, а не только
        // саму таблицу на всю ширину окна — иначе справа от "Таблица" осталась бы пустая полоса.
        columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY
        val selectedColumn = TableColumn<TableSelection, Boolean>("").apply {
            cellValueFactory = javafx.util.Callback { it.value.selectedProperty }
            cellFactory = CheckBoxTableCell.forTableColumn(this)
            isEditable = true
            prefWidth = 40.0
            maxWidth = 40.0
        }
        val nameColumn = TableColumn<TableSelection, String>(Messages.get("source.column.table")).apply {
            cellValueFactory = javafx.util.Callback { it.value.nameProperty }
            prefWidth = 260.0
        }
        columns.addAll(selectedColumn, nameColumn)
    }

    private val selectAllButton = Button(Messages.get("source.selectAll")).apply {
        setOnAction { tablesTable.items.forEach { it.selectedProperty.set(true) } }
    }
    private val selectNoneButton = Button(Messages.get("source.selectNone")).apply {
        setOnAction { tablesTable.items.forEach { it.selectedProperty.set(false) } }
    }

    // Views — необязательная категория, по умолчанию все сняты (см. Шаг 13 инструкции); список
    // может быть пуст, если в источнике нет представлений — блок тогда просто ничего не показывает.
    private val viewsTable = TableView<TableSelection>().apply {
        isEditable = true
        prefHeight = 100.0
        maxWidth = Double.MAX_VALUE
        columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY
        val selectedColumn = TableColumn<TableSelection, Boolean>("").apply {
            cellValueFactory = javafx.util.Callback { it.value.selectedProperty }
            cellFactory = CheckBoxTableCell.forTableColumn(this)
            isEditable = true
            prefWidth = 40.0
            maxWidth = 40.0
        }
        val nameColumn = TableColumn<TableSelection, String>(Messages.get("source.column.view")).apply {
            cellValueFactory = javafx.util.Callback { it.value.nameProperty }
            prefWidth = 260.0
        }
        columns.addAll(selectedColumn, nameColumn)
    }

    // Хранимые процедуры (и для MySQL — также функции, см. RoutineKind) — по решению пользователя
    // копируются только между источником и приёмником одного типа БД (синтаксис тела процедуры
    // почти никогда не портируется между диалектами даже частично, в отличие от view, где хотя бы
    // иногда можно попробовать и откатиться). Список поэтому не просто пуст, а явно недоступен
    // (через placeholder, см. refreshRoutinesVisibility), когда типы не совпадают — targetDbType
    // передаётся снаружи (CopyView), т.к. TargetPanelController — независимая, отдельная панель.
    private val routinesTable = TableView<RoutineSelection>().apply {
        isEditable = true
        prefHeight = 100.0
        maxWidth = Double.MAX_VALUE
        columnResizePolicy = TableView.CONSTRAINED_RESIZE_POLICY
        val selectedColumn = TableColumn<RoutineSelection, Boolean>("").apply {
            cellValueFactory = javafx.util.Callback { it.value.selectedProperty }
            cellFactory = CheckBoxTableCell.forTableColumn(this)
            isEditable = true
            prefWidth = 40.0
            maxWidth = 40.0
        }
        val nameColumn = TableColumn<RoutineSelection, String>(Messages.get("source.routines.column.name")).apply {
            cellValueFactory = javafx.util.Callback { it.value.nameProperty }
            prefWidth = 200.0
        }
        val kindColumn = TableColumn<RoutineSelection, String>(Messages.get("source.routines.column.kind")).apply {
            cellValueFactory = javafx.util.Callback { it.value.kindProperty }
            prefWidth = 90.0
        }
        columns.addAll(selectedColumn, nameColumn, kindColumn)
    }

    private var selection: SourceSelection? = null
    // Кол-во строк на таблицу, как его отдал SourceAdapter.listTables() (точный COUNT(*) для живых
    // БД, дешёвая оценка для дампов — см. DumpIndexer) — сохраняется, чтобы передать в сессию как
    // rowsTotal при старте копирования, не пересчитывая ещё раз.
    private var lastRowCounts: Map<String, Long?> = emptyMap()
    // Полный список процедур/функций источника (независимо от того, совпадает ли сейчас тип
    // приёмника) — запрашивается один раз при подключении; видимость в routinesTable пересчитывает
    // refreshRoutinesVisibility() при каждом изменении targetDbType, без повторного похода в БД.
    private var lastRoutines: List<RoutineRef> = emptyList()

    // Устанавливается снаружи (CopyView) при каждом изменении типа БД в TargetPanelController —
    // см. комментарий у routinesTable.
    var targetDbType: DbType? = null
        set(value) {
            field = value
            refreshRoutinesVisibility()
        }

    val connectedProperty = SimpleBooleanProperty(false)

    // Элементы управления источником — слева; список таблиц/views/процедур (которые появляются
    // только после успешного подключения) — справа, чтобы длинная таблица с сотнями строк не
    // растягивала окно вниз под формой подключения (см. Шаг 15 инструкции).
    private val controlsColumn = VBox(8.0, HBox(16.0, connectionModeRadio, dumpModeRadio), connectionBox, dumpBox)
    private val tablesColumn = VBox(
        8.0,
        HBox(8.0, selectAllButton, selectNoneButton),
        tablesTable,
        Label(Messages.get("source.views.label")),
        viewsTable,
        Label(Messages.get("source.routines.label")),
        routinesTable,
    ).apply { maxWidth = Double.MAX_VALUE }

    val view = VBox(
        8.0,
        Label(Messages.get("source.title")),
        HBox(16.0, controlsColumn, tablesColumn).apply { HBox.setHgrow(tablesColumn, Priority.ALWAYS) },
    ).apply { padding = Insets(8.0) }

    init {
        picker.testButton.setOnAction { testConnection() }
        modeGroup.selectedToggleProperty().addListener { _, _, newToggle ->
            val isDump = newToggle == dumpModeRadio
            connectionBox.isVisible = !isDump
            connectionBox.isManaged = !isDump
            dumpBox.isVisible = isDump
            dumpBox.isManaged = isDump
            connectedProperty.set(false)
            selection = null
            lastRowCounts = emptyMap()
            lastRoutines = emptyList()
            tablesTable.items.clear()
            viewsTable.items.clear()
            refreshRoutinesVisibility()
        }
    }

    fun selectedTables(): List<String> = tablesTable.items.filter { it.isSelected }.map { it.name }

    fun rowsTotalFor(table: String): Long? = lastRowCounts[table]

    fun selectedViews(): List<String> = viewsTable.items.filter { it.isSelected }.map { it.name }

    fun selectedRoutines(): List<RoutineRef> = routinesTable.items.filter { it.isSelected }.map { RoutineRef(it.name, it.kind) }

    fun currentSelection(): SourceSelection? = selection

    /** Перечитывает список сохранённых подключений — вызывается после закрытия окна "Подключения"
     *  (см. Main.kt), т.к. список мог измениться (создано/изменено/удалено подключение). */
    fun refreshConnections() = picker.refresh()

    // Хранимые процедуры/функции разрешено копировать только между источником и приёмником одного
    // типа БД (см. комментарий у routinesTable) — источник-дамп их не индексирует вовсе (аналогично
    // views), поэтому список в обоих случаях просто недоступен, а не тихо остаётся пустым без
    // объяснения, будто в источнике действительно ничего нет.
    private fun refreshRoutinesVisibility() {
        val sel = selection
        val target = targetDbType
        if (sel is SourceSelection.Connection && target != null && sel.config.type == target) {
            routinesTable.items.setAll(lastRoutines.sortedBy { it.name }.map { RoutineSelection(it.name, it.kind, false) })
            routinesTable.placeholder = Label(Messages.get("source.routines.empty"))
        } else {
            routinesTable.items.clear()
            routinesTable.placeholder = Label(
                Messages.get(if (sel is SourceSelection.Connection) "source.routines.typeMismatch" else "source.routines.dumpUnavailable")
            )
        }
    }

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
        val record = picker.combo.value ?: return
        val config = record.config
        picker.testButton.isDisable = true
        picker.statusLabel.text = Messages.get("source.connecting")

        AppScope.scope.launch {
            val adapter = JdbcSourceAdapter(config)
            try {
                adapter.connect()
                val tables = adapter.listTables()
                val views = adapter.listViews()
                val routines = adapter.listRoutines()
                withContext(Dispatchers.Main) {
                    selection = SourceSelection.Connection(config, record.id)
                    lastRowCounts = tables
                    lastRoutines = routines
                    tablesTable.items.setAll(tables.keys.sorted().map { TableSelection(it, true) })
                    viewsTable.items.setAll(views.sorted().map { TableSelection(it, false) })
                    refreshRoutinesVisibility()
                    picker.statusLabel.text = Messages.get("source.connected", tables.size)
                    connectedProperty.set(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    selection = null
                    connectedProperty.set(false)
                    picker.statusLabel.text = Messages.get("source.error", e.message ?: "")
                }
            } finally {
                adapter.close()
                withContext(Dispatchers.Main) { picker.testButton.isDisable = false }
            }
        }
    }

    private fun chooseDumpFile() {
        val chooser = FileChooser().apply {
            title = Messages.get("source.dump.chooserTitle")
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
            dumpStatusLabel.text = Messages.get("source.dump.chooseFileFirst")
            return
        }
        val dialect = dialectCombo.value
        loadDumpButton.isDisable = true
        dumpStatusLabel.text = Messages.get("source.dump.parsing")

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
                    lastRowCounts = tables
                    lastRoutines = emptyList()
                    tablesTable.items.setAll(tables.keys.sorted().map { TableSelection(it, true) })
                    // Парсер дампов не индексирует views/процедуры (Шаг 13) — список всегда пуст для дампов.
                    viewsTable.items.clear()
                    refreshRoutinesVisibility()
                    dumpStatusLabel.text = Messages.get("source.dump.parsed", tables.size)
                    connectedProperty.set(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    selection = null
                    connectedProperty.set(false)
                    dumpStatusLabel.text = Messages.get("source.error", e.message ?: "")
                }
            } finally {
                adapter.close()
                withContext(Dispatchers.Main) { loadDumpButton.isDisable = false }
            }
        }
    }
}
