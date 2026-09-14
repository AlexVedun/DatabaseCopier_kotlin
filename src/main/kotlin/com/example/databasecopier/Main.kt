package com.example.databasecopier

import com.example.databasecopier.i18n.AppLanguage
import com.example.databasecopier.i18n.Messages
import com.example.databasecopier.session.CopySessionRepository
import com.example.databasecopier.ui.ConnectionsView
import com.example.databasecopier.ui.CopyView
import com.example.databasecopier.ui.LogViewerWindow
import com.example.databasecopier.ui.SessionsView
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.control.RadioMenuItem
import javafx.scene.control.ToggleGroup
import javafx.scene.layout.BorderPane
import javafx.stage.Stage
import java.util.Locale

class DatabaseCopierApp : Application() {

    // Немодальное окно просмотра лога — синглтон (в отличие от CopyView/SessionsView), чтобы
    // повторный клик "Файл лога" не плодил новые окна поверх уже открытого, а просто выводил его
    // на передний план.
    private var logViewerWindow: LogViewerWindow? = null

    // Текущий экран копирования (когда он показан, а не SessionsView) — нужен, чтобы после
    // закрытия модального окна "Подключения" обновить в нём выпадающие списки подключений
    // (см. showInitialScreen/refreshConnections).
    private var currentCopyView: CopyView? = null

    private lateinit var primaryStage: Stage

    override fun start(stage: Stage) {
        primaryStage = stage
        initAppDatabase()
        CopySessionRepository.pauseAllRunningSessions()

        val contentPane = BorderPane()
        val root = BorderPane().apply {
            top = buildMenuBar()
            center = contentPane
        }
        // +30 — типичная высота MenuBar (Modena) — чтобы её появление не отъедало у contentPane
        // высоту, которая раньше (до добавления строки меню) была у него целиком.
        val scene = Scene(root, 900.0, 710.0)
        stage.title = "Database Copier"
        stage.scene = scene
        stage.show()

        showInitialScreen(contentPane)
    }

    private fun buildMenuBar(): MenuBar {
        val logMenuItem = MenuItem(Messages.get("menu.logFile")).apply {
            setOnAction {
                val existing = logViewerWindow
                if (existing != null && existing.stage.isShowing) {
                    existing.stage.toFront()
                    existing.stage.requestFocus()
                } else {
                    logViewerWindow = LogViewerWindow().also { it.stage.show() }
                }
            }
        }
        val connectionsMenuItem = MenuItem(Messages.get("menu.connections")).apply {
            setOnAction {
                ConnectionsView(primaryStage).showAndWait()
                // Окно модальное — showAndWait() возвращается только после его закрытия, список
                // подключений к этому моменту мог измениться.
                currentCopyView?.refreshConnections()
            }
        }
        val languageMenu = buildLanguageMenu()
        return MenuBar(Menu(Messages.get("menu.file"), null, connectionsMenuItem, logMenuItem, languageMenu))
    }

    // Смена языка не перестраивает уже показанный экран на лету (это потребовало бы либо полной
    // пересборки текущего CopyView/SessionsView с потерей несохранённого состояния формы вроде
    // отмеченных чекбоксов таблиц, либо реактивного связывания каждой строки в UI с текущим
    // языком) — вместо этого выбор сохраняется на диск и применяется при следующем запуске
    // (см. Messages/main()), а пользователю сразу показывается подсказка об этом.
    private fun buildLanguageMenu(): Menu {
        val languageToggleGroup = ToggleGroup()
        val items = AppLanguage.entries.map { language ->
            RadioMenuItem(language.displayName).apply {
                toggleGroup = languageToggleGroup
                isSelected = language == Messages.current
                setOnAction {
                    Messages.setLanguage(language)
                    Alert(Alert.AlertType.INFORMATION, Messages.get("language.restartMessage")).apply {
                        title = Messages.get("language.restartTitle")
                        headerText = null
                    }.showAndWait()
                }
            }
        }
        return Menu(Messages.get("menu.language")).apply { this.items.addAll(items) }
    }

    private fun showInitialScreen(contentPane: BorderPane) {
        val resumable = CopySessionRepository.listResumable()
        if (resumable.isEmpty()) {
            currentCopyView = CopyView().also { contentPane.center = it.root }
        } else {
            currentCopyView = null
            contentPane.center = SessionsView(
                onContinue = { id ->
                    currentCopyView = CopyView(existingSessionId = id, onBackToSessions = { showInitialScreen(contentPane) })
                        .also { contentPane.center = it.root }
                },
                onSkip = { currentCopyView = CopyView().also { contentPane.center = it.root } },
            ).root
        }
    }
}

fun main(args: Array<String>) {
    // Должно быть выставлено до первого обращения к SLF4J/logback где бы то ни было (включая
    // JDBC-драйверы) — конфигурация логгера читает эту system property один раз при инициализации.
    System.setProperty("APP_LOG_DIR", getAppDataDir().resolve("logs").absolutePath)
    // Влияет на встроенные (не наши) элементы JavaFX, которые сами локализуются по Locale.default —
    // например, текст кнопок ButtonType.YES/NO в Alert. Должно быть выставлено до первого создания
    // любого такого элемента, поэтому до Application.launch, а не где-то внутри UI-кода.
    Locale.setDefault(Messages.current.locale)
    Application.launch(DatabaseCopierApp::class.java, *args)
}
