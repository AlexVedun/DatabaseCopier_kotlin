package com.example.databasecopier.adapter

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

class JdbcSourceAdapter(private val config: ConnectionConfig) : SourceAdapter {

    private lateinit var connection: Connection

    override fun connect() {
        connection = DriverManager.getConnection(buildJdbcUrl(config), config.username, config.password)
    }

    override fun listTables(): Map<String, Long?> {
        val schemaFilter = schemaClause()
        val sql = "SELECT table_name FROM information_schema.tables " +
            "WHERE table_type = 'BASE TABLE' AND $schemaFilter"
        val result = LinkedHashMap<String, Long?>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    val name = rs.getString("table_name")
                    result[name] = countRows(name)
                }
            }
        }
        return result
    }

    override fun getTableStructure(table: String): TableStructure {
        val columns = mutableListOf<ColumnDef>()
        val columnsSql = "SELECT column_name, data_type, is_nullable FROM information_schema.columns " +
            "WHERE table_name = ? AND ${schemaClause()} ORDER BY ordinal_position"
        connection.prepareStatement(columnsSql).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    columns.add(
                        ColumnDef(
                            name = rs.getString("column_name"),
                            type = TypeMapper.fromSqlType(config.type, rs.getString("data_type")),
                            nullable = rs.getString("is_nullable") == "YES",
                        )
                    )
                }
            }
        }

        val primaryKey = mutableListOf<String>()
        // constraint_name для PRIMARY KEY в MySQL всегда буквально "PRIMARY" — одинаково для
        // всех таблиц схемы, поэтому join обязан фильтроваться ещё и по table_name, иначе
        // подхватятся PK-колонки других таблиц с тем же именем constraint.
        val pkSql = "SELECT kcu.column_name FROM information_schema.table_constraints tc " +
            "JOIN information_schema.key_column_usage kcu " +
            "  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema " +
            "  AND tc.table_name = kcu.table_name " +
            "WHERE tc.constraint_type = 'PRIMARY KEY' AND tc.table_name = ? AND ${schemaClause("tc")} " +
            "ORDER BY kcu.ordinal_position"
        connection.prepareStatement(pkSql).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) primaryKey.add(rs.getString("column_name"))
            }
        }

        return TableStructure(table, columns, primaryKey)
    }

    override fun getForeignKeys(table: String): List<ForeignKeyRef> {
        val sql = when (config.type) {
            DbType.MYSQL ->
                "SELECT column_name, referenced_table_name AS referenced_table, referenced_column_name AS referenced_column " +
                    "FROM information_schema.key_column_usage " +
                    "WHERE table_name = ? AND referenced_table_name IS NOT NULL AND ${schemaClause()}"
            DbType.POSTGRESQL ->
                "SELECT kcu.column_name, ccu.table_name AS referenced_table, ccu.column_name AS referenced_column " +
                    "FROM information_schema.table_constraints tc " +
                    "JOIN information_schema.key_column_usage kcu " +
                    "  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema " +
                    "JOIN information_schema.constraint_column_usage ccu " +
                    "  ON tc.constraint_name = ccu.constraint_name AND tc.table_schema = ccu.table_schema " +
                    "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = ? AND ${schemaClause("tc")}"
            else -> throw UnsupportedOperationException("getForeignKeys not implemented yet for ${config.type}")
        }
        val result = mutableListOf<ForeignKeyRef>()
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(
                        ForeignKeyRef(
                            columnName = rs.getString("column_name"),
                            referencedTable = rs.getString("referenced_table"),
                            referencedColumn = rs.getString("referenced_column"),
                        )
                    )
                }
            }
        }
        return result
    }

    override fun countRows(table: String): Long? {
        return try {
            connection.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM ${quote(table)}").use { rs ->
                    rs.next()
                    rs.getLong(1)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun readBatch(table: String, cursor: JsonElement?, batchSize: Int): BatchResult {
        val structure = getTableStructure(table)
        val pkColumn = structure.primaryKey.firstOrNull()

        return if (pkColumn != null) {
            val pkType = structure.columns.first { it.name == pkColumn }.type
            readBatchByPrimaryKey(table, pkColumn, pkType, cursor, batchSize)
        } else {
            readBatchByOffset(table, cursor, batchSize)
        }
    }

    private fun readBatchByPrimaryKey(
        table: String,
        pkColumn: String,
        pkType: LogicalType,
        cursor: JsonElement?,
        batchSize: Int,
    ): BatchResult {
        val lastValue = cursor?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("value")?.jsonPrimitive?.content }
        val sql = if (lastValue != null) {
            "SELECT * FROM ${quote(table)} WHERE ${quote(pkColumn)} > ? ORDER BY ${quote(pkColumn)} LIMIT ?"
        } else {
            "SELECT * FROM ${quote(table)} ORDER BY ${quote(pkColumn)} LIMIT ?"
        }

        val rows = mutableListOf<Map<String, Any?>>()
        var lastPk: Any? = null

        connection.prepareStatement(sql).use { ps ->
            var idx = 1
            // Курсор всегда хранится как строка в JSON, но PostgreSQL (в отличие от MySQL) не
            // приводит типы неявно в сравнении — нужно биндить значение как реальный тип колонки.
            if (lastValue != null) {
                when (pkType) {
                    LogicalType.INTEGER -> ps.setInt(idx++, lastValue.toInt())
                    LogicalType.BIGINT -> ps.setLong(idx++, lastValue.toLong())
                    else -> ps.setString(idx++, lastValue)
                }
            }
            ps.setInt(idx, batchSize)
            ps.executeQuery().use { rs ->
                val meta = rs.metaData
                while (rs.next()) {
                    rows.add(rowToMap(rs, meta))
                    lastPk = rs.getObject(pkColumn)
                }
            }
        }

        val nextCursor = if (rows.size < batchSize || lastPk == null) {
            null
        } else {
            buildJsonObject {
                put("type", JsonPrimitive("primary_key"))
                put("column", JsonPrimitive(pkColumn))
                put("value", JsonPrimitive(lastPk.toString()))
            }
        }

        return BatchResult(rows, nextCursor)
    }

    private fun readBatchByOffset(table: String, cursor: JsonElement?, batchSize: Int): BatchResult {
        val offset = cursor?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("value")?.jsonPrimitive?.content?.toLong() } ?: 0L
        val sql = "SELECT * FROM ${quote(table)} LIMIT ? OFFSET ?"

        val rows = mutableListOf<Map<String, Any?>>()
        connection.prepareStatement(sql).use { ps ->
            ps.setInt(1, batchSize)
            ps.setLong(2, offset)
            ps.executeQuery().use { rs ->
                val meta = rs.metaData
                while (rs.next()) rows.add(rowToMap(rs, meta))
            }
        }

        val nextCursor = if (rows.size < batchSize) {
            null
        } else {
            buildJsonObject {
                put("type", JsonPrimitive("offset"))
                put("value", JsonPrimitive(offset + rows.size))
            }
        }

        return BatchResult(rows, nextCursor)
    }

    private fun rowToMap(rs: ResultSet, meta: java.sql.ResultSetMetaData): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        for (i in 1..meta.columnCount) {
            map[meta.getColumnName(i)] = rs.getObject(i)
        }
        return map
    }

    private fun schemaClause(alias: String? = null): String {
        val prefix = if (alias != null) "$alias." else ""
        return when (config.type) {
            DbType.MYSQL -> "${prefix}table_schema = '${config.database}'"
            DbType.POSTGRESQL -> "${prefix}table_schema = 'public'"
            else -> "1=1"
        }
    }

    private fun quote(identifier: String): String = when (config.type) {
        DbType.MYSQL -> "`$identifier`"
        DbType.POSTGRESQL -> "\"$identifier\""
        else -> identifier
    }

    override fun close() {
        if (::connection.isInitialized) connection.close()
    }
}
