package com.example.databasecopier.adapter

import kotlinx.serialization.json.JsonElement

enum class DbType { MYSQL, POSTGRESQL, SQLSERVER, SQLITE }

data class ConnectionConfig(
    val type: DbType,
    val host: String? = null,
    val port: Int? = null,
    val database: String,
    val username: String? = null,
    val password: String? = null,
)

enum class LogicalType { INTEGER, BIGINT, VARCHAR, TEXT, DECIMAL, BOOLEAN, DATE, DATETIME, JSON, UUID }

data class ColumnDef(
    val name: String,
    val type: LogicalType,
    val nullable: Boolean,
    val autoIncrement: Boolean = false,
    // Сырое SQL-выражение дефолта как есть в источнике; для autoIncrement-колонок всегда null —
    // такие колонки получают на target нативный механизм автоинкремента, а не скопированный DEFAULT.
    val defaultValue: String? = null,
    // Значимо только при копировании между одинаковыми СУБД — имя collation одного диалекта, как
    // правило, не является валидным именем в другом (см. JdbcTargetAdapter.buildColumnSql).
    val collation: String? = null,
    val comment: String? = null,
)

data class IndexDef(val name: String, val columns: List<String>, val unique: Boolean)

data class CheckConstraintDef(val name: String, val expression: String)

data class TableStructure(
    val name: String,
    val columns: List<ColumnDef>,
    val primaryKey: List<String>,
    // Не включает PK — только обычные и unique-индексы, созданные отдельно (CREATE INDEX/UNIQUE
    // KEY, а не часть PRIMARY KEY).
    val indexes: List<IndexDef> = emptyList(),
    val checkConstraints: List<CheckConstraintDef> = emptyList(),
    val comment: String? = null,
)

enum class ReferentialAction { CASCADE, SET_NULL, RESTRICT, NO_ACTION, SET_DEFAULT }

data class ForeignKeyRef(
    val columnName: String,
    val referencedTable: String,
    val referencedColumn: String,
    val onDelete: ReferentialAction = ReferentialAction.NO_ACTION,
    val onUpdate: ReferentialAction = ReferentialAction.NO_ACTION,
)

data class BatchResult(val rows: List<Map<String, Any?>>, val nextCursor: JsonElement?)
