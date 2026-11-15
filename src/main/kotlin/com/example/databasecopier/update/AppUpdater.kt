package com.example.databasecopier.update

import com.example.databasecopier.AppInfo
import com.example.databasecopier.getAppDataDir
import javafx.application.Platform
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

enum class UpdatePlatform {
    MACOS,
    LINUX,
    WINDOWS,
    OTHER,
    ;

    companion object {
        fun current(osName: String = System.getProperty("os.name").lowercase()): UpdatePlatform = when {
            osName.contains("mac") -> MACOS
            osName.contains("linux") -> LINUX
            osName.contains("windows") -> WINDOWS
            else -> OTHER
        }
    }
}

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long,
)

data class ReleaseInfo(
    val version: String,
    val tagName: String,
    val releaseNotes: String,
    val htmlUrl: String,
    val asset: ReleaseAsset?,
)

object AppUpdater {

    private val logger = LoggerFactory.getLogger(AppUpdater::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    const val GITHUB_REPO = "AlexVedun/DatabaseCopier_kotlin"
    const val LATEST_RELEASE_API_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    const val DEFAULT_MAC_BUNDLE_NAME = "database-copier.app"
    private const val STARTUP_CONFIRM_TIMEOUT_MS = 15_000L

    internal var startupMarkerFile: File = getAppDataDir().resolve("update-startup-ok.marker")

    /**
     * Вызывается из `DatabaseCopierApp.start()` после успешной инициализации БД и показа главного окна.
     * Записывает маркер успешного старта, по которому процесс-обновщик убеждается, что новая версия
     * действительно запустилась и прошла инициализацию.
     */
    fun notifyStartupSucceeded() {
        runCatching {
            startupMarkerFile.parentFile?.mkdirs()
            startupMarkerFile.writeText(AppInfo.version)
        }
    }

    /**
     * Проверяет последний релиз на GitHub. Возвращает [ReleaseInfo], если на GitHub опубликована
     * более новая версия, чем [currentVersion], иначе `null`.
     */
    fun checkForUpdate(
        currentVersion: String = AppInfo.version,
        platform: UpdatePlatform = UpdatePlatform.current(),
        apiUrl: String = LATEST_RELEASE_API_URL,
    ): ReleaseInfo? {
        val responseBody = fetchText(apiUrl)
        val release = parseReleaseJson(responseBody, platform)
        return if (compareVersions(release.version, currentVersion) > 0) release else null
    }

    internal fun parseReleaseJson(
        rawJson: String,
        platform: UpdatePlatform = UpdatePlatform.current(),
    ): ReleaseInfo {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val tagName = root["tag_name"]?.jsonPrimitive?.content
            ?: throw IOException("Поле tag_name отсутствует в ответе GitHub Releases API")
        val version = tagName.removePrefix("v").removePrefix("V")
        val htmlUrl = root["html_url"]?.jsonPrimitive?.content
            ?: "https://github.com/$GITHUB_REPO/releases/tag/$tagName"
        val body = root["body"]?.jsonPrimitive?.content ?: ""

        val assets = root["assets"]?.jsonArray?.mapNotNull { element ->
            val obj = element.jsonObject
            val name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val url = obj["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val size = obj["size"]?.jsonPrimitive?.longOrNull ?: -1L
            ReleaseAsset(name = name, downloadUrl = url, size = size)
        }.orEmpty()

        return ReleaseInfo(
            version = version,
            tagName = tagName,
            releaseNotes = body,
            htmlUrl = htmlUrl,
            asset = selectAssetForPlatform(assets, platform),
        )
    }

    internal fun selectAssetForPlatform(
        assets: List<ReleaseAsset>,
        platform: UpdatePlatform,
    ): ReleaseAsset? = when (platform) {
        UpdatePlatform.MACOS -> assets.firstOrNull { it.name.endsWith(".dmg", ignoreCase = true) }
        UpdatePlatform.LINUX -> assets.firstOrNull { it.name.endsWith(".AppImage", ignoreCase = true) }
        UpdatePlatform.WINDOWS -> assets.firstOrNull { it.name.endsWith(".exe", ignoreCase = true) }
        UpdatePlatform.OTHER -> null
    }

    /**
     * Сравнивает две семантические версии вида `1.2.7` / `v1.2.8`.
     * Возвращает положительное число, если [v1] новее [v2], отрицательное — если старее, 0 — если равны.
     */
    internal fun compareVersions(v1: String, v2: String): Int {
        val parts1 = parseVersionParts(v1)
        val parts2 = parseVersionParts(v2)
        val maxLen = maxOf(parts1.size, parts2.size)
        for (i in 0 until maxLen) {
            val p1 = parts1.getOrElse(i) { 0 }
            val p2 = parts2.getOrElse(i) { 0 }
            if (p1 != p2) return p1.compareTo(p2)
        }
        return 0
    }

    private fun parseVersionParts(version: String): List<Int> =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('-')
            .split('.')
            .map { it.toIntOrNull() ?: 0 }

    suspend fun downloadAsset(
        asset: ReleaseAsset,
        destination: File,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        val conn = openConnectionFollowingRedirects(asset.downloadUrl)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code при скачивании ${asset.downloadUrl}")
            }
            val contentLength = conn.contentLengthLong
            if (contentLength > 0 && asset.size > 0 && contentLength != asset.size) {
                throw IOException(
                    "Несовпадение размера пакета ${asset.name}: HTTP Content-Length=$contentLength, в релизе=${asset.size}"
                )
            }
            val expectedBytes = contentLength.takeIf { it > 0 } ?: asset.size

            conn.inputStream.buffered().use { input ->
                downloadStreamToFile(input, destination, expectedBytes, onProgress)
            }
        } finally {
            conn.disconnect()
        }
    }

