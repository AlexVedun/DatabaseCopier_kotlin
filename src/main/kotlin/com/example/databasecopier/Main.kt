package com.example.databasecopier

import com.example.databasecopier.session.CopySessionRepository
import com.example.databasecopier.ui.CopyView
import javafx.application.Application
import javafx.scene.Scene
import javafx.stage.Stage

class DatabaseCopierApp : Application() {
    override fun start(stage: Stage) {
        initAppDatabase()
        CopySessionRepository.pauseAllRunningSessions()

        val root = CopyView().root
        val scene = Scene(root, 900.0, 700.0)

        stage.title = "Database Copier"
        stage.scene = scene
        stage.show()
    }
}

fun main(args: Array<String>) {
    Application.launch(DatabaseCopierApp::class.java, *args)
}
