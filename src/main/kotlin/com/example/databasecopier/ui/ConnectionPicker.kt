package com.example.databasecopier.ui

import com.example.databasecopier.adapter.DbType
import com.example.databasecopier.connection.ConnectionRecord
import com.example.databasecopier.connection.ConnectionRepository
import com.example.databasecopier.i18n.Messages
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.util.StringConverter

/**
 * Заменяет прежнюю форму host/port/database/username/password на выпадающий список ранее
 * созданных именованных подключений (см. ConnectionsView) — переиспользуется
 * SourcePanelController и TargetPanelController, аналогично тому, как раньше переиспользовался
 * ConnectionForm.
 */
class ConnectionPicker {

    companion object {
        // Совпадает с шириной подписей в ConnectionEditDialog — визуально выравнивает эту строку
        // с остальными формами приложения.
        private const val LABEL_WIDTH = 90.0
    }

    val combo: ComboBox<ConnectionRecord> = ComboBox<ConnectionRecord>().apply {
        prefWidth = 260.0
        promptText = Messages.get("picker.noConnections")
        converter = object : StringConverter<ConnectionRecord>() {
            override fun toString(record: ConnectionRecord?): String =
                record?.let { "${it.name} (${it.config.type.name.lowercase()})" } ?: ""
            override fun fromString(text: String?): ConnectionRecord? = null
        }
    }

    // Не biндим disableProperty напрямую на combo.valueProperty().isNull — testConnection() в
    // вызывающем контроллере ещё и сам временно отключает кнопку на время запроса, а забинженное
    // свойство нельзя менять вручную (кидает исключение).
    val testButton = Button(Messages.get("picker.test")).apply { isDisable = true }
    val statusLabel = Label()

    val row: VBox = VBox(
        6.0,
        HBox(8.0, Label(Messages.get("picker.label")).apply { minWidth = LABEL_WIDTH }, combo),
        HBox(8.0, testButton, statusLabel),
    ).apply { padding = Insets(4.0) }

    init {
        combo.valueProperty().addListener { _, _, newValue -> testButton.isDisable = newValue == null }
        refresh()
    }

    /** Перечитывает список подключений из БД — вызывается при создании панели и после закрытия
     *  окна "Подключения" (там список мог измениться). Пытается сохранить текущий выбор по id. */
    fun refresh() {
        val selectedId = combo.value?.id
        combo.items.setAll(ConnectionRepository.list())
        combo.value = combo.items.find { it.id == selectedId } ?: combo.items.firstOrNull()
    }

    fun onSelectionChanged(listener: (DbType?) -> Unit) {
        combo.valueProperty().addListener { _, _, newValue -> listener(newValue?.config?.type) }
    }
}