    internal suspend fun downloadStreamToFile(
        input: InputStream,
        destination: File,
        expectedBytes: Long,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        try {
            destination.outputStream().buffered().use { output ->
                val buffer = ByteArray(64 * 1024)
                var downloaded = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    downloaded += read
                    onProgress(downloaded, expectedBytes)
                }
                output.flush()
                if (expectedBytes > 0 && downloaded != expectedBytes) {
                    throw IOException(
                        "Скачивание прервано преждевременно: получено $downloaded из $expectedBytes байт"
                    )
                }
            }
        } catch (e: Throwable) {
            runCatching { destination.delete() }
            throw e
        }
    }

    /**
     * Устанавливает скачанный пакет обновления и перезапускает приложение:
     * - **macOS (Вариант 1а — автозамена `.app`)**: тихо монтирует `.dmg` через
     *   `hdiutil attach -nobrowse -readonly -mountpoint ...`, копирует `.app`-бандл поверх
     *   текущего `.app` (например, `/Applications/database-copier.app`), размонтирует образ и
     *   перезапускает приложение через `open -n ...`.
     * - **Linux (`.AppImage`)**: заменяет текущий файл `.AppImage` (путь из `$APPIMAGE`),
     *   выставляет права `0755` и запускает новый `.AppImage`.
     * - **Windows (`.exe`)**: запускает скачанный установщик `.exe` и завершает текущий процесс.
     */
    fun installAndRestart(
        downloadedFile: File,
        platform: UpdatePlatform = UpdatePlatform.current(),
    ) {
        when (platform) {
            UpdatePlatform.MACOS -> installMacDmgAndRestart(downloadedFile)
            UpdatePlatform.LINUX -> installLinuxAppImageAndRestart(downloadedFile)
            UpdatePlatform.WINDOWS -> installWindowsExeAndRestart(downloadedFile)
            UpdatePlatform.OTHER -> throw UnsupportedOperationException("Автообновление не поддерживается для текущей ОС")
        }
    }

    internal fun installMacDmgAndRestart(
        dmgFile: File,
        targetAppBundle: File = resolveMacAppBundlePath(),
        commandRunner: (List<String>) -> CommandResult = ::runCommand,
        relauncher: (File) -> Unit = { app -> relaunchMacAppAndAwaitReady(app, commandRunner) },
        onShutdown: () -> Unit = ::shutdownApplication,
    ) {
        val mountPoint = Files.createTempDirectory("database-copier-dmg-").toFile()
        val backupAppBundle = File(targetAppBundle.parentFile, "${targetAppBundle.name}.old")
        var mounted = false
        var backupCreated = false

        try {
            val attachCmd = buildMacAttachCommand(dmgFile, mountPoint)
            val attachResult = commandRunner(attachCmd)
            if (attachResult.exitCode != 0) {
                throw IOException("Не удалось смонтировать DMG (код ${attachResult.exitCode}): ${attachResult.output}")
            }
            mounted = true

            val mountedApp = findAppBundleInDirectory(mountPoint)
                ?: throw IOException("Внутри смонтированного образа ${dmgFile.name} не найден .app-бандл")

            val hadExistingTarget = targetAppBundle.exists()

            if (backupAppBundle.exists()) {
                val cleanOldBackup = commandRunner(listOf("rm", "-rf", backupAppBundle.absolutePath))
                if (cleanOldBackup.exitCode != 0) {
                    throw IOException(
                        "Не удалось удалить старую резервную копию ${backupAppBundle.absolutePath}: ${cleanOldBackup.output}"
                    )
                }
            }

            // Переименовываем старый .app в .app.old и сохраняем его до успешного перезапуска новой
            // версии, чтобы при любой ошибке копирования или запуска откатить рабочую версию обратно.
            if (hadExistingTarget) {
                val moveToBackup = commandRunner(
                    listOf("mv", targetAppBundle.absolutePath, backupAppBundle.absolutePath)
                )
                if (moveToBackup.exitCode != 0) {
                    throw IOException(
                        "Не удалось переименовать ${targetAppBundle.absolutePath} в ${backupAppBundle.name}: ${moveToBackup.output}"
                    )
                }
                backupCreated = true
            } else {
                targetAppBundle.parentFile?.mkdirs()
            }

            val copyResult = commandRunner(
                listOf("ditto", mountedApp.absolutePath, targetAppBundle.absolutePath)
            )
            if (copyResult.exitCode != 0) {
                throw IOException("Не удалось скопировать ${mountedApp.name} в ${targetAppBundle.absolutePath}: ${copyResult.output}")
            }

            // Снимаем карантинный атрибут со свежескопированного бандла, чтобы macOS не блокировала
            // повторный запуск без цифровой подписи Apple Developer ID.
            commandRunner(listOf("xattr", "-cr", targetAppBundle.absolutePath))
        } catch (e: Throwable) {
            if (backupCreated) {
                rollbackMacAppBundle(targetAppBundle, backupAppBundle, commandRunner, e)
            }
            throw e
        } finally {
            if (mounted) {
                runCatching {
                    val detachResult = commandRunner(buildMacDetachCommand(mountPoint, force = false))
                    if (detachResult.exitCode != 0) {
                        commandRunner(buildMacDetachCommand(mountPoint, force = true))
                    }
                }
            }
            runCatching { mountPoint.deleteRecursively() }
        }

        try {
            relauncher(targetAppBundle)
        } catch (e: Throwable) {
            if (backupCreated) {
                rollbackMacAppBundle(targetAppBundle, backupAppBundle, commandRunner, e)
            }
            throw e
        }

        // Только после подтверждённого запуска новой версии синхронно удаляем резервную копию .app.old
        // и временный DMG, и лишь затем завершаем старый процесс.
        if (backupCreated) {
            runCatching { commandRunner(listOf("rm", "-rf", backupAppBundle.absolutePath)) }
        }
        runCatching { dmgFile.delete() }
        onShutdown()
    }

    private fun rollbackMacAppBundle(
        targetAppBundle: File,
        backupAppBundle: File,
        commandRunner: (List<String>) -> CommandResult,
        cause: Throwable,
    ) {
        val rmPartial = runCatching { commandRunner(listOf("rm", "-rf", targetAppBundle.absolutePath)) }.getOrNull()
        val restoreBackup = if (rmPartial?.exitCode == 0) {
            runCatching { commandRunner(listOf("mv", backupAppBundle.absolutePath, targetAppBundle.absolutePath)) }.getOrNull()
        } else {
            null
        }
        if (rmPartial == null || rmPartial.exitCode != 0 || restoreBackup == null || restoreBackup.exitCode != 0) {
            val rollbackDetails = restoreBackup?.output?.ifBlank { "код ${restoreBackup.exitCode}" }
                ?: rmPartial?.output?.ifBlank { "код ${rmPartial.exitCode}" }
                ?: "ошибка выполнения команды отката"
            throw IOException(
                "${cause.message ?: cause.javaClass.simpleName}; кроме того, не удалось автоматически восстановить " +
                    "резервную копию из ${backupAppBundle.absolutePath}: $rollbackDetails",
                cause,
            )
        }
    }

    internal fun buildMacAttachCommand(dmgFile: File, mountPoint: File): List<String> = listOf(
        "hdiutil",
        "attach",
        "-nobrowse",
        "-readonly",
        "-mountpoint",
        mountPoint.absolutePath,
        dmgFile.absolutePath,
    )

    internal fun buildMacDetachCommand(mountPoint: File, force: Boolean = false): List<String> =
        if (force) {
            listOf("hdiutil", "detach", mountPoint.absolutePath, "-force", "-quiet")
        } else {
            listOf("hdiutil", "detach", mountPoint.absolutePath, "-quiet")
        }

    internal fun buildMacRelaunchCommand(appBundle: File): List<String> = listOf(
        "open",
        "-n",
        appBundle.absolutePath,
    )

    internal fun findAppBundleInDirectory(mountPoint: File): File? =
        mountPoint.listFiles()?.firstOrNull { it.isDirectory && it.name.endsWith(".app", ignoreCase = true) }

    /**
     * Определяет путь к текущему `.app`-бандлу на macOS (например, `/Applications/database-copier.app`),
     * поднимаясь вверх по дереву директорий от исполняемого файла jpackage или расположения jar-файла.
     * Если приложение запущено вне `.app`-бандла, возвращает `/Applications/database-copier.app`.
     */
    internal fun resolveMacAppBundlePath(
        jpackageAppPath: String? = System.getProperty("jpackage.app-path"),
        processCommandPath: String? = ProcessHandle.current().info().command().orElse(null),
        codeSourcePath: String? = runCatching {
            File(AppUpdater::class.java.protectionDomain.codeSource.location.toURI()).absolutePath
        }.getOrNull(),
        defaultApplicationsDir: File = File("/Applications"),
    ): File {
        for (candidate in listOfNotNull(jpackageAppPath, processCommandPath, codeSourcePath)) {
            findEnclosingAppBundle(File(candidate))?.let { return it }
        }
        return File(defaultApplicationsDir, DEFAULT_MAC_BUNDLE_NAME)
    }

    internal fun findEnclosingAppBundle(path: File): File? {
        var current: File? = path.absoluteFile
        while (current != null) {
            if (current.name.endsWith(".app", ignoreCase = true)) {
                return current
            }
            current = current.parentFile
        }
        return null
    }

    private fun relaunchMacAppAndAwaitReady(
        targetAppBundle: File,
        commandRunner: (List<String>) -> CommandResult = ::runCommand,
        timeoutMs: Long = STARTUP_CONFIRM_TIMEOUT_MS,
    ) {
        runCatching { Files.deleteIfExists(startupMarkerFile.toPath()) }
        val launchResult = commandRunner(buildMacRelaunchCommand(targetAppBundle))
        if (launchResult.exitCode != 0) {
            throw IOException("Не удалось запустить обновлённое приложение ${targetAppBundle.absolutePath}: ${launchResult.output}")
        }
        awaitStartupMarker(timeoutMs = timeoutMs, spawnedProcess = null)
    }

    internal fun installLinuxAppImageAndRestart(
        downloadedFile: File,
        targetFile: File = resolveLinuxAppImageFile(),
        relauncher: (File) -> Unit = ::relaunchLinuxAppImageAndAwaitReady,
        onShutdown: () -> Unit = ::shutdownApplication,
    ) {
        val stagingFile = File(targetFile.parentFile, "${targetFile.name}.new")
        val backupFile = File(targetFile.parentFile, "${targetFile.name}.old")

        Files.copy(downloadedFile.toPath(), stagingFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        check(stagingFile.setReadable(true, false) && stagingFile.setExecutable(true, false)) {
            "Не удалось выставить права выполнения для ${stagingFile.absolutePath}"
        }

        val hadExistingTarget = targetFile.exists()
        if (hadExistingTarget) {
            Files.move(
                targetFile.toPath(),
                backupFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }

        try {
            Files.move(
                stagingFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            relauncher(targetFile)
        } catch (e: Throwable) {
            if (hadExistingTarget && backupFile.exists()) {
                try {
                    Files.move(
                        backupFile.toPath(),
                        targetFile.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (rollbackError: Throwable) {
                    e.addSuppressed(rollbackError)
                    throw IOException(
                        "${e.message ?: e.javaClass.simpleName}; кроме того, не удалось автоматически восстановить " +
                            "резервную копию из ${backupFile.absolutePath}: ${rollbackError.message}",
                        e,
                    )
                }
            }
            throw e
        }

        // Резервная копия .old и временный файл удаляются синхронно до завершения старого процесса,
        // только после того, как новая версия подтвердила успешную инициализацию.
        if (hadExistingTarget) {
            runCatching { Files.deleteIfExists(backupFile.toPath()) }
        }
        runCatching { downloadedFile.delete() }
        onShutdown()
    }

    internal fun resolveLinuxAppImageFile(
        appImageEnv: String? = System.getenv("APPIMAGE"),
        appDirEnv: String? = System.getenv("APPDIR"),
    ): File {
        val currentAppImagePath = appImageEnv?.takeIf { it.isNotBlank() }
            ?: throw IOException(
                "Переменная окружения APPIMAGE не задана — автозамена на Linux поддерживается только при запуске из .AppImage"
            )
        val file = File(currentAppImagePath)
        if (!file.isFile) {
            throw IOException(
                "Некорректный путь APPIMAGE (${file.absolutePath}): файл не существует"
            )
        }
        if (!appDirEnv.isNullOrBlank()) {
            val appDirCanonical = runCatching { File(appDirEnv).canonicalPath }.getOrNull()
            val fileCanonical = runCatching { file.canonicalPath }.getOrNull()
            if (appDirCanonical != null && fileCanonical != null &&
                (fileCanonical == appDirCanonical || fileCanonical.startsWith("$appDirCanonical${File.separator}"))
            ) {
                throw IOException(
                    "Некорректный путь APPIMAGE (${file.absolutePath}): указывает внутрь смонтированного APPDIR ($appDirCanonical)"
                )
            }
        }
        if (!isLikelyAppImageFile(file)) {
            throw IOException(
                "Некорректный путь APPIMAGE (${file.absolutePath}): файл не является образом AppImage"
            )
        }
        return file
    }

    /**
     * Проверяет, что файл является образом AppImage — либо по стандартной сигнатуре AppImage
     * в ELF-заголовке (`\x7FELF` на смещении 0 и `'A', 'I'` на смещении 8, что работает даже если
     * пользователь переименовал файл без расширения `.AppImage`), либо по расширению `.AppImage`.
     */
    internal fun isLikelyAppImageFile(file: File): Boolean {
        if (file.name.endsWith(".AppImage", ignoreCase = true)) return true
        return runCatching {
            file.inputStream().use { input ->
                val header = ByteArray(11)
                val read = input.read(header)
                read >= 10 &&
                    header[0] == 0x7F.toByte() &&
                    header[1] == 'E'.code.toByte() &&
                    header[2] == 'L'.code.toByte() &&
                    header[3] == 'F'.code.toByte() &&
                    header[8] == 'A'.code.toByte() &&
                    header[9] == 'I'.code.toByte()
            }
        }.getOrDefault(false)
    }

    internal fun relaunchLinuxAppImageAndAwaitReady(
        targetFile: File,
        timeoutMs: Long = STARTUP_CONFIRM_TIMEOUT_MS,
    ) {
        runCatching { Files.deleteIfExists(startupMarkerFile.toPath()) }
        val process = ProcessBuilder(targetFile.absolutePath).start()
        awaitStartupMarker(timeoutMs = timeoutMs, spawnedProcess = process)
    }

    internal fun awaitStartupMarker(
        timeoutMs: Long,
        spawnedProcess: Process?,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (startupMarkerFile.isFile) {
                runCatching { Files.deleteIfExists(startupMarkerFile.toPath()) }
                return
            }
            if (spawnedProcess != null && !spawnedProcess.isAlive) {
                throw IOException(
                    "Обновлённое приложение завершилось до окончания инициализации (код ${spawnedProcess.exitValue()})"
                )
            }
            Thread.sleep(50)
        }
        spawnedProcess?.destroyForcibly()
        throw IOException(
            "Обновлённое приложение не подтвердило успешную инициализацию в течение ${timeoutMs / 1000} с"
        )
    }

    private fun installWindowsExeAndRestart(downloadedFile: File) {
        ProcessBuilder(downloadedFile.absolutePath).start()
        shutdownApplication()
    }

    private fun shutdownApplication() {
        Platform.runLater {
            Platform.exit()
            exitProcess(0)
        }
    }

    data class CommandResult(val exitCode: Int, val output: String)

    private fun runCommand(command: List<String>): CommandResult {
        logger.info("Executing update command: {}", command.joinToString(" "))
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        val exitCode = process.waitFor()
        return CommandResult(exitCode = exitCode, output = output)
    }

    private fun fetchText(url: String): String {
        val conn = openConnectionFollowingRedirects(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException("HTTP $code при обращении к $url")
            }
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun openConnectionFollowingRedirects(url: String, maxRedirects: Int = 5): HttpURLConnection {
        var currentUrl = url
        repeat(maxRedirects) {
            val conn = (URI.create(currentUrl).toURL().openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "DatabaseCopier/${AppInfo.version}")
            }
            val code = conn.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (!location.isNullOrBlank()) {
                    currentUrl = URI.create(currentUrl).resolve(location).toString()
                    return@repeat
                }
            }
            return conn
        }
        throw IOException("Слишком много перенаправлений при обращении к $url")
    }
}
