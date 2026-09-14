package com.example.databasecopier.i18n

import com.example.databasecopier.getAppDataDir
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

enum class AppLanguage(val locale: Locale, val displayName: String) {
    ENGLISH(Locale.ENGLISH, "English"),
    UKRAINIAN(Locale.forLanguageTag("uk"), "Українська"),
    RUSSIAN(Locale.forLanguageTag("ru"), "Русский"),
}

/**
 * Простой доступ к переведённым строкам приложения (resource bundle `i18n/messages_<lang>.properties`,
 * см. src/main/resources). Выбранный язык сохраняется в отдельном файле в каталоге данных
 * приложения и применяется при следующем запуске (см. main()/Locale.setDefault) — приложение не
 * перестраивает уже показанный UI на лету при смене языка (см. LanguageMenu), только на старте.
 */
object Messages {

    private val languageFile = getAppDataDir().resolve("language.txt")

    var current: AppLanguage = loadSaved()
        private set

    private var bundle: ResourceBundle = loadBundle(current)

    fun get(key: String, vararg args: Any): String {
        val pattern = if (bundle.containsKey(key)) bundle.getString(key) else key
        return if (args.isEmpty()) pattern else MessageFormat.format(pattern, *args)
    }

    /** Метка для статуса сессии/представления/процедуры (draft/running/paused/completed/
     *  cancelled/failed/pending/done/manual_adaptation_required/skipped) — если для конкретного
     *  значения перевода нет, возвращает исходную строку как есть, а не молча теряет информацию. */
    fun status(rawStatus: String): String {
        val key = "status.$rawStatus"
        return if (bundle.containsKey(key)) bundle.getString(key) else rawStatus
    }

    fun setLanguage(language: AppLanguage) {
        current = language
        bundle = loadBundle(language)
        runCatching {
            languageFile.parentFile.mkdirs()
            languageFile.writeText(language.name)
        }
    }

    private fun loadBundle(language: AppLanguage): ResourceBundle =
        ResourceBundle.getBundle("i18n.messages", language.locale)

    private fun loadSaved(): AppLanguage {
        val saved = runCatching { languageFile.readText().trim() }.getOrNull()
        return AppLanguage.entries.find { it.name == saved } ?: AppLanguage.ENGLISH
    }
}
