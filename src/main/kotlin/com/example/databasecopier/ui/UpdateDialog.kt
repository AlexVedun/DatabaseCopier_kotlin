package com.example.databasecopier.ui

import com.example.databasecopier.AppInfo
import com.example.databasecopier.AppScope
import com.example.databasecopier.i18n.Messages
import com.example.databasecopier.update.AppUpdater
import com.example.databasecopier.update.ReleaseAsset
import com.example.databasecopier.update.ReleaseInfo
import javafx.application.Platform
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Alert
import javafx.scene.control.Button
import javafx.scene.control.ButtonBar
import javafx.scene.control.ButtonType
import javafx.scene.control.Label
import javafx.scene.control.ProgressBar
import javafx.scene.control.TextArea
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.stage.Modality
import javafx.stage.Stage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.Locale

object UpdateDialog {

    private val logger = LoggerFactory.getLogger(UpdateDialog::class.java)

    /**
     * Тихая фоновая проверка при старте приложения: сетевые ошибки не беспокоят пользователя,
     * диалог показывается только если реально опубликован более свежий релиз с пакетом под текущую ОС.
     */
    fun checkAndPromptOnStartup(owner: Stage) {
        AppScope.scope.launch(Dispatchers.IO) {
            val release = runCatching { AppUpdater.checkForUpdate() }
                .onFailure { logger.debug("Не удалось проверить обновления при запуске", it) }
                .getOrNull() ?: return@launch

            if (release.asset == null) return@launch
            Platform.runLater {
                if (owner.isShowing) {
                    promptAndInstall(owner, release)
                }
            }
        }
    }

    /**
     * Ручная проверка по пункту меню "Проверить обновления...".
     */
    fun checkInteractive(owner: Stage) {
        AppScope.scope.launch(Dispatchers.IO) {
            val result = runCatching { AppUpdater.checkForUpdate() }
            Platform.runLater {
                result.fold(
                    onSuccess = { release ->
                        if (release == null) {
                            Alert(
                                Alert.AlertType.INFORMATION,
                                Messages.get("update.upToDateMessage", AppInfo.version),
                            ).apply {
                                initOwner(owner)
                                title = Messages.get("update.title")
                                headerText = null
                            }.showAndWait()
                        } else if (release.asset == null) {
                            Alert(
                                Alert.AlertType.INFORMATION,
                                Messages.get("update.noAssetForPlatform", release.version, release.htmlUrl),
                            ).apply {
                                initOwner(owner)
                                title = Messages.get("update.title")
                                headerText = null
                            }.showAndWait()
                        } else {
                            promptAndInstall(owner, release)
                        }
                    },
                    onFailure = { error ->
                        logger.warn("Ошибка при проверке обновлений", error)
                        Alert(
                            Alert.AlertType.ERROR,
                            Messages.get("update.checkFailed", error.message ?: error.javaClass.simpleName),
                        ).apply {
                            initOwner(owner)
                            title = Messages.get("update.title")
                            headerText = null
                        }.showAndWait()
                    },
                )
            }
        }
    }

    private fun promptAndInstall(owner: Stage, release: ReleaseInfo) {
        val asset = release.asset ?: return
        val updateButtonType = ButtonType(Messages.get("update.installNow"), ButtonBar.ButtonData.OK_DONE)
        val laterButtonType = ButtonType(Messages.get("update.later"), ButtonBar.ButtonData.CANCEL_CLOSE)

        val alert = Alert(Alert.AlertType.CONFIRMATION).apply {
            initOwner(owner)
            title = Messages.get("update.title")
            headerText = Messages.get("update.availableHeader", release.version, AppInfo.version)
            contentText = Messages.get("update.availableMessage", asset.name)
            buttonTypes.setAll(updateButtonType, laterButtonType)

            if (release.releaseNotes.isNotBlank()) {
                val notesArea = TextArea(release.releaseNotes).apply {
                    isEditable = false
                    isWrapText = true
                    prefRowCount = 8
                    maxWidth = Double.MAX_VALUE
                    maxHeight = Double.MAX_VALUE
                }
                val contentBox = VBox(
                    8.0,
                    Label(Messages.get("update.availableMessage", asset.name)).apply { isWrapText = true },
                    Label(Messages.get("update.releaseNotesLabel")),
                    notesArea,
                )
                VBox.setVgrow(notesArea, Priority.ALWAYS)
                dialogPane.content = contentBox
                dialogPane.prefWidth = 520.0
            }
        }

        val chosen = alert.showAndWait().orElse(laterButtonType)
        if (chosen == updateButtonType) {
            try {
                AppUpdater.ensureNoRunningCopies()
            } catch (e: Exception) {
                Alert(Alert.AlertType.WARNING, e.message ?: e.javaClass.simpleName).apply {
                    initOwner(owner)
                    title = Messages.get("update.title")
                    headerText = null
                }.showAndWait()
                return
            }
            startDownloadAndInstall(owner, asset)
        }
    }

