package com.example.databasecopier.adapter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
