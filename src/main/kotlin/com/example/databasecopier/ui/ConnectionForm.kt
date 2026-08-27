package com.example.databasecopier.ui

import com.example.databasecopier.adapter.DbType
import com.example.databasecopier.connection.ConnectionRepository
import javafx.geometry.Insets
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox

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

    companion object {
        // Общая ширина колонки лейблов формы подключения — поля ввода в каждой строке начинаются
        // с одного и того же отступа независимо от длины подписи ("Host:" короче "Database:"),
        // так что все ComboBox/PasswordField в форме выровнены по левому краю между собой.
        const val LABEL_WIDTH = 90.0
        private const val LABEL_GAP = 8.0

        // Единая ширина для всех полей ввода формы (кроме Database, которому нужно больше места
        // под длинный путь к файлу SQLite) — визуально выравнивает правый край полей между собой.
        private const val FIELD_WIDTH = 220.0
        private const val DATABASE_FIELD_WIDTH = 220.0
    }

    val dbTypeCombo: ComboBox<DbType> = ComboBox<DbType>().apply {
        items.addAll(DbType.MYSQL, DbType.POSTGRESQL, DbType.SQLITE, DbType.SQLSERVER)
        value = DbType.MYSQL
        prefWidth = FIELD_WIDTH
    }
    val hostField: ComboBox<String> = editableCombo(ConnectionRepository.distinctHosts(), "host", FIELD_WIDTH)
    val portField: ComboBox<String> = editableCombo(ConnectionRepository.distinctPorts(), "port", FIELD_WIDTH)
    val databaseField: ComboBox<String> =
        editableCombo(ConnectionRepository.distinctDatabases(), "database (для SQLite — путь к файлу)", DATABASE_FIELD_WIDTH)
    val usernameField: ComboBox<String> = editableCombo(ConnectionRepository.distinctUsernames(), "username", FIELD_WIDTH)
    val passwordField = PasswordField().apply { promptText = "password"; prefWidth = FIELD_WIDTH }
    val testButton = Button("Проверить подключение")
    val statusLabel = Label()

    val typeRow: HBox = labeledRow("Тип БД:", dbTypeCombo)
    val hostRow: HBox = labeledRow("Host:", hostField)
    val portRow: HBox = labeledRow("Port:", portField)
    val databaseRow: HBox = labeledRow("Database:", databaseField)
    val userNameRow: HBox = labeledRow("Username:", usernameField)
    val passwordRow: HBox = labeledRow("Password:", passwordField)
    val testButtonRow: HBox = HBox(8.0, testButton, statusLabel)
    val row: VBox = VBox(
        6.0,
        typeRow,
        hostRow,
        portRow,
        databaseRow,
        userNameRow,
        passwordRow,
        testButtonRow,
    ).apply { padding = Insets(4.0) }

    fun portOrNull(): Int? = portField.value?.toIntOrNull()

    // Лейбл фиксированной ширины (с отступом до самого поля) — гарантирует, что поле ввода
    // в этой строке стоит на том же месте, что и в любой другой строке формы.
    private fun labeledRow(text: String, control: javafx.scene.Node): HBox =
        HBox(LABEL_GAP, Label(text).apply { minWidth = LABEL_WIDTH }, control)

    private fun editableCombo(history: List<String>, prompt: String, width: Double): ComboBox<String> =
        ComboBox<String>().apply {
            isEditable = true
            items.addAll(history)
            promptText = prompt
            prefWidth = width
        }
}
