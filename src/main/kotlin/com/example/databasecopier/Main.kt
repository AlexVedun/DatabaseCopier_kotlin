package com.example.databasecopier

import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.control.Label
import javafx.scene.layout.StackPane
import javafx.stage.Stage

class DatabaseCopierApp : Application() {
    override fun start(stage: Stage) {
        initAppDatabase()

        val root = StackPane(Label("Database Copier - Hello World"))
        val scene = Scene(root, 400.0, 300.0)

        stage.title = "Database Copier"
        stage.scene = scene
        stage.show()
    }
}

fun main(args: Array<String>) {
    Application.launch(DatabaseCopierApp::class.java, *args)
}
