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
        val sql = if (config.type == DbType.SQLITE) {
            "SELECT name AS table_name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\'"
        } else {
            "SELECT table_name FROM information_schema.tables WHERE table_type = 'BASE TABLE' AND ${schemaClause()}"
        }
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
        if (config.type == DbType.SQLITE) return getSqliteTableStructure(table)

        val identityColumns = if (config.type == DbType.SQLSERVER) readMssqlIdentityColumns(table) else emptySet()
        val columns = mutableListOf<ColumnDef>()
        val columnsSql = if (config.type == DbType.MYSQL) {
            "SELECT column_name, data_type, is_nullable, extra, column_default FROM information_schema.columns " +
                "WHERE table_name = ? AND ${schemaClause()} ORDER BY ordinal_position"
        } else {
            "SELECT column_name, data_type, is_nullable, column_default FROM information_schema.columns " +
                "WHERE table_name = ? AND ${schemaClause()} ORDER BY ordinal_position"
        }
        connection.prepareStatement(columnsSql).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = rs.getString("column_name")
                    val rawDefault = rs.getString("column_default")
                    val autoIncrement = when (config.type) {
                        DbType.MYSQL -> rs.getString("extra")?.contains("auto_increment", ignoreCase = true) == true
                        DbType.POSTGRESQL -> rawDefault?.startsWith("nextval(") == true
                        DbType.SQLSERVER -> name in identityColumns
                        else -> false
                    }
                    columns.add(
                        ColumnDef(
                            name = name,
                            type = TypeMapper.fromSqlType(config.type, rs.getString("data_type")),
                            nullable = rs.getString("is_nullable") == "YES",
                            autoIncrement = autoIncrement,
                            // nextval(...)/identity уже подразумевают генерацию значения — обычный
                            // DEFAULT для таких колонок не нужен и не переносится.
                            defaultValue = if (autoIncrement) null else rawDefault,
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

        return TableStructure(table, columns, primaryKey, getIndexes(table), getCheckConstraints(table))
    }

    private fun getIndexes(table: String): List<IndexDef> {
        data class Row(val indexName: String, val columnName: String, val unique: Boolean, val position: Int)
        val sql = when (config.type) {
            DbType.MYSQL ->
                "SELECT index_name, column_name, non_unique = 0 AS is_unique, seq_in_index AS position " +
                    "FROM information_schema.statistics " +
                    "WHERE table_name = ? AND index_name != 'PRIMARY' AND ${schemaClause()} " +
                    "ORDER BY index_name, seq_in_index"
            DbType.POSTGRESQL ->
                "SELECT ic.relname AS index_name, a.attname AS column_name, ix.indisunique AS is_unique, " +
                    "array_position(ix.indkey, a.attnum) AS position " +
                    "FROM pg_index ix " +
                    "JOIN pg_class ic ON ic.oid = ix.indexrelid " +
                    "JOIN pg_class tc ON tc.oid = ix.indrelid " +
                    "JOIN pg_attribute a ON a.attrelid = tc.oid AND a.attnum = ANY(ix.indkey) " +
                    "WHERE tc.relname = ? AND NOT ix.indisprimary " +
                    "ORDER BY index_name, position"
            DbType.SQLSERVER ->
                "SELECT i.name AS index_name, c.name AS column_name, i.is_unique, ic.key_ordinal AS position " +
                    "FROM sys.indexes i " +
                    "JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id " +
                    "JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id " +
                    "JOIN sys.tables t ON t.object_id = i.object_id " +
                    "WHERE t.name = ? AND i.is_primary_key = 0 AND i.name IS NOT NULL " +
                    "ORDER BY i.name, ic.key_ordinal"
            else -> return emptyList()
        }
        val rows = mutableListOf<Row>()
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    rows.add(Row(rs.getString("index_name"), rs.getString("column_name"), rs.getBoolean("is_unique"), rs.getInt("position")))
                }
            }
        }
        return rows.groupBy { it.indexName }.map { (name, cols) ->
            IndexDef(name, cols.sortedBy { it.position }.map { it.columnName }, cols.first().unique)
        }
    }

    private fun getCheckConstraints(table: String): List<CheckConstraintDef> {
        val sql = when (config.type) {
            DbType.MYSQL, DbType.POSTGRESQL ->
                "SELECT tc.constraint_name, cc.check_clause FROM information_schema.table_constraints tc " +
                    "JOIN information_schema.check_constraints cc " +
                    "  ON tc.constraint_name = cc.constraint_name AND tc.table_schema = cc.constraint_schema " +
                    "WHERE tc.constraint_type = 'CHECK' AND tc.table_name = ? AND ${schemaClause("tc")}"
            DbType.SQLSERVER ->
                "SELECT cc.name AS constraint_name, cc.definition AS check_clause " +
                    "FROM sys.check_constraints cc JOIN sys.tables t ON cc.parent_object_id = t.object_id " +
                    "WHERE t.name = ?"
            else -> return emptyList()
        }
        // Postgres 12+ отражает обычный NOT NULL на колонке как отдельный синтетический CHECK
        // ("col IS NOT NULL", имя вида "2200_16384_1_not_null") — это уже покрыто ColumnDef.nullable,
        // поэтому такие записи отфильтровываются, чтобы не создавать избыточный/дублирующий CHECK.
        val notNullPattern = Regex("""(?i)^"?[\w]+"?\s+IS\s+NOT\s+NULL$""")
        val result = mutableListOf<CheckConstraintDef>()
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val clause = rs.getString("check_clause")
                    if (notNullPattern.matches(clause.trim())) continue
                    result.add(CheckConstraintDef(rs.getString("constraint_name"), clause))
                }
            }
        }
        return result
    }

    /** SQLite не поддерживает `information_schema` — структура читается через `PRAGMA table_info`. */
    private fun getSqliteTableStructure(table: String): TableStructure {
        data class Raw(val name: String, val type: String, val notNull: Boolean, val default: String?, val pk: Int)
        val raws = mutableListOf<Raw>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info(${quote(table)})").use { rs ->
                while (rs.next()) {
                    raws.add(
                        Raw(
                            name = rs.getString("name"),
                            type = rs.getString("type") ?: "",
                            notNull = rs.getInt("notnull") != 0,
                            default = rs.getString("dflt_value"),
                            pk = rs.getInt("pk"),
                        )
                    )
                }
            }
        }
        // В SQLite единственная INTEGER-колонка PK — это alias rowid, который автоинкрементится
        // сам по себе (без явного ключевого слова AUTOINCREMENT в исходном CREATE TABLE).
        val singlePk = raws.filter { it.pk > 0 }.singleOrNull()
        val columns = raws.map { r ->
            val autoIncrement = singlePk?.name == r.name && r.type.contains("int", ignoreCase = true)
            ColumnDef(
                name = r.name,
                type = TypeMapper.fromSqlType(DbType.SQLITE, r.type),
                nullable = !r.notNull,
                autoIncrement = autoIncrement,
                defaultValue = if (autoIncrement) null else r.default,
            )
        }
        val primaryKey = raws.filter { it.pk > 0 }.sortedBy { it.pk }.map { it.name }
        return TableStructure(table, columns, primaryKey, getSqliteIndexes(table), getSqliteCheckConstraints(table))
    }

    private fun getSqliteIndexes(table: String): List<IndexDef> {
        data class IndexMeta(val name: String, val unique: Boolean, val origin: String)
        val indexMetas = mutableListOf<IndexMeta>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA index_list(${quote(table)})").use { rs ->
                while (rs.next()) {
                    indexMetas.add(IndexMeta(rs.getString("name"), rs.getInt("unique") != 0, rs.getString("origin")))
                }
            }
        }
        // origin='pk' — неявный индекс, который SQLite сам создаёт для PRIMARY KEY(...);
        // он уже отражён в structure.primaryKey и не должен дублироваться как обычный индекс.
        return indexMetas.filter { it.origin != "pk" }.map { meta ->
            val columns = mutableListOf<String>()
            connection.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA index_info(${quote(meta.name)})").use { rs ->
                    while (rs.next()) columns.add(rs.getString("name"))
                }
            }
            IndexDef(meta.name, columns, meta.unique)
        }
    }

    /** SQLite не хранит CHECK-ограничения отдельным каталогом — извлекаются регэкспом из
     *  исходного текста CREATE TABLE, хранящегося в sqlite_master.sql. */
    private fun getSqliteCheckConstraints(table: String): List<CheckConstraintDef> {
        val ddl = connection.prepareStatement("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?").use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString("sql") else null }
        } ?: return emptyList()
        return Regex("""(?i)CHECK\s*\(([^()]*(?:\([^()]*\)[^()]*)*)\)""").findAll(ddl)
            .mapIndexed { idx, m -> CheckConstraintDef("chk_${table}_$idx", m.groupValues[1].trim()) }
            .toList()
    }

    private fun readMssqlIdentityColumns(table: String): Set<String> {
        val result = mutableSetOf<String>()
        val sql = "SELECT c.name FROM sys.identity_columns c JOIN sys.tables t ON c.object_id = t.object_id WHERE t.name = ?"
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs -> while (rs.next()) result.add(rs.getString("name")) }
        }
        return result
    }

    override fun getForeignKeys(table: String): List<ForeignKeyRef> {
        if (config.type == DbType.SQLITE) {
            val result = mutableListOf<ForeignKeyRef>()
            connection.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA foreign_key_list(${quote(table)})").use { rs ->
                    while (rs.next()) {
                        result.add(
                            ForeignKeyRef(
                                columnName = rs.getString("from"),
                                referencedTable = rs.getString("table"),
                                referencedColumn = rs.getString("to"),
                                onDelete = parseAction(rs.getString("on_delete")),
                                onUpdate = parseAction(rs.getString("on_update")),
                            )
                        )
                    }
                }
            }
            return result
        }

        if (config.type == DbType.SQLSERVER) {
            // У MSSQL INFORMATION_SCHEMA.CONSTRAINT_COLUMN_USAGE (в отличие от Postgres) отдаёт
            // constrained-сторону, а не referenced — поэтому FK читаем через системные каталоги.
            val result = mutableListOf<ForeignKeyRef>()
            val sql = "SELECT cp.name AS column_name, tr.name AS referenced_table, cr.name AS referenced_column, " +
                "fk.delete_referential_action_desc AS on_delete, fk.update_referential_action_desc AS on_update " +
                "FROM sys.foreign_keys fk " +
                "JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id " +
                "JOIN sys.tables tp ON fkc.parent_object_id = tp.object_id " +
                "JOIN sys.columns cp ON fkc.parent_object_id = cp.object_id AND fkc.parent_column_id = cp.column_id " +
                "JOIN sys.tables tr ON fkc.referenced_object_id = tr.object_id " +
                "JOIN sys.columns cr ON fkc.referenced_object_id = cr.object_id AND fkc.referenced_column_id = cr.column_id " +
                "WHERE tp.name = ?"
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, table)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        result.add(
                            ForeignKeyRef(
                                columnName = rs.getString("column_name"),
                                referencedTable = rs.getString("referenced_table"),
                                referencedColumn = rs.getString("referenced_column"),
                                onDelete = parseAction(rs.getString("on_delete")),
                                onUpdate = parseAction(rs.getString("on_update")),
                            )
                        )
                    }
                }
            }
            return result
        }

        val sql = when (config.type) {
            DbType.MYSQL ->
                "SELECT kcu.column_name, kcu.referenced_table_name AS referenced_table, " +
                    "kcu.referenced_column_name AS referenced_column, rc.delete_rule AS on_delete, rc.update_rule AS on_update " +
                    "FROM information_schema.key_column_usage kcu " +
                    "JOIN information_schema.referential_constraints rc " +
                    "  ON kcu.constraint_name = rc.constraint_name AND kcu.table_schema = rc.constraint_schema " +
                    "  AND kcu.table_name = rc.table_name " +
                    "WHERE kcu.table_name = ? AND kcu.referenced_table_name IS NOT NULL AND ${schemaClause("kcu")}"
            DbType.POSTGRESQL ->
                "SELECT kcu.column_name, ccu.table_name AS referenced_table, ccu.column_name AS referenced_column, " +
                    "rc.delete_rule AS on_delete, rc.update_rule AS on_update " +
                    "FROM information_schema.table_constraints tc " +
                    "JOIN information_schema.key_column_usage kcu " +
                    "  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema " +
                    "JOIN information_schema.constraint_column_usage ccu " +
                    "  ON tc.constraint_name = ccu.constraint_name AND tc.table_schema = ccu.table_schema " +
                    "JOIN information_schema.referential_constraints rc " +
                    "  ON tc.constraint_name = rc.constraint_name AND tc.table_schema = rc.constraint_schema " +
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
                            onDelete = parseAction(rs.getString("on_delete")),
                            onUpdate = parseAction(rs.getString("on_update")),
                        )
                    )
                }
            }
        }
        return result
    }

    /** Приводит текстовое обозначение referential action (разное у каждой СУБД) к [ReferentialAction]. */
    private fun parseAction(raw: String?): ReferentialAction = when (raw?.uppercase()?.replace("_", " ")) {
        "CASCADE" -> ReferentialAction.CASCADE
        "SET NULL" -> ReferentialAction.SET_NULL
        "RESTRICT" -> ReferentialAction.RESTRICT
        "SET DEFAULT" -> ReferentialAction.SET_DEFAULT
        else -> ReferentialAction.NO_ACTION
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
        val isMssql = config.type == DbType.SQLSERVER
        // MSSQL не поддерживает LIMIT — постраничность там выражается через
        // ORDER BY ... OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY (offset всегда 0, т.к. отсечение уже
        // сделано условием WHERE pk > ?, либо его нет вовсе на первом батче).
        val sql = if (lastValue != null) {
            if (isMssql) {
                "SELECT * FROM ${quote(table)} WHERE ${quote(pkColumn)} > ? " +
                    "ORDER BY ${quote(pkColumn)} OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY"
            } else {
                "SELECT * FROM ${quote(table)} WHERE ${quote(pkColumn)} > ? ORDER BY ${quote(pkColumn)} LIMIT ?"
            }
        } else {
            if (isMssql) {
                "SELECT * FROM ${quote(table)} ORDER BY ${quote(pkColumn)} OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY"
            } else {
                "SELECT * FROM ${quote(table)} ORDER BY ${quote(pkColumn)} LIMIT ?"
            }
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
        // Без PK нет естественного столбца для ORDER BY — но MSSQL требует ORDER BY для
        // OFFSET/FETCH синтаксически, поэтому используем заведомо "пустую" сортировку.
        val sql = if (config.type == DbType.SQLSERVER) {
            "SELECT * FROM ${quote(table)} ORDER BY (SELECT NULL) OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"
        } else {
            "SELECT * FROM ${quote(table)} LIMIT ? OFFSET ?"
        }

        val rows = mutableListOf<Map<String, Any?>>()
        connection.prepareStatement(sql).use { ps ->
            if (config.type == DbType.SQLSERVER) {
                ps.setLong(1, offset)
                ps.setInt(2, batchSize)
            } else {
                ps.setInt(1, batchSize)
                ps.setLong(2, offset)
            }
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
            DbType.SQLSERVER -> "${prefix}table_schema = 'dbo'"
            else -> "1=1"
        }
    }

    private fun quote(identifier: String): String = when (config.type) {
        DbType.MYSQL -> "`$identifier`"
        DbType.POSTGRESQL, DbType.SQLITE -> "\"$identifier\""
        DbType.SQLSERVER -> "[$identifier]"
    }

    override fun close() {
        if (::connection.isInitialized) connection.close()
    }
}
