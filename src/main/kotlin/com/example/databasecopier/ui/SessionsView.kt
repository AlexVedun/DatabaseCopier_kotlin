package com.example.databasecopier.ui

import com.example.databasecopier.session.CopySessionRecord
import com.example.databasecopier.session.CopySessionRepository
import javafx.geometry.Insets
import javafx.scene.Parent
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TableCell
import javafx.scene.control.TableColumn
import javafx.scene.control.TableView
import javafx.scene.layout.VBox
import javafx.util.Callback
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Стартовый экран: если в служебной БД есть незавершённые сессии (draft/paused/running/failed) —
 * список с возможностью продолжить любую из них; иначе (или по кнопке "Новая сессия") —
 * переход сразу к CopyView с чистого листа.
 */
class SessionsView(
    private val onContinue: (Int) -> Unit,
    private val onSkip: () -> Unit,
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

    private val table = TableView<CopySessionRecord>().apply {
        val nameColumn = TableColumn<CopySessionRecord, String>("Имя").apply {
            cellValueFactory = Callback { javafx.beans.property.SimpleStringProperty(it.value.name) }
            prefWidth = 220.0
        }
        val statusColumn = TableColumn<CopySessionRecord, String>("Статус").apply {
            cellValueFactory = Callback { javafx.beans.property.SimpleStringProperty(it.value.status) }
            prefWidth = 100.0
        }
        val updatedColumn = TableColumn<CopySessionRecord, String>("Обновлено").apply {
            cellValueFactory = Callback { javafx.beans.property.SimpleStringProperty(dateFormat.format(Date(it.value.updatedAt))) }
            prefWidth = 150.0
        }
        val actionColumn = TableColumn<CopySessionRecord, Void>("").apply {
            prefWidth = 220.0
            cellFactory = Callback {
                object : TableCell<CopySessionRecord, Void>() {
                    private val continueButton = Button("Продолжить").apply {
                        setOnAction { onContinue(tableView.items[index].id) }
                    }
                    // "Закрыть" — чтобы неудачные/отменённые сессии не висели в списке
                    // "продолжаемых" вечно (listResumable() включает и status = "failed").
                    private val closeButton = Button("Закрыть").apply {
                        setOnAction {
                            CopySessionRepository.updateSessionStatus(tableView.items[index].id, "cancelled")
                            // Явно квалифицируем this@SessionsView — иначе unqualified refresh()
                            // резолвится в ближайший TableView.refresh() (просто перерисовка ячеек
                            // без повторного запроса данных), а не в перезагрузку списка ниже.
                            this@SessionsView.refresh()
                        }
                    }
                    private val box = javafx.scene.layout.HBox(8.0, continueButton, closeButton)

                    override fun updateItem(item: Void?, empty: Boolean) {
                        super.updateItem(item, empty)
                        graphic = if (empty) null else box
                    }
                }
            }
        }
        columns.addAll(nameColumn, statusColumn, updatedColumn, actionColumn)
        prefHeight = 300.0
    }

    private val newSessionButton = Button("Новая сессия").apply {
        setOnAction { onSkip() }
    }

    val root: Parent = VBox(
        8.0,
        Label("Сохранённые сессии копирования"),
        table,
        newSessionButton,
    ).apply { padding = Insets(8.0) }

    init {
        refresh()
    }

    fun refresh() {
        table.items.setAll(CopySessionRepository.listResumable())
    }
}
