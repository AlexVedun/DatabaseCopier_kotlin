package com.example.databasecopier.adapter

import kotlinx.serialization.json.JsonElement

interface SourceAdapter {
    fun connect()
    fun listTables(): Map<String, Long?>
    fun getTableStructure(table: String): TableStructure
    fun getForeignKeys(table: String): List<ForeignKeyRef>
    fun countRows(table: String): Long?
    fun readBatch(table: String, cursor: JsonElement?, batchSize: Int): BatchResult
    fun close()
}
