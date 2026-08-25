package com.example.databasecopier.adapter

import java.sql.Connection
import java.sql.DriverManager

class JdbcTargetAdapter(private val config: ConnectionConfig) : TargetAdapter {

    private lateinit var connection: Connection

    override fun connect() {
        connection = DriverManager.getConnection(buildJdbcUrl(config), config.username, config.password)
        connection.autoCommit = false
    }

    override fun createTable(structure: TableStructure) {
        val columnsSql = structure.columns.joinToString(", ") { col ->
            val sqlType = TypeMapper.toSqlType(config.type, col.type)
            val nullability = if (col.nullable) "" else " NOT NULL"
            "${quote(col.name)} $sqlType$nullability"
        }
        val pkSql = if (structure.primaryKey.isNotEmpty()) {
            ", PRIMARY KEY (${structure.primaryKey.joinToString(", ") { quote(it) }})"
        } else ""

        connection.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE IF NOT EXISTS ${quote(structure.name)} ($columnsSql$pkSql)")
        }
        connection.commit()
    }

    override fun insertBatch(table: String, rows: List<Map<String, Any?>>) {
        if (rows.isEmpty()) return
        val columns = rows.first().keys.toList()
        val placeholders = columns.joinToString(", ") { "?" }
        val columnsSql = columns.joinToString(", ") { quote(it) }
        val sql = "INSERT INTO ${quote(table)} ($columnsSql) VALUES ($placeholders)"

        connection.prepareStatement(sql).use { ps ->
            for (row in rows) {
                columns.forEachIndexed { idx, col -> ps.setObject(idx + 1, row[col]) }
                ps.addBatch()
            }
            ps.executeBatch()
        }
        connection.commit()
    }

    override fun tableExists(table: String): Boolean {
        val sql = "SELECT table_name FROM information_schema.tables WHERE table_name = ? AND ${schemaClause()}"
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    override fun disableForeignKeyChecks() {
        connection.createStatement().use { stmt ->
            when (config.type) {
                DbType.MYSQL -> stmt.execute("SET FOREIGN_KEY_CHECKS=0")
                DbType.POSTGRESQL -> stmt.execute("SET session_replication_role = 'replica'")
                DbType.SQLITE -> stmt.execute("PRAGMA foreign_keys = OFF")
                DbType.SQLSERVER -> { /* реализуется в Шаге 9 (ALTER TABLE ... NOCHECK CONSTRAINT ALL для каждой таблицы) */ }
            }
        }
        connection.commit()
    }

    override fun enableForeignKeyChecks() {
        connection.createStatement().use { stmt ->
            when (config.type) {
                DbType.MYSQL -> stmt.execute("SET FOREIGN_KEY_CHECKS=1")
                DbType.POSTGRESQL -> stmt.execute("SET session_replication_role = 'origin'")
                DbType.SQLITE -> stmt.execute("PRAGMA foreign_keys = ON")
                DbType.SQLSERVER -> { /* реализуется в Шаге 9 */ }
            }
        }
        connection.commit()
    }

    private fun schemaClause(): String = when (config.type) {
        DbType.MYSQL -> "table_schema = '${config.database}'"
        DbType.POSTGRESQL -> "table_schema = 'public'"
        else -> "1=1"
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
