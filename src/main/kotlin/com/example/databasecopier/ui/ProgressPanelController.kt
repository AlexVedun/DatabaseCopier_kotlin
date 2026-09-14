package com.example.databasecopier.ui

import com.example.databasecopier.AppScope
import com.example.databasecopier.adapter.ConnectionConfig
import com.example.databasecopier.adapter.JdbcSourceAdapter
import com.example.databasecopier.adapter.JdbcTargetAdapter
import com.example.databasecopier.adapter.SourceAdapter
import com.example.databasecopier.connection.ConnectionRepository
import com.example.databasecopier.copy.CopyProgressEvent
import com.example.databasecopier.copy.CopyRunner
import com.example.databasecopier.dump.DumpDialect
import com.example.databasecopier.dump.MysqlDumpSourceAdapter
import com.example.databasecopier.dump.PostgresDumpSourceAdapter
import com.example.databasecopier.i18n.Messages
import com.example.databasecopier.session.CopySessionRepository
import javafx.geometry.Insets
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Раздел "Прогресс": запуск/пауза/отмена копирования, отображение прогресса по текущей таблице. */
class ProgressPanelController(
    private val source: SourcePanelController,
    private val target: TargetPanelController,
) {
    private val startButton = Button(Messages.get("progress.start"))
    private val pauseButton = Button(Messages.get("progress.pause")).apply { isDisable = true }
    private val cancelButton = Button(Messages.get("progress.cancel")).apply { isDisable = true }

    private val overallLabel = Label(Messages.get("progress.notStarted"))
    private val overallProgressBar = ProgressBar(0.0).apply { prefWidth = 300.0; maxWidth = Double.MAX_VALUE }
    private val tableLabel = Label("")
    private val tableProgressBar = ProgressBar(0.0).apply { prefWidth = 300.0; maxWidth = Double.MAX_VALUE }
    private val errorLabel = Label("").apply { isWrapText = true; style = "-fx-text-fill: red;" }

    var sessionId: Int? = null
        private set

    // Region по умолчанию не растёт шире своей preferred-ширины, даже если родитель (HBox с
    // Priority.ALWAYS в CopyView) готов выделить больше места — maxWidth нужно снять явно, иначе
    // раздел "Прогресс" остаётся прижатым к "Результату копирования" вместо растяжения до края окна.
    val view = VBox(
        8.0,
        Label(Messages.get("progress.title")),
        HBox(8.0, startButton, pauseButton, cancelButton),
        overallLabel,
        overallProgressBar,
        tableLabel,
        tableProgressBar,
        errorLabel,
    ).apply { padding = Insets(8.0); maxWidth = Double.MAX_VALUE }

    private var collectorJob: Job? = null

    // По умолчанию кнопка "Запустить" создаёт новую сессию из настроенных source/target панелей.
    // Но в режиме продолжения сессии (CopyView(existingSessionId)) эти панели не отображаются и не
    // настроены — там повторное нажатие "Запустить" (например, после failed) обязано повторить
    // именно resumeSession(id), а не start(), иначе пользователь неизбежно получает "источник не
    // настроен", даже когда сессия и её источник/приёмник давно сохранены в служебной БД.
    private var onStartRequested: () -> Unit = { start() }

    init {
        startButton.setOnAction { onStartRequested() }
        pauseButton.setOnAction { pause() }
        cancelButton.setOnAction { cancel() }
    }

    private fun start() {
        val selection = source.currentSelection()
        val targetConfig = target.connectionConfig
        val targetConnId = target.connectionId
        val tables = source.selectedTables()

        if (selection == null) {
            showError(Messages.get("progress.error.noSource"))
            return
        }
        if (targetConfig == null || targetConnId == null) {
            showError(Messages.get("progress.error.noTarget"))
            return
        }
        if (tables.isEmpty()) {
            showError(Messages.get("progress.error.noTables"))
            return
        }

        val sourceName = when (selection) {
            is SourceSelection.Connection -> selection.config.database
            is SourceSelection.Dump -> selection.file.name
        }
        val newSessionId = CopySessionRepository.createSession(
            name = "$sourceName -> ${targetConfig.database}",
            sourceType = if (selection is SourceSelection.Connection) "connection" else "dump",
            sourceConnectionId = (selection as? SourceSelection.Connection)?.connectionId,
            sourceDumpPath = (selection as? SourceSelection.Dump)?.file?.absolutePath,
            sourceDumpDialect = (selection as? SourceSelection.Dump)?.dialect?.name?.lowercase(),
            targetConnectionId = targetConnId,
            copyMode = target.copyMode(),
            batchSize = target.batchSize(),
        )
        tables.forEach { CopySessionRepository.addTable(newSessionId, it, rowsTotal = source.rowsTotalFor(it)) }
        source.selectedViews().forEach { CopySessionRepository.addView(newSessionId, it, isSelected = true) }
        source.selectedRoutines().forEach {
            CopySessionRepository.addRoutine(newSessionId, it.name, it.kind.name.lowercase(), isSelected = true)
        }

        launchCopy(newSessionId, source.createAdapter(), targetConfig, tables.size)
    }

    /**
     * Продолжает ранее сохранённую (paused/failed/draft-незавершённую) сессию, выбранную в
     * SessionsView — подключения/файл дампа и список таблиц уже сохранены в служебной БД,
     * никакого нового ввода от пользователя не требуется.
     */
    fun resumeSession(id: Int) {
        onStartRequested = { resumeSession(id) }

        val session = CopySessionRepository.getSession(id)
        if (session == null) {
            showError(Messages.get("progress.error.sessionNotFound"))
            return
        }
        val sourceAdapter: SourceAdapter = when (session.sourceType) {
            "connection" -> {
                val connId = session.sourceConnectionId
                val config = connId?.let { ConnectionRepository.load(it) }
                if (config == null) {
                    showError(Messages.get("progress.error.sourceConnectionLoadFailed"))
                    return
                }
                JdbcSourceAdapter(config)
            }
            "dump" -> {
                val path = session.sourceDumpPath
                val dialect = session.sourceDumpDialect?.let { runCatching { DumpDialect.valueOf(it.uppercase()) }.getOrNull() }
                if (path == null || dialect == null) {
                    showError(Messages.get("progress.error.dumpRestoreFailed"))
                    return
                }
                val file = File(path)
                when (dialect) {
                    DumpDialect.MYSQL -> MysqlDumpSourceAdapter(file)
                    DumpDialect.POSTGRESQL -> PostgresDumpSourceAdapter(file)
                }
            }
            else -> {
                showError(Messages.get("progress.error.unknownSourceType", session.sourceType))
                return
            }
        }
        val targetConfig = ConnectionRepository.load(session.targetConnectionId)
        if (targetConfig == null) {
            showError(Messages.get("progress.error.targetConnectionLoadFailed"))
            return
        }
        val totalTables = CopySessionRepository.getTables(id).count { it.isSelected }

        launchCopy(id, sourceAdapter, targetConfig, totalTables)
    }

    private fun launchCopy(id: Int, sourceAdapter: SourceAdapter, targetConfig: ConnectionConfig, totalTables: Int) {
        sessionId = id
        CopySessionRepository.updateSessionStatus(id, "running")

        startButton.isDisable = true
        pauseButton.isDisable = false
        cancelButton.isDisable = false
        overallLabel.text = Messages.get("progress.tablesProgress", 0, totalTables)
        overallProgressBar.progress = 0.0
        tableProgressBar.progress = 0.0
        errorLabel.text = ""

        val runner = CopyRunner()
        collectorJob = AppScope.scope.launch {
            runner.progress.collect { event -> withContext(Dispatchers.Main) { onProgress(event, totalTables) } }
        }

        AppScope.scope.launch {
            val targetAdapter = JdbcTargetAdapter(targetConfig)
            try {
                withContext(Dispatchers.Main) { overallLabel.text = Messages.get("progress.connectingSource") }
                sourceAdapter.connect()
                withContext(Dispatchers.Main) { overallLabel.text = Messages.get("progress.connectingTarget") }
                targetAdapter.connect()
                withContext(Dispatchers.Main) { overallLabel.text = Messages.get("progress.tablesProgress", 0, totalTables) }
                runner.run(id, sourceAdapter, targetAdapter)
            } catch (e: Exception) {
                // connect() выполняется здесь, а не внутри CopyRunner.run — без этого catch сбой
                // подключения (например, недоступный удалённый сервер) улетал бы необработанным
                // исключением из корутины, сессия навсегда оставалась бы в статусе "running", а UI —
                // висел с "Подключение..." без единой подсказки, что пошло не так.
                CopySessionRepository.updateSessionStatus(id, "failed", lastError = e.message)
            } finally {
                sourceAdapter.close()
                targetAdapter.close()
                collectorJob?.cancel()
                withContext(Dispatchers.Main) { onFinished(id) }
            }
        }
    }

    private fun pause() {
        sessionId?.let { CopySessionRepository.updateSessionStatus(it, "paused") }
        pauseButton.isDisable = true
        cancelButton.isDisable = true
    }

    private fun cancel() {
        sessionId?.let { CopySessionRepository.updateSessionStatus(it, "cancelled") }
        pauseButton.isDisable = true
        cancelButton.isDisable = true
        overallLabel.text = Messages.get("progress.sessionCancelled")
        errorLabel.text = ""
    }

    private fun onProgress(event: CopyProgressEvent, totalTables: Int) {
        val done = CopySessionRepository.getTables(event.sessionId).count { it.status == "done" }
        overallLabel.text = Messages.get("progress.tablesProgress", done, totalTables)
        overallProgressBar.progress = if (totalTables > 0) done.toDouble() / totalTables else 0.0

        // FK/views — отдельные проходы ПОСЛЕ того, как все таблицы уже в статусе "done" (см.
        // CopyRunner.run) — у них нет построчного прогресса как у копирования данных, только
        // "обработано объектов X из Y" (переиспользует rowsCopied/rowsTotal в этом смысле для
        // этих двух phase, см. CopyProgressEvent). Без отдельной ветки здесь пользователь во время
        // этих проходов видел бы застывшую на последней скопированной таблице полоску без единого
        // намёка на то, сколько ещё осталось — так и был обнаружен этот пробел.
        val phaseLabel = when (event.phase) {
            "foreign_keys", "views", "routines" -> Messages.get("progress.phase.${event.phase}")
            else -> null
        }
        if (phaseLabel != null) {
            tableLabel.text = Messages.get("progress.phaseProgress", phaseLabel, event.rowsCopied, event.rowsTotal ?: 0L, event.tableName)
        } else {
            tableLabel.text = event.rowsTotal?.let {
                Messages.get("progress.rows.withTotal", event.tableName, event.rowsCopied, it)
            } ?: Messages.get("progress.rows.withoutTotal", event.tableName, event.rowsCopied)
        }
        // progress = -1.0 (JavaFX "indeterminate") рисуется как непрерывно бегающая туда-сюда
        // анимированная полоска — из-за постоянной перерисовки это грузит CPU почти на 100% на
        // время всего копирования такой таблицы. Раз общее число строк неизвестно (rowsTotal ==
        // null, source.countRows() не смог его получить) или равно 0 (копировать нечего), честного
        // соотношения всё равно не показать — вместо анимации просто держим полоску пустой/полной.
        tableProgressBar.progress = event.rowsTotal?.let { total ->
            if (total > 0) event.rowsCopied.toDouble() / total else 1.0
        } ?: 0.0
    }

    private fun onFinished(sessionId: Int) {
        startButton.isDisable = false
        pauseButton.isDisable = true
        val session = CopySessionRepository.getSession(sessionId)
        // "Отменить" остаётся доступна после failed/paused — иначе с упавшей сессией нельзя было
        // сделать вообще ничего, кроме бесконечных попыток "Запустить" заново.
        cancelButton.isDisable = session?.status !in setOf("failed", "paused")
        overallLabel.text = Messages.get("progress.sessionFinished", session?.status?.let { Messages.status(it) } ?: "")
        if (session?.status == "completed") {
            overallProgressBar.progress = 1.0
            tableProgressBar.progress = 1.0
        }
        errorLabel.text = if (session?.status == "failed") Messages.get("progress.error", session.lastError ?: "") else ""
    }

    private fun showError(message: String) {
        Alert(Alert.AlertType.WARNING, message).showAndWait()
    }
}
