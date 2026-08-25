package com.example.databasecopier.dump

import com.example.databasecopier.adapter.BatchResult
import com.example.databasecopier.adapter.ForeignKeyRef
import com.example.databasecopier.adapter.SourceAdapter
import com.example.databasecopier.adapter.TableStructure
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.RandomAccessFile

/**
 * Источник данных — SQL-дамп на диске, читаемый потоково (без разворачивания во временную БД,
 * см. Шаг 5.3 инструкции). Единая реализация для обоих диалектов, параметризованная [dialect];
 * `MysqlDumpSourceAdapter`/`PostgresDumpSourceAdapter` — именованные обёртки поверх неё.
 */
class DumpSourceAdapter(private val file: File, private val dialect: DumpDialect) : SourceAdapter {

    private lateinit var raf: RandomAccessFile
    private lateinit var reader: RafByteReader
    private lateinit var index: Map<String, TableDumpInfo>

    private val useBackslashEscape = dialect == DumpDialect.MYSQL

    override fun connect() {
        raf = RandomAccessFile(file, "r")
        index = DumpIndexer.buildIndex(raf, dialect)
        reader = RafByteReader(raf)
    }

    override fun listTables(): Map<String, Long?> = index.mapValues { it.value.rowCount }

    override fun getTableStructure(table: String): TableStructure =
        index[table]?.structure ?: error("Table not found in dump: $table")

    override fun getForeignKeys(table: String): List<ForeignKeyRef> = index[table]?.foreignKeys ?: emptyList()

    override fun countRows(table: String): Long? = index[table]?.rowCount

    override fun readBatch(table: String, cursor: JsonElement?, batchSize: Int): BatchResult {
        val info = index[table] ?: error("Table not found in dump: $table")
        val startOffset = cursorOffset(cursor) ?: info.dataStartOffset
        if (startOffset >= info.dataEndOffset) return BatchResult(emptyList(), null)

        return when (info.format) {
            DataFormat.INSERT -> readInsertBatch(info, startOffset, batchSize)
            DataFormat.COPY -> readCopyBatch(info, startOffset, batchSize)
        }
    }

    private fun readInsertBatch(info: TableDumpInfo, startOffset: Long, batchSize: Int): BatchResult {
        reader.seek(startOffset)
        val rows = mutableListOf<Map<String, Any?>>()
        val fallbackColumns = info.structure.columns.map { it.name }

        while (rows.size < batchSize && reader.position() < info.dataEndOffset) {
            val stmt = SqlText.readNextStatement(reader, useBackslashEscape) ?: break
            val parsed = InsertParser.parse(stmt, useBackslashEscape)
            val columns = parsed.columns ?: fallbackColumns
            parsed.rows.forEach { values -> rows.add(columns.zip(values).toMap()) }
        }

        val nextCursor = if (reader.position() >= info.dataEndOffset) null else cursorOf(reader.position())
        return BatchResult(rows, nextCursor)
    }

    private fun readCopyBatch(info: TableDumpInfo, startOffset: Long, batchSize: Int): BatchResult {
        reader.seek(startOffset)
        val rows = mutableListOf<Map<String, Any?>>()
        val columns = info.copyColumns ?: info.structure.columns.map { it.name }
        var reachedEnd = false

        while (rows.size < batchSize) {
            val line = reader.readLine()
            if (line == null || line.trim() == CopyBlockParser.TERMINATOR) {
                reachedEnd = true
                break
            }
            val values = CopyBlockParser.parseDataLine(line)
            rows.add(columns.zip(values).toMap())
        }

        val nextCursor = if (reachedEnd || reader.position() >= info.dataEndOffset) null else cursorOf(reader.position())
        return BatchResult(rows, nextCursor)
    }

    private fun cursorOffset(cursor: JsonElement?): Long? =
        (cursor as? JsonObject)?.get("value")?.jsonPrimitive?.content?.toLong()

    private fun cursorOf(offset: Long): JsonElement = buildJsonObject {
        put("type", JsonPrimitive("byte_offset"))
        put("value", JsonPrimitive(offset.toString()))
    }

    override fun close() {
        if (::raf.isInitialized) raf.close()
    }
}
