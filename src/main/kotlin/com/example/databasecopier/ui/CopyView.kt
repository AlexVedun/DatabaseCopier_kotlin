package com.example.databasecopier.ui

import javafx.geometry.Insets
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox

/**
 * @param onBackToSessions вызывается по кнопке "Назад к списку сессий" — актуально только в
 *   режиме продолжения ([existingSessionId] задан), где нет никакого другого способа покинуть
 *   этот экран (например, после отмены упавшей сессии) и начать новую или продолжить другую.
 */
class CopyView(existingSessionId: Int? = null, onBackToSessions: (() -> Unit)? = null) {
    val sourcePanel = SourcePanelController()
    val targetPanel = TargetPanelController()
    val progressPanel = ProgressPanelController(sourcePanel, targetPanel)

    val root: Parent = if (existingSessionId != null) {
        VBox(
            8.0,
            HBox(8.0, Label("Продолжение сессии #$existingSessionId")).apply {
                if (onBackToSessions != null) {
                    children.add(Button("Назад к списку сессий").apply { setOnAction { onBackToSessions() } })
                }
            },
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
