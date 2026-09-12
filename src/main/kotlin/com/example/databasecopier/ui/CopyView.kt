package com.example.databasecopier.ui

import javafx.geometry.Insets
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ScrollPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
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

    // Список таблиц/views источника занимает фиксированную высоту сам по себе и при большом
    // количестве таблиц (сотни) вместе с формами подключения легко превышает высоту окна —
    // без ScrollPane низ содержимого (в т.ч. ProgressBar и кнопки паузы/отмены) просто обрезался
    // окном и не был виден и недоступен для клика.
    val root: Parent = ScrollPane(
        if (existingSessionId != null) {
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
                16.0,
                sourcePanel.view,
                HBox(16.0, targetPanel.view, progressPanel.view).apply {
                    HBox.setHgrow(progressPanel.view, Priority.ALWAYS)
                },
            ).apply { padding = Insets(8.0) }
        }
    ).apply { isFitToWidth = true }

    init {
        if (existingSessionId != null) {
            progressPanel.resumeSession(existingSessionId)
        } else {
            // Список хранимых процедур/функций в sourcePanel зависит от типа target-БД (копируются
            // только между источником и приёмником одного типа, см. SourcePanelController) — панели
            // независимы друг от друга, поэтому связываем их здесь, а не внутри самих панелей.
            sourcePanel.targetDbType = targetPanel.dbType
            targetPanel.onDbTypeChanged { sourcePanel.targetDbType = it }
        }
    }
}
