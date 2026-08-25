package com.example.databasecopier.ui

import javafx.geometry.Insets
import javafx.scene.Parent
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox

class CopyView(existingSessionId: Int? = null) {
    val sourcePanel = SourcePanelController()
    val targetPanel = TargetPanelController()
    val progressPanel = ProgressPanelController(sourcePanel, targetPanel)

    val root: Parent = if (existingSessionId != null) {
        VBox(
            8.0,
            Label("Продолжение сессии #$existingSessionId"),
            progressPanel.view,
        ).apply { padding = Insets(8.0) }
    } else {
        VBox(
            8.0,
            HBox(16.0, sourcePanel.view, targetPanel.view),
            progressPanel.view,
        ).apply { padding = Insets(8.0) }
    }

    init {
        if (existingSessionId != null) {
            progressPanel.resumeSession(existingSessionId)
        }
    }
}
