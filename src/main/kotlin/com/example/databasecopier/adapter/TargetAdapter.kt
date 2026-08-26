package com.example.databasecopier.adapter

interface TargetAdapter {
    fun connect()
    fun createTable(structure: TableStructure)
    fun createForeignKeys(table: String, foreignKeys: List<ForeignKeyRef>)
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
