package com.example.databasecopier.ui

import com.example.databasecopier.AppScope
import com.example.databasecopier.adapter.ConnectionConfig
import com.example.databasecopier.adapter.DbType
import com.example.databasecopier.adapter.JdbcTargetAdapter
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

    private val picker = ConnectionPicker()

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

    // Нужен SourcePanelController'у, чтобы решить, показывать ли список хранимых процедур/функций —
    // их разрешено копировать только между одинаковыми типами БД (см. SourcePanelController). Тип
    // выбирается сразу при выборе подключения из списка, ДО фактического нажатия "Проверить
    // подключение" — поэтому используем значение выбранной записи picker'а, а не connectionConfig
    // (которое появляется только после успешного testConnection()).
    val dbType: DbType? get() = picker.combo.value?.config?.type

    fun onDbTypeChanged(listener: (DbType?) -> Unit) = picker.onSelectionChanged(listener)

    val view = VBox(
        8.0,
        Label("Результат копирования"),
        picker.row,
        HBox(8.0, structureOnlyRadio, structureAndDataRadio),
        HBox(8.0, Label("Размер батча:").apply { minWidth = 90.0 }, batchSizeSpinner),
    ).apply { padding = Insets(8.0) }

    init {
        picker.testButton.setOnAction { testConnection() }
    }

    fun copyMode(): String = if (structureOnlyRadio.isSelected) "structure_only" else "structure_and_data"

    fun batchSize(): Int = batchSizeSpinner.value

    /** Перечитывает список сохранённых подключений — вызывается после закрытия окна "Подключения"
     *  (см. Main.kt), т.к. список мог измениться (создано/изменено/удалено подключение). */
    fun refreshConnections() = picker.refresh()

    private fun testConnection() {
        val record = picker.combo.value ?: return
        val config = record.config
        picker.testButton.isDisable = true
        picker.statusLabel.text = "Проверка..."

        AppScope.scope.launch {
            val adapter = JdbcTargetAdapter(config)
            try {
                adapter.connect()
                withContext(Dispatchers.Main) {
                    connectionConfig = config
                    connectionId = record.id
                    picker.statusLabel.text = "Подключено"
                    connectedProperty.set(true)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    connectionConfig = null
                    connectionId = null
                    connectedProperty.set(false)
                    picker.statusLabel.text = "Ошибка: ${e.message}"
                }
            } finally {
                adapter.close()
                withContext(Dispatchers.Main) { picker.testButton.isDisable = false }
            }
        }
    }
}
