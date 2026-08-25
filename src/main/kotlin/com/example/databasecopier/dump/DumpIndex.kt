package com.example.databasecopier.dump

import com.example.databasecopier.adapter.ForeignKeyRef
import com.example.databasecopier.adapter.TableStructure

enum class DataFormat { INSERT, COPY }

data class TableDumpInfo(
    val structure: TableStructure,
    val foreignKeys: List<ForeignKeyRef>,
    val format: DataFormat,
    val dataStartOffset: Long,
    val dataEndOffset: Long,
    val rowCount: Long,
    /** Порядок колонок для COPY-блока (фиксирован в заголовке `COPY table (cols) FROM stdin`). */
    val copyColumns: List<String>?,
)
