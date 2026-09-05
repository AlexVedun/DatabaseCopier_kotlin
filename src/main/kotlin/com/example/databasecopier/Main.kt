package com.example.databasecopier

import com.example.databasecopier.session.CopySessionRepository
import com.example.databasecopier.ui.CopyView
import com.example.databasecopier.ui.SessionsView
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.layout.StackPane
import javafx.stage.Stage

class DatabaseCopierApp : Application() {
    override fun start(stage: Stage) {
        initAppDatabase()
        CopySessionRepository.pauseAllRunningSessions()

        val scene = Scene(StackPane(), 900.0, 800.0)
        stage.title = "Database Copier"
        stage.scene = scene
        stage.show()

        showInitialScreen(scene)
    }

    private fun showInitialScreen(scene: Scene) {
        val resumable = CopySessionRepository.listResumable()
        if (resumable.isEmpty()) {
            scene.root = CopyView().root
        } else {
            scene.root = SessionsView(
                onContinue = { id ->
                    scene.root = CopyView(existingSessionId = id, onBackToSessions = { showInitialScreen(scene) }).root
                },
                onSkip = { scene.root = CopyView().root },
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
