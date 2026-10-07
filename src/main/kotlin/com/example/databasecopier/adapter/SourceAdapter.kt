package com.example.databasecopier.adapter

import kotlinx.serialization.json.JsonElement

interface SourceAdapter {
    fun connect()
    fun listTables(): Map<String, Long?>
    fun getTableStructure(table: String): TableStructure
    fun getForeignKeys(table: String): List<ForeignKeyRef>
    /** Точное количество строк; вызывается только перед копированием выбранной таблицы. */
    fun countRows(table: String): Long?
    /**
     * Количество строк для прогресса копирования. Реализация может отказаться от дорогого
     * COUNT(*) и вернуть каталожную оценку с exact=false.
     */
    fun countRowsForCopy(table: String, estimatedRows: Long?): RowCountResult {
        val exact = countRows(table)
        return RowCountResult(exact ?: estimatedRows, exact != null)
    }
    /** Имена представлений (views) источника. Для дамп-источников всегда пусто — парсер дампов
     *  не индексирует CREATE VIEW (см. Шаг 13 инструкции). */
    fun listViews(): List<String>
    /** Тело SELECT-запроса view как есть в источнике — переносится на target только когда
     *  диалекты совпадают, см. TargetAdapter.createView(). */
    fun getViewDefinition(view: String): String
    /** Хранимые процедуры источника (и функции — только для MySQL, см. RoutineKind). Пусто для
     *  дамп-источников и для SQLite (там их нет вовсе). */
    fun listRoutines(): List<RoutineRef>
    /** Полный текст CREATE PROCEDURE/CREATE FUNCTION как есть в источнике — переносится на target
     *  только когда диалекты совпадают (синтаксис тела процедуры почти никогда не портируется
     *  между диалектами даже частично, в отличие от view), см. TargetAdapter.createRoutine(). */
    fun getRoutineDefinition(routine: RoutineRef): String
    fun readBatch(table: String, cursor: JsonElement?, batchSize: Int): BatchResult
    fun close()
}

data class RowCountResult(val value: Long?, val exact: Boolean)
