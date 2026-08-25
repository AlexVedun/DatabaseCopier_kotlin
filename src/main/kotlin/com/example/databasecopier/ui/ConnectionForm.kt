package com.example.databasecopier.ui

import com.example.databasecopier.adapter.DbType
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.layout.GridPane

/**
 * Общая форма подключения к БД (тип/host/port/database/username/password + кнопка проверки),
 * переиспользуется SourcePanelController и TargetPanelController. Для SQLite поле "Database"
 * служит путём к файлу .sqlite — host/port/username/password в этом случае не используются
 * (buildJdbcUrl игнорирует их для DbType.SQLITE), их можно оставить пустыми.
 */
class ConnectionForm {
    val dbTypeCombo: ComboBox<DbType> = ComboBox<DbType>().apply {
        items.addAll(DbType.MYSQL, DbType.POSTGRESQL, DbType.SQLITE, DbType.SQLSERVER)
        value = DbType.MYSQL
    }
    val hostField = TextField().apply { promptText = "host" }
    val portField = TextField().apply { promptText = "port" }
    val databaseField = TextField().apply { promptText = "database (для SQLite — путь к файлу)" }
    val usernameField = TextField().apply { promptText = "username" }
    val passwordField = PasswordField().apply { promptText = "password" }
    val testButton = Button("Проверить подключение")
    val statusLabel = Label()

    val grid: GridPane = GridPane().apply {
        hgap = 8.0
        vgap = 6.0
        padding = Insets(8.0)
        addRow(0, Label("Тип БД:"), dbTypeCombo)
        addRow(1, Label("Host:"), hostField)
        addRow(2, Label("Port:"), portField)
        addRow(3, Label("Database:"), databaseField)
        addRow(4, Label("Username:"), usernameField)
        addRow(5, Label("Password:"), passwordField)
        addRow(6, testButton, statusLabel)
    }

    fun portOrNull(): Int? = portField.text.toIntOrNull()
}