    private fun startDownloadAndInstall(owner: Stage, asset: ReleaseAsset) {
        val statusLabel = Label(Messages.get("update.downloading", asset.name))
        val progressBar = ProgressBar(0.0).apply {
            maxWidth = Double.MAX_VALUE
        }
        val cancelButton = Button(Messages.get("progress.cancel"))
        val buttonBar = HBox(cancelButton).apply {
            alignment = Pos.CENTER_RIGHT
        }

        val root = VBox(12.0, statusLabel, progressBar, buttonBar).apply {
            padding = Insets(16.0)
            prefWidth = 440.0
        }

        val progressStage = Stage().apply {
            initOwner(owner)
            initModality(Modality.WINDOW_MODAL)
            title = Messages.get("update.title")
            scene = Scene(root)
            isResizable = false
        }

        var job: Job? = null
        cancelButton.setOnAction {
            job?.cancel()
            progressStage.close()
        }
        progressStage.setOnCloseRequest {
            job?.cancel()
        }

        job = AppScope.scope.launch(Dispatchers.IO) {
            val suffix = "." + asset.name.substringAfterLast('.', "pkg")
            val tempFile = Files.createTempFile("database-copier-update-", suffix).toFile()
            try {
                AppUpdater.downloadAsset(asset, tempFile) { downloaded, total ->
                    Platform.runLater {
                        if (total > 0) {
                            val fraction = (downloaded.toDouble() / total.toDouble()).coerceIn(0.0, 1.0)
                            progressBar.progress = fraction
                            val downloadedMb = formatMegabytes(downloaded)
                            val totalMb = formatMegabytes(total)
                            val percent = (fraction * 100).toInt()
                            statusLabel.text = Messages.get("update.downloadProgress", downloadedMb, totalMb, percent)
                        } else {
                            progressBar.progress = ProgressBar.INDETERMINATE_PROGRESS
                            statusLabel.text = Messages.get("update.downloadProgressUnknown", formatMegabytes(downloaded))
                        }
                    }
                }

                withContext(Dispatchers.IO) {
                    Platform.runLater {
                        cancelButton.isDisable = true
                        progressBar.progress = ProgressBar.INDETERMINATE_PROGRESS
                        statusLabel.text = Messages.get("update.installing")
                    }
                    AppUpdater.installAndRestart(tempFile)
                }
            } catch (_: CancellationException) {
                runCatching { tempFile.delete() }
            } catch (e: Exception) {
                runCatching { tempFile.delete() }
                logger.error("Ошибка при установке обновления", e)
                Platform.runLater {
                    progressStage.close()
                    Alert(
                        Alert.AlertType.ERROR,
                        Messages.get("update.installFailed", e.message ?: e.javaClass.simpleName),
                    ).apply {
                        initOwner(owner)
                        title = Messages.get("update.title")
                        headerText = null
                    }.showAndWait()
                }
            }
        }

        progressStage.show()
    }

    private fun formatMegabytes(bytes: Long): String =
        String.format(Locale.US, "%.1f", bytes.toDouble() / (1024.0 * 1024.0))
}
