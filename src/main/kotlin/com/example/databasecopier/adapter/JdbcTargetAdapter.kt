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
            // Если таблица с таким именем уже существует на target — она безусловно удаляется
            // и создаётся заново по структуре источника (решение зафиксировано с пользователем:
            // не пытаться угадывать совместимость существующей схемы, а гарантировать, что
            // структура target всегда точно соответствует source).
            stmt.execute("DROP TABLE IF EXISTS ${quote(structure.name)}")
            stmt.execute("CREATE TABLE ${quote(structure.name)} ($columnsSql$pkSql)")
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
        val sql = if (config.type == DbType.SQLITE) {
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?"
        } else {
            "SELECT table_name FROM information_schema.tables WHERE table_name = ? AND ${schemaClause()}"
        }
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    override fun disableForeignKeyChecks() {
        when (config.type) {
            DbType.MYSQL -> connection.createStatement().use { it.execute("SET FOREIGN_KEY_CHECKS=0") }
            DbType.POSTGRESQL -> connection.createStatement().use { it.execute("SET session_replication_role = 'replica'") }
            DbType.SQLITE -> connection.createStatement().use { it.execute("PRAGMA foreign_keys = OFF") }
            // У MSSQL нет сессионного тумблера — переключается по каждой таблице отдельно.
            DbType.SQLSERVER -> forEachTable { fullName ->
                connection.createStatement().use { it.execute("ALTER TABLE $fullName NOCHECK CONSTRAINT ALL") }
            }
        }
        connection.commit()
    }

    override fun enableForeignKeyChecks() {
        when (config.type) {
            DbType.MYSQL -> connection.createStatement().use { it.execute("SET FOREIGN_KEY_CHECKS=1") }
            DbType.POSTGRESQL -> connection.createStatement().use { it.execute("SET session_replication_role = 'origin'") }
            DbType.SQLITE -> connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            // CHECK CONSTRAINT ALL (без WITH CHECK) включает проверку для новых DML, не
            // перепроверяя уже вставленные строки — то же поведение, что и у остальных СУБД здесь.
            DbType.SQLSERVER -> forEachTable { fullName ->
                connection.createStatement().use { it.execute("ALTER TABLE $fullName CHECK CONSTRAINT ALL") }
            }
        }
        connection.commit()
    }

    private fun forEachTable(action: (String) -> Unit) {
        val names = mutableListOf<String>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT s.name AS schema_name, t.name AS table_name " +
                    "FROM sys.tables t JOIN sys.schemas s ON t.schema_id = s.schema_id"
            ).use { rs ->
                while (rs.next()) names.add("[${rs.getString("schema_name")}].[${rs.getString("table_name")}]")
            }
        }
        names.forEach(action)
    }

    private fun schemaClause(): String = when (config.type) {
        DbType.MYSQL -> "table_schema = '${config.database}'"
        DbType.POSTGRESQL -> "table_schema = 'public'"
        DbType.SQLSERVER -> "table_schema = 'dbo'"
        else -> "1=1"
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
