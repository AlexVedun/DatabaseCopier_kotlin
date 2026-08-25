package com.example.databasecopier.ui

import com.example.databasecopier.AppScope
import com.example.databasecopier.adapter.JdbcSourceAdapter
import com.example.databasecopier.adapter.JdbcTargetAdapter
import com.example.databasecopier.copy.CopyProgressEvent
import com.example.databasecopier.copy.CopyRunner
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

/** Раздел "Прогресс": запуск/пауза/отмена копирования, отображение прогресса по текущей таблице. */
class ProgressPanelController(
    private val source: SourcePanelController,
    private val target: TargetPanelController,
) {
    private val startButton = Button("Запустить")
    private val pauseButton = Button("Приостановить").apply { isDisable = true }
    private val cancelButton = Button("Отменить").apply { isDisable = true }

    private val overallLabel = Label("Сессия не запущена")
    private val tableLabel = Label("")
    private val tableProgressBar = ProgressBar(0.0).apply { prefWidth = 300.0 }

    var sessionId: Int? = null
        private set

    val view = VBox(
        8.0,
        Label("Прогресс"),
        HBox(8.0, startButton, pauseButton, cancelButton),
        overallLabel,
        tableLabel,
        tableProgressBar,
    ).apply { padding = Insets(8.0) }

    private var collectorJob: Job? = null

    init {
        startButton.setOnAction { start() }
        pauseButton.setOnAction { pause() }
        cancelButton.setOnAction { cancel() }
    }

    private fun start() {
        val sourceConfig = source.connectionConfig
        val sourceConnId = source.connectionId
        val targetConfig = target.connectionConfig
        val targetConnId = target.connectionId
        val tables = source.selectedTables()

        if (sourceConfig == null || sourceConnId == null) {
            showError("Сначала проверьте подключение источника")
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

        val newSessionId = CopySessionRepository.createSession(
            name = "${sourceConfig.database} -> ${targetConfig.database}",
            sourceType = "connection",
            sourceConnectionId = sourceConnId,
            sourceDumpPath = null,
            sourceDumpDialect = null,
            targetConnectionId = targetConnId,
            copyMode = target.copyMode(),
            batchSize = target.batchSize(),
        )
        tables.forEach { CopySessionRepository.addTable(newSessionId, it) }
        sessionId = newSessionId
        CopySessionRepository.updateSessionStatus(newSessionId, "running")

        startButton.isDisable = true
        pauseButton.isDisable = false
        cancelButton.isDisable = false
        overallLabel.text = "Копирование: 0 из ${tables.size} таблиц"

        val runner = CopyRunner()
        collectorJob = AppScope.scope.launch {
            runner.progress.collect { event -> withContext(Dispatchers.Main) { onProgress(event, tables.size) } }
        }

        AppScope.scope.launch {
            val sourceAdapter = JdbcSourceAdapter(sourceConfig)
            val targetAdapter = JdbcTargetAdapter(targetConfig)
            try {
                sourceAdapter.connect()
                targetAdapter.connect()
                runner.run(newSessionId, sourceAdapter, targetAdapter)
            } finally {
                sourceAdapter.close()
                targetAdapter.close()
                collectorJob?.cancel()
                withContext(Dispatchers.Main) { onFinished(newSessionId) }
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
        val status = CopySessionRepository.getSession(sessionId)?.status
        overallLabel.text = "Сессия завершена со статусом: $status"
    }

    private fun showError(message: String) {
        Alert(Alert.AlertType.WARNING, message).showAndWait()
    }
}
