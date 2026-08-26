package com.example.databasecopier.adapter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Общие интеграционные тесты для JdbcSourceAdapter/JdbcTargetAdapter, запускаются против
 * реальной MySQL и PostgreSQL (через Testcontainers) в конкретных наследниках.
 */
abstract class JdbcAdapterTestBase {

    abstract fun config(): ConnectionConfig
    abstract fun rawJdbcUrl(): String

    private lateinit var rawConnection: Connection
    private lateinit var source: JdbcSourceAdapter
    private lateinit var target: JdbcTargetAdapter

    @BeforeEach
    fun setUp() {
        val cfg = config()
        rawConnection = DriverManager.getConnection(rawJdbcUrl(), cfg.username, cfg.password)
        rawConnection.createStatement().use { stmt ->
            stmt.execute("DROP TABLE IF EXISTS orders")
            stmt.execute("DROP TABLE IF EXISTS customers")
            stmt.execute(
                "CREATE TABLE customers (id INTEGER PRIMARY KEY, name VARCHAR(255) NOT NULL)"
            )
            stmt.execute(
                "CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL, " +
                    "amount INTEGER NOT NULL, FOREIGN KEY (customer_id) REFERENCES customers(id))"
            )
            for (i in 1..5) {
                stmt.execute("INSERT INTO customers (id, name) VALUES ($i, 'Customer $i')")
            }
            for (i in 1..25) {
                stmt.execute("INSERT INTO orders (id, customer_id, amount) VALUES ($i, ${(i % 5) + 1}, ${i * 10})")
            }
        }

        source = JdbcSourceAdapter(cfg)
        source.connect()
        target = JdbcTargetAdapter(cfg)
        target.connect()
    }

    @AfterEach
    fun tearDown() {
        source.close()
        target.close()
        rawConnection.createStatement().use { stmt ->
            stmt.execute("DROP TABLE IF EXISTS orders")
            stmt.execute("DROP TABLE IF EXISTS customers")
        }
        rawConnection.close()
    }

    @Test
    fun `lists tables with row counts`() {
        val tables = source.listTables()
        assertEquals(25L, tables["orders"])
        assertEquals(5L, tables["customers"])
    }

    @Test
    fun `reads table structure with primary key`() {
        val structure = source.getTableStructure("orders")
        assertEquals(listOf("id"), structure.primaryKey)
        assertTrue(structure.columns.any { it.name == "customer_id" && it.type == LogicalType.INTEGER })
    }

    @Test
    fun `reads foreign keys`() {
        val fks = source.getForeignKeys("orders")
        assertEquals(1, fks.size)
        assertEquals("customer_id", fks[0].columnName)
        assertEquals("customers", fks[0].referencedTable)
        assertEquals("id", fks[0].referencedColumn)
        // Фикстура не задаёт ON DELETE/ON UPDATE явно — все СУБД по умолчанию считают это NO ACTION.
        assertEquals(ReferentialAction.NO_ACTION, fks[0].onDelete)
        assertEquals(ReferentialAction.NO_ACTION, fks[0].onUpdate)
    }

    @Test
    fun `paginates through all rows without duplicates using primary key cursor`() {
        val allRows = mutableListOf<Map<String, Any?>>()
        var cursor: kotlinx.serialization.json.JsonElement? = null
        do {
            val batch = source.readBatch("orders", cursor, batchSize = 7)
            allRows.addAll(batch.rows)
            cursor = batch.nextCursor
        } while (cursor != null)

        assertEquals(25, allRows.size)
        assertEquals((1..25).toSet(), allRows.map { (it["id"] as Number).toInt() }.toSet())
    }

    @Test
    fun `resumes from a saved cursor without re-reading earlier rows`() {
        val firstBatch = source.readBatch("orders", null, batchSize = 10)
        assertEquals(10, firstBatch.rows.size)

        val resumed = mutableListOf<Map<String, Any?>>()
        var cursor = firstBatch.nextCursor
        do {
            val batch = source.readBatch("orders", cursor, batchSize = 10)
            resumed.addAll(batch.rows)
            cursor = batch.nextCursor
        } while (cursor != null)

        assertEquals(15, resumed.size)
        val resumedIds = resumed.map { (it["id"] as Number).toInt() }.toSet()
        val firstIds = firstBatch.rows.map { (it["id"] as Number).toInt() }.toSet()
        assertTrue(resumedIds.intersect(firstIds).isEmpty())
    }

