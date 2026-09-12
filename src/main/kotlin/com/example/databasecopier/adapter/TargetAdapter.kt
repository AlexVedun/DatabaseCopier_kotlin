package com.example.databasecopier.adapter

interface TargetAdapter {
    fun connect()
    fun createTable(structure: TableStructure)
    fun createForeignKeys(table: String, foreignKeys: List<ForeignKeyRef>)
    /** Индексы и CHECK-ограничения создаются здесь, а не в createTable() — после загрузки данных
     *  таблицы это заметно быстрее, чем поддерживать индекс при каждой вставке батча. */
    fun createIndexesAndConstraints(structure: TableStructure)
    /** definition — тело SELECT как есть у источника; вызывающая сторона (CopyRunner) сама решает,
     *  безопасно ли переносить его без трансляции (см. Шаг 13 инструкции — только между одинаковыми
     *  диалектами), этот метод просто выполняет DROP VIEW IF EXISTS + CREATE VIEW. */
    fun createView(name: String, definition: String)
    /** definition — полный текст CREATE PROCEDURE/CREATE FUNCTION как есть у источника (см.
     *  SourceAdapter.getRoutineDefinition) — вызывающая сторона (CopyRunner) сама решает,
     *  безопасно ли его переносить (только между одинаковыми диалектами). */
    fun createRoutine(routine: RoutineRef, definition: String)
    /** Синхронизирует счётчик автоинкремента/identity/sequence, чтобы новые строки после
     *  копирования не конфликтовали по PK с уже скопированными. Вызывается только для колонок
     *  с [ColumnDef.autoIncrement] после того, как все строки таблицы уже скопированы. */
    fun syncAutoIncrement(table: String, column: String, maxValue: Long)
    fun insertBatch(table: String, rows: List<Map<String, Any?>>)
    fun tableExists(table: String): Boolean
    fun disableForeignKeyChecks()
    fun enableForeignKeyChecks()
    fun close()
}
