package com.example.databasecopier

import org.jetbrains.exposed.sql.Table

object Connections : Table("connections") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 255)
    val type = varchar("type", 20) // mysql / postgresql / sqlserver / sqlite
    val host = varchar("host", 255).nullable()
    val port = integer("port").nullable()
    val database = varchar("database", 255).nullable()
    val username = varchar("username", 255).nullable()
    val passwordEncrypted = text("password_encrypted").nullable()
    val extraOptionsJson = text("extra_options_json").nullable()

    override val primaryKey = PrimaryKey(id)
}

object CopySessions : Table("copy_sessions") {
    val id = integer("id").autoIncrement()
    val name = varchar("name", 255)
    val status = varchar("status", 20) // draft/running/paused/completed/cancelled/failed
    val sourceType = varchar("source_type", 20) // connection/dump
    val sourceConnectionId = integer("source_connection_id").references(Connections.id).nullable()
    val sourceDumpPath = varchar("source_dump_path", 1024).nullable()
    val sourceDumpDialect = varchar("source_dump_dialect", 20).nullable() // mysql/postgresql
    val targetConnectionId = integer("target_connection_id").references(Connections.id)
    val copyMode = varchar("copy_mode", 30) // structure_only/structure_and_data
    val batchSize = integer("batch_size").default(1000)
    val lastError = text("last_error").nullable()
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")

    override val primaryKey = PrimaryKey(id)
}

object CopySessionTables : Table("copy_session_tables") {
    val id = integer("id").autoIncrement()
    val copySessionId = integer("copy_session_id").references(CopySessions.id)
    val sourceTableName = varchar("table_name", 255)
    val isSelected = bool("is_selected").default(true)
    val status = varchar("status", 20) // pending/in_progress/done/skipped/failed
    val rowsTotal = long("rows_total").nullable()
    val rowsCopied = long("rows_copied").default(0)
    val cursorJson = text("cursor_json").nullable()
    val structureCopied = bool("structure_copied").default(false)
    val foreignKeysCopied = bool("foreign_keys_copied").default(false)
    val indexesCopied = bool("indexes_copied").default(false)

    override val primaryKey = PrimaryKey(id)
}
