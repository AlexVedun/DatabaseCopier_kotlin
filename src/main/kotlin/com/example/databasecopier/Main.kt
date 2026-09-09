package com.example.databasecopier

import com.example.databasecopier.session.CopySessionRepository
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

    override fun start(stage: Stage) {
        initAppDatabase()
        CopySessionRepository.pauseAllRunningSessions()

        val contentPane = BorderPane()
        val root = BorderPane().apply {
            top = buildMenuBar()
            center = contentPane
        }
        // +30 — типичная высота MenuBar (Modena) — чтобы её появление не отъедало у contentPane
        // высоту, которая раньше (до добавления строки меню) была у него целиком.
        val scene = Scene(root, 900.0, 830.0)
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
        return MenuBar(Menu("Файл", null, logMenuItem))
    }

    private fun showInitialScreen(contentPane: BorderPane) {
        val resumable = CopySessionRepository.listResumable()
        if (resumable.isEmpty()) {
            contentPane.center = CopyView().root
        } else {
            contentPane.center = SessionsView(
                onContinue = { id ->
                    contentPane.center = CopyView(existingSessionId = id, onBackToSessions = { showInitialScreen(contentPane) }).root
                },
                onSkip = { contentPane.center = CopyView().root },
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
