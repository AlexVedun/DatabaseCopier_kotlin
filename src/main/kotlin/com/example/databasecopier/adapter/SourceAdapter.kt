package com.example.databasecopier.adapter

import kotlinx.serialization.json.JsonElement

interface SourceAdapter {
    fun connect()
    fun listTables(): Map<String, Long?>
    fun getTableStructure(table: String): TableStructure
    fun getForeignKeys(table: String): List<ForeignKeyRef>
    fun countRows(table: String): Long?
    /** Имена представлений (views) источника. Для дамп-источников всегда пусто — парсер дампов
     *  не индексирует CREATE VIEW (см. Шаг 13 инструкции). */
    fun listViews(): List<String>
    /** Тело SELECT-запроса view как есть в источнике — переносится на target только когда
     *  диалекты совпадают, см. TargetAdapter.createView(). */
    fun getViewDefinition(view: String): String
    fun readBatch(table: String, cursor: JsonElement?, batchSize: Int): BatchResult
    fun close()
}
