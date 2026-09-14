package com.example.databasecopier.ui

import com.example.databasecopier.adapter.ConnectionConfig
import com.example.databasecopier.adapter.DbType
import com.example.databasecopier.connection.ConnectionRepository
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.TextField
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import javafx.stage.Window

/**
 * Модальный диалог создания/изменения/копирования именованного подключения (см. ConnectionsView).
 * Поля ввода — обычные TextField, без истории автодополнения (в отличие от прежнего
 * ConnectionForm) — здесь пользователь один раз явно вводит и сохраняет конкретное подключение,
 * а не многократно набирает одни и те же параметры вручную.
 *
 * @param excludingId id редактируемого подключения — исключается из проверки уникальности имени,
 *   иначе сохранение без изменения имени всегда падало бы как "уже используется". null при
 *   создании и при копировании (там всегда новая запись).
 * @param onSave вызывается только после прохождения валидации; сам решает, insert это или update.
 */
class ConnectionEditDialog(
    owner: Window?,
    title: String,
    initialName: String,
    initialConfig: ConnectionConfig?,
    private val excludingId: Int?,
    private val onSave: (name: String, config: ConnectionConfig) -> Unit,
) {
    companion object {
        private const val LABEL_WIDTH = 90.0
        private const val FIELD_WIDTH = 220.0
    }

    private val nameField = TextField(initialName).apply { prefWidth = FIELD_WIDTH }
    private val typeCombo = ComboBox<DbType>().apply {
        items.addAll(DbType.MYSQL, DbType.POSTGRESQL, DbType.SQLITE, DbType.SQLSERVER)
        value = initialConfig?.type ?: DbType.MYSQL
        prefWidth = FIELD_WIDTH
    }
    private val hostField = TextField(initialConfig?.host ?: "").apply { prefWidth = FIELD_WIDTH }
    private val portField = TextField(initialConfig?.port?.toString() ?: "").apply { prefWidth = FIELD_WIDTH }
    private val databaseField = TextField(initialConfig?.database ?: "").apply {
        prefWidth = FIELD_WIDTH
        promptText = "для SQLite — путь к файлу"
    }
    private val usernameField = TextField(initialConfig?.username ?: "").apply { prefWidth = FIELD_WIDTH }
    private val passwordField = PasswordField().apply {
        text = initialConfig?.password ?: ""
        prefWidth = FIELD_WIDTH
    }
    private val errorLabel = Label().apply { isWrapText = true; style = "-fx-text-fill: red;" }

    private val stage = Stage().apply {
        this.title = title
        initModality(Modality.WINDOW_MODAL)
        if (owner != null) initOwner(owner)
    }

    fun showAndWait() {
        val saveButton = Button("Сохранить").apply { setOnAction { trySave() } }
        val cancelButton = Button("Отмена").apply { setOnAction { stage.close() } }
        val root = VBox(
            8.0,
            labeledRow("Название:", nameField),
            labeledRow("Тип БД:", typeCombo),
            labeledRow("Host:", hostField),
            labeledRow("Port:", portField),
            labeledRow("Database:", databaseField),
            labeledRow("Username:", usernameField),
            labeledRow("Password:", passwordField),
            errorLabel,
            HBox(8.0, saveButton, cancelButton),
        ).apply { padding = Insets(12.0) }
        stage.scene = Scene(root)
        stage.showAndWait()
    }

    private fun labeledRow(text: String, control: Node): HBox =
        HBox(8.0, Label(text).apply { minWidth = LABEL_WIDTH }, control)

    private fun trySave() {
        val name = nameField.text?.trim().orEmpty()
        if (name.isEmpty()) {
            errorLabel.text = "Введите название подключения"
            return
        }
        if (ConnectionRepository.isNameTaken(name, excludingId)) {
            errorLabel.text = "Подключение с таким названием уже существует"
            return
        }
        val config = ConnectionConfig(
            type = typeCombo.value,
            host = hostField.text?.trim()?.ifBlank { null },
            port = portField.text?.trim()?.toIntOrNull(),
            database = databaseField.text?.trim().orEmpty(),
            username = usernameField.text?.trim()?.ifBlank { null },
            password = passwordField.text?.ifEmpty { null },
        )
        onSave(name, config)
        stage.close()
    }
}
