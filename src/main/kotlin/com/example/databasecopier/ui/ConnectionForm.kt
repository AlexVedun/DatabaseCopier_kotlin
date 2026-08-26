package com.example.databasecopier.ui

import com.example.databasecopier.adapter.DbType
import com.example.databasecopier.connection.ConnectionRepository
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.layout.HBox

/**
 * Общая форма подключения к БД (тип/host/port/database/username/password + кнопка проверки),
 * переиспользуется SourcePanelController и TargetPanelController. Для SQLite поле "Database"
 * служит путём к файлу .sqlite — host/port/username/password в этом случае не используются
 * (buildJdbcUrl игнорирует их для DbType.SQLITE), их можно оставить пустыми.
 *
 * Host/Port/Database/Username — редактируемые ComboBox, а не обычные TextField: выпадающий
 * список подсказок заполняется значениями, которые уже когда-либо вводились и сохранялись в
 * `Connections` (см. ConnectionRepository), чтобы не перепечатывать одни и те же параметры
 * подключения заново при повторном использовании приложения.
 */
class ConnectionForm {
    val dbTypeCombo: ComboBox<DbType> = ComboBox<DbType>().apply {
        items.addAll(DbType.MYSQL, DbType.POSTGRESQL, DbType.SQLITE, DbType.SQLSERVER)
        value = DbType.MYSQL
    }
    val hostField: ComboBox<String> = editableCombo(ConnectionRepository.distinctHosts(), "host", 120.0)
    val portField: ComboBox<String> = editableCombo(ConnectionRepository.distinctPorts(), "port", 60.0)
    val databaseField: ComboBox<String> =
        editableCombo(ConnectionRepository.distinctDatabases(), "database (для SQLite — путь к файлу)", 220.0)
    val usernameField: ComboBox<String> = editableCombo(ConnectionRepository.distinctUsernames(), "username", 100.0)
    val passwordField = PasswordField().apply { promptText = "password"; prefWidth = 100.0 }
    val testButton = Button("Проверить подключение")
    val statusLabel = Label()

    val row: HBox = HBox(
        6.0,
        Label("Тип БД:"), dbTypeCombo,
        Label("Host:"), hostField,
        Label("Port:"), portField,
        Label("Database:"), databaseField,
        Label("Username:"), usernameField,
        Label("Password:"), passwordField,
        testButton, statusLabel,
    ).apply { padding = Insets(4.0) }

    fun portOrNull(): Int? = portField.value?.toIntOrNull()

    private fun editableCombo(history: List<String>, prompt: String, width: Double): ComboBox<String> =
        ComboBox<String>().apply {
            isEditable = true
            items.addAll(history)
            promptText = prompt
            prefWidth = width
        }
}