    @Test
    fun `creates table and inserts batch on target`() {
        val structure = TableStructure(
            name = "copy_target",
            columns = listOf(
                ColumnDef("id", LogicalType.INTEGER, nullable = false),
                ColumnDef("label", LogicalType.VARCHAR, nullable = true),
            ),
            primaryKey = listOf("id"),
        )
        assertFalse(target.tableExists("copy_target"))
        target.createTable(structure)
        assertTrue(target.tableExists("copy_target"))

        target.insertBatch(
            "copy_target",
            listOf(mapOf("id" to 1, "label" to "a"), mapOf("id" to 2, "label" to "b")),
        )

        rawConnection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM copy_target").use { rs ->
                rs.next()
                assertEquals(2, rs.getInt(1))
            }
            stmt.execute("DROP TABLE copy_target")
        }
    }

    @Test
    fun `recreates an existing target table dropping its old structure and data`() {
        rawConnection.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE copy_target (old_col VARCHAR(50))")
            stmt.execute("INSERT INTO copy_target (old_col) VALUES ('stale')")
        }

        val newStructure = TableStructure(
            name = "copy_target",
            columns = listOf(ColumnDef("id", LogicalType.INTEGER, nullable = false)),
            primaryKey = listOf("id"),
        )
        target.createTable(newStructure)
        target.insertBatch("copy_target", listOf(mapOf("id" to 1)))

        rawConnection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM copy_target").use { rs ->
                rs.next()
                assertEquals(1, rs.getInt(1))
            }
            stmt.execute("DROP TABLE copy_target")
        }
    }

    @Test
    fun `creates foreign key constraint with referential action after both tables exist`() {
        // SQLite не поддерживает ALTER TABLE ADD CONSTRAINT FOREIGN KEY — createForeignKeys()
        // там осознанно no-op (см. JdbcTargetAdapter), проверять здесь нечего.
        assumeTrue(config().type != DbType.SQLITE)

        target.createTable(
            TableStructure("t_customers", listOf(ColumnDef("id", LogicalType.INTEGER, nullable = false)), listOf("id"))
        )
        target.createTable(
            TableStructure(
                "t_orders",
                listOf(
                    ColumnDef("id", LogicalType.INTEGER, nullable = false),
                    ColumnDef("customer_id", LogicalType.INTEGER, nullable = true),
                ),
                listOf("id"),
            )
        )
        target.insertBatch("t_customers", listOf(mapOf("id" to 1)))
        target.insertBatch("t_orders", listOf(mapOf("id" to 100, "customer_id" to 1)))

        target.createForeignKeys(
            "t_orders",
            listOf(ForeignKeyRef("customer_id", "t_customers", "id", onDelete = ReferentialAction.CASCADE))
        )

        // Реальное поведение ON DELETE CASCADE доказывает, что constraint создался, а не просто
        // не упал молча: удаление родителя должно каскадно удалить зависимую строку.
        rawConnection.createStatement().use { stmt -> stmt.execute("DELETE FROM t_customers WHERE id = 1") }
        rawConnection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM t_orders").use { rs ->
                rs.next()
                assertEquals(0, rs.getInt(1))
            }
        }

        rawConnection.createStatement().use { stmt ->
            stmt.execute("DROP TABLE t_orders")
            stmt.execute("DROP TABLE t_customers")
        }
    }

    @Test
    fun `reads auto-increment and default value, then recreates natively on target with synced counter`() {
        val ddl = when (config().type) {
            DbType.MYSQL -> "CREATE TABLE auto_src (id INTEGER PRIMARY KEY AUTO_INCREMENT, note VARCHAR(50) DEFAULT 'hi')"
            DbType.POSTGRESQL -> "CREATE TABLE auto_src (id SERIAL PRIMARY KEY, note VARCHAR(50) DEFAULT 'hi')"
            DbType.SQLITE -> "CREATE TABLE auto_src (id INTEGER PRIMARY KEY AUTOINCREMENT, note TEXT DEFAULT 'hi')"
            DbType.SQLSERVER -> "CREATE TABLE auto_src (id INT IDENTITY(1,1) PRIMARY KEY, note NVARCHAR(50) DEFAULT 'hi')"
        }
        rawConnection.createStatement().use { stmt ->
            stmt.execute(ddl)
            stmt.execute("INSERT INTO auto_src (note) VALUES ('a')")
            stmt.execute("INSERT INTO auto_src (note) VALUES ('b')")
            stmt.execute("INSERT INTO auto_src (note) VALUES ('c')")
        }

        val structure = source.getTableStructure("auto_src")
        val idCol = structure.columns.first { it.name == "id" }
        val noteCol = structure.columns.first { it.name == "note" }
        assertTrue(idCol.autoIncrement)
        assertEquals(null, idCol.defaultValue)
        assertFalse(noteCol.autoIncrement)
        assertTrue(noteCol.defaultValue?.contains("hi") == true)

        // Пересоздаём структуру на target (та же СУБД, чтобы не смешивать с кросс-диалектным
        // маппингом типов — это отдельно проверяется тестами TypeMapper) и вручную "копируем"
        // существующие строки, как это делает CopyRunner.
        target.createTable(structure.copy(name = "auto_target"))
        target.insertBatch(
            "auto_target",
            listOf(mapOf("id" to 1, "note" to "x"), mapOf("id" to 2, "note" to "y"), mapOf("id" to 3, "note" to "z")),
        )
        target.syncAutoIncrement("auto_target", "id", 3)

        // Новая строка без явного id должна продолжить нумерацию с 4, а не конфликтовать с уже
        // скопированными — это и доказывает, что счётчик автоинкремента реально синхронизирован.
        rawConnection.createStatement().use { stmt -> stmt.execute("INSERT INTO auto_target (note) VALUES ('new')") }
        rawConnection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT id FROM auto_target WHERE note = 'new'").use { rs ->
                rs.next()
                assertEquals(4, rs.getInt("id"))
            }
        }

        rawConnection.createStatement().use { stmt ->
            stmt.execute("DROP TABLE auto_target")
            stmt.execute("DROP TABLE auto_src")
        }
    }

    @Test
    fun `allows inserting a row with a dangling foreign key while checks are disabled`() {
        // FOREIGN_KEY_CHECKS/session_replication_role — настройки уровня сессии, поэтому и
        // отключение, и сама вставка обязаны идти через одно и то же JDBC-соединение (target).
        target.disableForeignKeyChecks()
        try {
            // customer_id = 999 не существует в customers — с включёнными проверками это упало бы
            target.insertBatch("orders", listOf(mapOf("id" to 999, "customer_id" to 999, "amount" to 100)))
        } finally {
            target.enableForeignKeyChecks()
            rawConnection.createStatement().use { stmt -> stmt.execute("DELETE FROM orders WHERE id = 999") }
        }
    }
}
