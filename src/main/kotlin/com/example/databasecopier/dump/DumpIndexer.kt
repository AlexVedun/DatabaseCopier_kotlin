package com.example.databasecopier.dump

import com.example.databasecopier.adapter.ForeignKeyRef
import com.example.databasecopier.adapter.TableStructure
import java.io.RandomAccessFile

/**
 * Один линейный проход по файлу дампа: находит `CREATE TABLE`, вычисляет структуру/FK, и для
 * каждой последующей секции `INSERT`/`COPY` того же имени таблицы — байтовый диапазон данных и
 * количество строк (без полной распаковки значений — см. Шаг 5.3 инструкции). Предполагается
 * типичная для mysqldump/pg_dump раскладка: секции таблиц идут одна за другой, не перемешиваясь.
 */
object DumpIndexer {

    private class Builder(var structure: TableStructure, var foreignKeys: List<ForeignKeyRef>) {
        var format: DataFormat = DataFormat.INSERT
        var dataStartOffset: Long = -1L
        var dataEndOffset: Long = -1L
        var rowCount: Long = 0L
        var copyColumns: List<String>? = null

        fun toInfo() = TableDumpInfo(
            structure = structure,
            foreignKeys = foreignKeys,
            format = format,
            dataStartOffset = if (dataStartOffset == -1L) 0L else dataStartOffset,
            dataEndOffset = if (dataEndOffset == -1L) 0L else dataEndOffset,
            rowCount = rowCount,
            copyColumns = copyColumns,
        )
    }

    fun buildIndex(raf: RandomAccessFile, dialect: DumpDialect): Map<String, TableDumpInfo> {
        val reader = RafByteReader(raf)
        val useBackslash = dialect == DumpDialect.MYSQL
        val builders = LinkedHashMap<String, Builder>()

        while (true) {
            val stmtStart = reader.position()
            val stmt = SqlText.readNextStatement(reader, useBackslash) ?: break
            val clean = SqlText.stripLeadingComments(stmt).trim()
            if (clean.isEmpty()) continue
            val upper = clean.uppercase()

            when {
                upper.startsWith("CREATE TABLE") -> {
                    val parsed = runCatching { CreateTableParser.parse(clean, dialect) }.getOrNull() ?: continue
                    builders[parsed.structure.name] = Builder(parsed.structure, parsed.foreignKeys)
                }
                upper.startsWith("INSERT INTO") -> {
                    val insert = runCatching { InsertParser.parse(clean, useBackslash) }.getOrNull() ?: continue
                    val builder = builders.getOrPut(insert.tableName) {
                        Builder(TableStructure(insert.tableName, emptyList(), emptyList()), emptyList())
                    }
                    builder.format = DataFormat.INSERT
                    if (builder.dataStartOffset == -1L) builder.dataStartOffset = stmtStart
                    builder.dataEndOffset = reader.position()
                    builder.rowCount += insert.rows.size
                }
                upper.startsWith("COPY") && upper.contains("FROM STDIN") -> {
                    val header = CopyBlockParser.parseHeader(clean) ?: continue
                    val builder = builders.getOrPut(header.tableName) {
                        Builder(TableStructure(header.tableName, emptyList(), emptyList()), emptyList())
                    }
                    builder.format = DataFormat.COPY
                    builder.copyColumns = header.columns
                    // readNextStatement() останавливается сразу на ';' после "FROM stdin" — до
                    // первой строки данных остаётся непрочитанный перевод строки, который иначе
                    // readLine() примет за пустую строку данных.
                    reader.readLine()
                    if (builder.dataStartOffset == -1L) builder.dataStartOffset = reader.position()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.trim() == CopyBlockParser.TERMINATOR) break
                        builder.rowCount++
                    }
                    builder.dataEndOffset = reader.position()
                }
                else -> Unit // SET/LOCK/UNLOCK/комментарии и т.п. — не влияют на копирование данных
            }
        }

        return builders.mapValues { it.value.toInfo() }
    }
}
