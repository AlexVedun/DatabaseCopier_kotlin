package com.example.databasecopier.ui

import com.example.databasecopier.adapter.ConnectionConfig
import com.example.databasecopier.dump.DumpDialect
import java.io.File

/** То, что настроил пользователь в SourcePanelController: живая БД или файл SQL-дампа. */
sealed interface SourceSelection {
    data class Connection(val config: ConnectionConfig, val connectionId: Int) : SourceSelection
    data class Dump(val file: File, val dialect: DumpDialect) : SourceSelection
}
