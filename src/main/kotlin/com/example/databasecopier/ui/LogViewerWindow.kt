package com.example.databasecopier.ui

import com.example.databasecopier.getAppDataDir
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.geometry.Insets
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.TextArea
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.stage.Stage
import javafx.util.Duration
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * Отдельное немодальное окно с содержимым текущего лог-файла приложения (см. logback.xml —
 * APP_LOG_DIR/logs/app.log). Не модальное specifically, чтобы можно было наблюдать за логом,
 * продолжая работать в основном окне (например, следить за ходом копирования).
 *
 * Дочитывает файл "с хвоста" (RandomAccessFile.seek на последнюю прочитанную позицию), а не
 * перечитывает его целиком на каждое обновление — текущий файл может достигать 10 МБ
 * (SizeBasedTriggeringPolicy в logback.xml), и полное перечитывание раз в пару секунд заметно
 * грузило бы CPU и UI-поток на перерисовку огромного TextArea.
 */
class LogViewerWindow {

    companion object {
        // Штатный шаг прокрутки TextArea колесом мыши по ощущениям кратно медленнее, чем в обычном
        // текстовом редакторе/браузере — подобрано эмпирически, не привязано к какой-либо единице
        // JavaFX API (deltaY сам по себе платформозависим).
        private const val SCROLL_SPEED_MULTIPLIER = 6.0
    }

    private val logFile = getAppDataDir().resolve("logs").resolve("app.log")

    private val textArea = TextArea().apply {
        isEditable = false
        isWrapText = false
        style = "-fx-font-family: monospace;"
        // TextArea сама обрабатывает прокрутку колесом мыши с фиксированным, довольно медленным
        // шагом (не настраивается штатным API) — на лог-файле в десятки тысяч строк это неудобно.
        // Перехватываем ScrollEvent на фазе filter (раньше, чем сама TextArea) и двигаем scrollTop
        // на deltaY с увеличенным множителем сами, гася событие, чтобы штатная (медленная)
        // прокрутка не сработала следом ещё и от исходного deltaY.
        addEventFilter(ScrollEvent.SCROLL) { event ->
            scrollTop -= event.deltaY * SCROLL_SPEED_MULTIPLIER
            event.consume()
        }
    }

    private val statusLabel = Label()

    private var lastReadPosition = 0L

    val stage: Stage = Stage().apply {
        title = "Лог-файл приложения"

        val refreshButton = Button("Обновить").apply { setOnAction { refresh(force = true) } }
        val toolbar = HBox(8.0, refreshButton, statusLabel).apply { padding = Insets(8.0) }

        val root = BorderPane().apply {
            top = toolbar
            center = textArea
        }
        scene = Scene(root, 900.0, 600.0)

        // Раз в 2 секунды дочитываем появившиеся с прошлого раза строки — пока сессия копирования
        // активна, это даёт эффект "живого" хвоста лога без ручного нажатия "Обновить".
        val timeline = Timeline(KeyFrame(Duration.seconds(2.0), { refresh(force = false) }))
        timeline.cycleCount = Timeline.INDEFINITE
        timeline.play()
        setOnCloseRequest { timeline.stop() }

        refresh(force = true)
    }

    private fun refresh(force: Boolean) {
        if (!logFile.exists()) {
            if (force) {
                textArea.text = ""
                statusLabel.text = "Лог-файл ещё не создан (${logFile.absolutePath})"
            }
            return
        }

        val currentLength = logFile.length()
        // Файл мог "переехать" (ротация logback — переименование в app.1.log и создание нового
        // app.log с нуля): длина резко уменьшилась относительно последней прочитанной позиции —
        // считаем это новым файлом и перечитываем с начала, а не пытаемся seek() за пределы файла.
        if (force || currentLength < lastReadPosition) {
            textArea.clear()
            lastReadPosition = 0L
        }
        if (currentLength == lastReadPosition) {
            statusLabel.text = "Обновлено: без изменений (${currentLength / 1024} КБ)"
            return
        }

        RandomAccessFile(logFile, "r").use { raf ->
            raf.seek(lastReadPosition)
            val newBytes = ByteArray((raf.length() - lastReadPosition).toInt())
            raf.readFully(newBytes)
            textArea.appendText(String(newBytes, StandardCharsets.UTF_8))
            lastReadPosition = raf.length()
        }
        textArea.scrollTop = Double.MAX_VALUE
        statusLabel.text = "Обновлено: ${lastReadPosition / 1024} КБ"
    }
}
