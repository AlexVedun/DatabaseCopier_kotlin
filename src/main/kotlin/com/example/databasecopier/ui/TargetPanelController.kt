package com.example.databasecopier.ui

import com.example.databasecopier.AppScope
import com.example.databasecopier.adapter.ConnectionConfig
import com.example.databasecopier.adapter.JdbcTargetAdapter
import com.example.databasecopier.connection.ConnectionRepository
import javafx.beans.property.SimpleBooleanProperty
import javafx.geometry.Insets
import javafx.scene.control.Label
import javafx.scene.control.RadioButton
import javafx.scene.control.Spinner
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Раздел "Результат копирования": подключение к живой target-БД + режим копирования + размер батча. */
class TargetPanelController {

    private val form = ConnectionForm()

    private val toggleGroup = ToggleGroup()
    private val structureOnlyRadio = RadioButton("Только структура").apply { toggleGroup = this@TargetPanelController.toggleGroup }
    private val structureAndDataRadio = RadioButton("Структура и данные").apply {
        toggleGroup = this@TargetPanelController.toggleGroup
        isSelected = true
    }

    private val batchSizeSpinner = Spinner<Int>(1, Int.MAX_VALUE, 1000, 100).apply { isEditable = true }

    var connectionConfig: ConnectionConfig? = null
        private set
    var connectionId: Int? = null
        private set

    val connectedProperty = SimpleBooleanProperty(false)

    val view = VBox(
        8.0,
        Label("Результат копирования"),
        form.row,
        HBox(8.0, structureOnlyRadio, structureAndDataRadio),
        HBox(8.0, Label("Размер батча:").apply { minWidth = ConnectionForm.LABEL_WIDTH }, batchSizeSpinner),
    ).apply { padding = Insets(8.0) }

    init {
        form.testButton.setOnAction { testConnection() }
    }

    fun copyMode(): String = if (structureOnlyRadio.isSelected) "structure_only" else "structure_and_data"

    fun batchSize(): Int = batchSizeSpinner.value

    private fun testConnection() {
        val config = ConnectionConfig(
            type = form.dbTypeCombo.value,
            host = form.hostField.value,
            port = form.portOrNull(),
            database = form.databaseField.value ?: "",
            username = form.usernameField.value,
            password = form.passwordField.text,
        )
        form.testButton.isDisable = true
        form.statusLabel.text = "Проверка..."

        AppScope.scope.launch {
            val adapter = JdbcTargetAdapter(config)
            try {
                adapter.connect()
                val savedId = ConnectionRepository.save("${config.host}:${config.database}", config)
                withContext(Dispatchers.Main) {
                    connectionConfig = config
                    connectionId = savedId
                    form.statusLabel.text = "Подключено"
                    connectedProperty.set(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    connectionConfig = null
                    connectionId = null
                    connectedProperty.set(false)
                    form.statusLabel.text = "Ошибка: ${e.message}"
                }
            } finally {
                adapter.close()
                withContext(Dispatchers.Main) { form.testButton.isDisable = false }
            }
        }
    }
}
