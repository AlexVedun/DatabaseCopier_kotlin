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
    private val startButton = Button("Запустить")
    private val pauseButton = Button("Приостановить").apply { isDisable = true }
    private val cancelButton = Button("Отменить").apply { isDisable = true }

    private val overallLabel = Label("Сессия не запущена")
    private val overallProgressBar = ProgressBar(0.0).apply { prefWidth = 300.0 }
    private val tableLabel = Label("")
    private val tableProgressBar = ProgressBar(0.0).apply { prefWidth = 300.0 }
    private val errorLabel = Label("").apply { isWrapText = true; style = "-fx-text-fill: red;" }

    var sessionId: Int? = null
        private set

    val view = VBox(
        8.0,
        Label("Прогресс"),
        HBox(8.0, startButton, pauseButton, cancelButton),
        overallLabel,
        overallProgressBar,
        tableLabel,
        tableProgressBar,
        errorLabel,
    ).apply { padding = Insets(8.0) }

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
            showError("Сначала настройте источник (подключение или дамп)")
            return
        }
        if (targetConfig == null || targetConnId == null) {
            showError("Сначала проверьте подключение приёмника")
            return
        }
        if (tables.isEmpty()) {
            showError("Выберите хотя бы одну таблицу для копирования")
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
        tables.forEach { CopySessionRepository.addTable(newSessionId, it) }
        source.selectedViews().forEach { CopySessionRepository.addView(newSessionId, it, isSelected = true) }

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
            showError("Сессия не найдена")
            return
        }
        val sourceAdapter: SourceAdapter = when (session.sourceType) {
            "connection" -> {
                val connId = session.sourceConnectionId
                val config = connId?.let { ConnectionRepository.load(it) }
                if (config == null) {
                    showError("Не удалось загрузить сохранённое подключение источника")
                    return
                }
                JdbcSourceAdapter(config)
            }
            "dump" -> {
                val path = session.sourceDumpPath
                val dialect = session.sourceDumpDialect?.let { runCatching { DumpDialect.valueOf(it.uppercase()) }.getOrNull() }
                if (path == null || dialect == null) {
                    showError("Не удалось восстановить параметры дампа для этой сессии")
                    return
                }
                val file = File(path)
                when (dialect) {
                    DumpDialect.MYSQL -> MysqlDumpSourceAdapter(file)
                    DumpDialect.POSTGRESQL -> PostgresDumpSourceAdapter(file)
                }
            }
            else -> {
                showError("Неизвестный тип источника: ${session.sourceType}")
                return
            }
        }
        val targetConfig = ConnectionRepository.load(session.targetConnectionId)
        if (targetConfig == null) {
            showError("Не удалось загрузить сохранённое подключение приёмника")
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
        overallLabel.text = "Копирование: 0 из $totalTables таблиц"
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
                sourceAdapter.connect()
                targetAdapter.connect()
                runner.run(id, sourceAdapter, targetAdapter)
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
    }

    private fun onProgress(event: CopyProgressEvent, totalTables: Int) {
        val done = CopySessionRepository.getTables(event.sessionId).count { it.status == "done" }
        overallLabel.text = "Копирование: $done из $totalTables таблиц"
        overallProgressBar.progress = if (totalTables > 0) done.toDouble() / totalTables else 0.0
        tableLabel.text = "${event.tableName}: ${event.rowsCopied}" +
            (event.rowsTotal?.let { " из $it" } ?: " строк")
        tableProgressBar.progress = event.rowsTotal?.let { total ->
            if (total > 0) event.rowsCopied.toDouble() / total else -1.0
        } ?: -1.0
    }

    private fun onFinished(sessionId: Int) {
        startButton.isDisable = false
        pauseButton.isDisable = true
        cancelButton.isDisable = true
        val session = CopySessionRepository.getSession(sessionId)
        overallLabel.text = "Сессия завершена со статусом: ${session?.status}"
        if (session?.status == "completed") {
            overallProgressBar.progress = 1.0
            tableProgressBar.progress = 1.0
        }
        errorLabel.text = if (session?.status == "failed") "Ошибка: ${session.lastError}" else ""
    }

    private fun showError(message: String) {
        Alert(Alert.AlertType.WARNING, message).showAndWait()
    }
}
