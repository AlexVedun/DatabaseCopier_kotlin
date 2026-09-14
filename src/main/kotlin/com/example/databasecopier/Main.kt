package com.example.databasecopier

import com.example.databasecopier.session.CopySessionRepository
import com.example.databasecopier.ui.ConnectionsView
import com.example.databasecopier.ui.CopyView
import com.example.databasecopier.ui.LogViewerWindow
import com.example.databasecopier.ui.SessionsView
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.layout.BorderPane
import javafx.stage.Stage

class DatabaseCopierApp : Application() {

    // Немодальное окно просмотра лога — синглтон (в отличие от CopyView/SessionsView), чтобы
    // повторный клик "Файл лога" не плодил новые окна поверх уже открытого, а просто выводил его
    // на передний план.
    private var logViewerWindow: LogViewerWindow? = null

    // Текущий экран копирования (когда он показан, а не SessionsView) — нужен, чтобы после
    // закрытия модального окна "Подключения" обновить в нём выпадающие списки подключений
    // (см. showInitialScreen/refreshConnections).
    private var currentCopyView: CopyView? = null

    private lateinit var primaryStage: Stage

    override fun start(stage: Stage) {
        primaryStage = stage
        initAppDatabase()
        CopySessionRepository.pauseAllRunningSessions()

        val contentPane = BorderPane()
        val root = BorderPane().apply {
            top = buildMenuBar()
            center = contentPane
        }
        // +30 — типичная высота MenuBar (Modena) — чтобы её появление не отъедало у contentPane
        // высоту, которая раньше (до добавления строки меню) была у него целиком.
        val scene = Scene(root, 900.0, 845.0)
        stage.title = "Database Copier"
        stage.scene = scene
        stage.show()

        showInitialScreen(contentPane)
    }

    private fun buildMenuBar(): MenuBar {
        val logMenuItem = MenuItem("Файл лога").apply {
            setOnAction {
                val existing = logViewerWindow
                if (existing != null && existing.stage.isShowing) {
                    existing.stage.toFront()
                    existing.stage.requestFocus()
                } else {
                    logViewerWindow = LogViewerWindow().also { it.stage.show() }
                }
            }
        }
        val connectionsMenuItem = MenuItem("Подключения").apply {
            setOnAction {
                ConnectionsView(primaryStage).showAndWait()
                // Окно модальное — showAndWait() возвращается только после его закрытия, список
                // подключений к этому моменту мог измениться.
                currentCopyView?.refreshConnections()
            }
        }
        return MenuBar(Menu("Файл", null, connectionsMenuItem, logMenuItem))
    }

    private fun showInitialScreen(contentPane: BorderPane) {
        val resumable = CopySessionRepository.listResumable()
        if (resumable.isEmpty()) {
            currentCopyView = CopyView().also { contentPane.center = it.root }
        } else {
            currentCopyView = null
            contentPane.center = SessionsView(
                onContinue = { id ->
                    currentCopyView = CopyView(existingSessionId = id, onBackToSessions = { showInitialScreen(contentPane) })
                        .also { contentPane.center = it.root }
                },
                onSkip = { currentCopyView = CopyView().also { contentPane.center = it.root } },
            ).root
        }
    }
}

fun main(args: Array<String>) {
    // Должно быть выставлено до первого обращения к SLF4J/logback где бы то ни было (включая
    // JDBC-драйверы) — конфигурация логгера читает эту system property один раз при инициализации.
    System.setProperty("APP_LOG_DIR", getAppDataDir().resolve("logs").absolutePath)
    Application.launch(DatabaseCopierApp::class.java, *args)
}
