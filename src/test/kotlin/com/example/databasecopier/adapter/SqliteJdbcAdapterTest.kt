package com.example.databasecopier.adapter

import kotlin.io.path.createTempFile

/**
 * SQLite не требует Testcontainers — источник и приёмник это просто локальный файл, что и
 * подтверждает предположение инструкции: "должно быть почти бесплатно при правильно
 * спроектированном JdbcSourceAdapter/JdbcTargetAdapter — просто другой JDBC URL".
 */
class SqliteJdbcAdapterTest : JdbcAdapterTestBase() {

    private val dbFile = createTempFile("sqlite-adapter-test-", ".sqlite").toFile().apply {
        delete()
        deleteOnExit()
    }

    override fun config() = ConnectionConfig(
        type = DbType.SQLITE,
        database = dbFile.absolutePath,
    )

    override fun rawJdbcUrl(): String = "jdbc:sqlite:${dbFile.absolutePath}"
}
