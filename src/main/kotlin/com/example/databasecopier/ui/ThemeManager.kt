package com.example.databasecopier.ui

import atlantafx.base.theme.PrimerDark
import atlantafx.base.theme.PrimerLight
import com.example.databasecopier.getAppDataDir
import javafx.application.Application
import javafx.application.ColorScheme
import javafx.application.Platform
import javafx.beans.value.ChangeListener

enum class AppThemeMode(val messageKey: String) {
    SYSTEM("theme.system"),
    LIGHT("theme.light"),
    DARK("theme.dark"),
}

/**
 * Управляет темой оформления приложения (AtlantaFX PrimerLight / PrimerDark).
 *
 * По умолчанию используется режим [AppThemeMode.SYSTEM], который автоматически подстраивается под
 * системную тему ОС при старте и реагирует на её переключение налету:
 * - через штатный API JavaFX 22+ `Platform.getPreferences().colorSchemeProperty()` (работает на
 *   Windows, macOS и при смене классической GTK-темы на Linux);
 * - дополнительно на Linux (GNOME 42+, KDE Plasma и др.) через XDG Desktop Portal / GSettings
 *   (`org.gnome.desktop.interface color-scheme` / `org.freedesktop.appearance color-scheme`), так как
 *   переключатель "Тёмный стиль" в современном GNOME меняет настройку `color-scheme` (`prefer-dark`),
 *   оставляя классическую тему GTK3 (`Adwaita`) светлой, из-за чего один только `libglassgtk3` в
 *   JavaFX не видит переключения.
 */
object ThemeManager {

    private val themeFile = getAppDataDir().resolve("theme.txt")

    var currentMode: AppThemeMode = loadSaved()
        private set

    private var initialized = false
    private var appliedDark: Boolean? = null

    private val fxColorSchemeListener = ChangeListener<ColorScheme> { _, _, _ ->
        if (currentMode == AppThemeMode.SYSTEM) {
            applyEffectiveTheme()
        }
    }

    fun init() {
        if (initialized) {
            applyEffectiveTheme()
            return
        }
        initialized = true
        Platform.getPreferences().colorSchemeProperty().addListener(fxColorSchemeListener)
        startLinuxSystemThemeMonitors()
        applyEffectiveTheme()
    }

    fun setMode(mode: AppThemeMode) {
        currentMode = mode
        runCatching {
            themeFile.parentFile.mkdirs()
            themeFile.writeText(mode.name)
        }
        applyEffectiveTheme()
    }

    fun isDarkEffective(): Boolean = when (currentMode) {
        AppThemeMode.LIGHT -> false
        AppThemeMode.DARK -> true
        AppThemeMode.SYSTEM -> detectSystemDark()
    }

    internal fun applyEffectiveTheme() {
        val dark = isDarkEffective()
        if (appliedDark == dark) return
        appliedDark = dark
        val theme = if (dark) PrimerDark() else PrimerLight()
        Application.setUserAgentStylesheet(theme.userAgentStylesheet)
    }

    internal fun detectSystemDark(
        osName: String = System.getProperty("os.name").lowercase(),
        fxColorScheme: ColorScheme? = runCatching { Platform.getPreferences().colorScheme }.getOrNull(),
        linuxPortalScheme: String? = if (osName.contains("linux")) queryLinuxPortalColorScheme() else null,
        gnomeColorScheme: String? = if (osName.contains("linux")) queryGnomeColorScheme() else null,
    ): Boolean {
        if (osName.contains("linux")) {
            parseLinuxDarkPreference(linuxPortalScheme, gnomeColorScheme)?.let { return it }
        }
        return fxColorScheme == ColorScheme.DARK
    }

    /**
     * Разбирает ответ XDG Desktop Portal (`uint32 1` = prefer-dark, `uint32 2` = prefer-light)
     * или значение `gsettings get org.gnome.desktop.interface color-scheme` (`'prefer-dark'`,
     * `'prefer-light'`, `'default'`). Возвращает `null`, если ни один источник не дал явного ответа
     * (в таком случае решение остаётся за `Platform.getPreferences().colorScheme`).
     */
    internal fun parseLinuxDarkPreference(portalOutput: String?, gnomeOutput: String?): Boolean? {
        if (gnomeOutput != null) {
            val cleaned = gnomeOutput.trim().trim('\'', '"').lowercase()
            when (cleaned) {
                "prefer-dark" -> return true
                "prefer-light" -> return false
            }
        }
        if (portalOutput != null) {
            // Ответ gdbus ReadOne для org.freedesktop.appearance color-scheme имеет вид "(<<uint32 1>>,)"
            val match = Regex("""uint32\s+(\d+)""").find(portalOutput)
            when (match?.groupValues?.get(1)?.toIntOrNull()) {
                1 -> return true
                2 -> return false
            }
        }
        if (gnomeOutput != null && gnomeOutput.trim().trim('\'', '"').equals("default", ignoreCase = true)) {
            // В GNOME значение 'default' означает светлый стиль (если сама GTK-тема не тёмная).
            return null
        }
        return null
    }

    private fun queryGnomeColorScheme(): String? = runCommandCapture(
        "gsettings", "get", "org.gnome.desktop.interface", "color-scheme"
    )

    private fun queryLinuxPortalColorScheme(): String? = runCommandCapture(
        "gdbus", "call", "--session",
        "--dest", "org.freedesktop.portal.Desktop",
        "--object-path", "/org/freedesktop/portal/desktop",
        "--method", "org.freedesktop.portal.Settings.ReadOne",
        "org.freedesktop.appearance", "color-scheme",
    )

    private fun runCommandCapture(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(false)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (process.waitFor() == 0 && output.isNotEmpty()) output else null
    }.getOrNull()

    private fun startLinuxSystemThemeMonitors() {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return

        startCommandMonitor("linux-gsettings-theme-monitor", "gsettings", "monitor", "org.gnome.desktop.interface", "color-scheme")
        startCommandMonitor(
            "linux-portal-theme-monitor",
            "gdbus", "monitor", "--session",
            "--dest", "org.freedesktop.portal.Desktop",
            "--object-path", "/org/freedesktop/portal/desktop",
        ) { line ->
            line.contains("color-scheme")
        }
    }

    private fun startCommandMonitor(
        threadName: String,
        vararg command: String,
        lineFilter: (String) -> Boolean = { true },
    ) {
        Thread({
            runCatching {
                val process = ProcessBuilder(*command)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                Runtime.getRuntime().addShutdownHook(Thread { runCatching { process.destroy() } })
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        if (lineFilter(line) && currentMode == AppThemeMode.SYSTEM) {
                            Platform.runLater { applyEffectiveTheme() }
                        }
                    }
                }
            }
        }, threadName).apply {
            isDaemon = true
            start()
        }
    }

    private fun loadSaved(): AppThemeMode {
        val saved = runCatching { themeFile.readText().trim() }.getOrNull()
        return AppThemeMode.entries.find { it.name == saved } ?: AppThemeMode.SYSTEM
    }
}
