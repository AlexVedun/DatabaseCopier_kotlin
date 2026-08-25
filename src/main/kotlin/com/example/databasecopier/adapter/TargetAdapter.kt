package com.example.databasecopier.adapter

interface TargetAdapter {
    fun connect()
    fun createTable(structure: TableStructure)
    fun insertBatch(table: String, rows: List<Map<String, Any?>>)
    fun tableExists(table: String): Boolean
    fun disableForeignKeyChecks()
    fun enableForeignKeyChecks()
    fun close()
}
