package com.example.databasecopier.dump

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.io.path.createTempFile
import kotlin.io.path.writeText

class DumpSourceAdapterTest {

    private fun writeFixture(content: String): File {
        val path = createTempFile("dump-fixture-", ".sql")
        path.writeText(content)
        return path.toFile()
    }

    @Test
    fun `keeps Unicode column names in MySQL dump structure keys and rows`() {
        // The c in this identifier is Cyrillic, as in the reported mysqldump.
        val id = "gt\u0441_goal_template_category_id"
        val parentId = "gt\u0441_parent_id"
        val table = "app_gtc_goal_template_category"
        val sql = """
            CREATE TABLE `$table` (
              `$id` int(11) NOT NULL AUTO_INCREMENT,
              `$parentId` int(11) DEFAULT NULL,
              `gtc_name` varchar(255) NOT NULL,
              PRIMARY KEY (`$id`),
              CONSTRAINT `fk_category_parent` FOREIGN KEY (`$parentId`) REFERENCES `$table` (`$id`)
            );
            INSERT INTO `$table` VALUES (1,NULL,'Шаблон1');
        """.trimIndent()

        val adapter = MysqlDumpSourceAdapter(writeFixture(sql))
        adapter.connect()
        try {
            val structure = adapter.getTableStructure(table)
            assertEquals(listOf(id, parentId, "gtc_name"), structure.columns.map { it.name })
            assertEquals(listOf(id), structure.primaryKey)
            assertEquals(parentId, adapter.getForeignKeys(table).single().columnName)
            assertEquals(id, adapter.getForeignKeys(table).single().referencedColumn)
            assertEquals(mapOf(id to 1L, parentId to null, "gtc_name" to "Шаблон1"), adapter.readBatch(table, null, 10).rows.single())
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `reads structure, foreign keys and paginates INSERT-format MySQL dump without duplicates`() {
        val sql = """
            -- MySQL dump 10.13  Distrib 8.0.36
            SET NAMES utf8mb4;
            DROP TABLE IF EXISTS `customers`;
            CREATE TABLE `customers` (
              `id` int(11) NOT NULL,
              `name` varchar(255) NOT NULL,
              PRIMARY KEY (`id`)
            );
            INSERT INTO `customers` (`id`, `name`) VALUES (1,'Alice'),(2,'O\'Brien');
            INSERT INTO `customers` (`id`, `name`) VALUES (3,'Carol');
            DROP TABLE IF EXISTS `orders`;
            CREATE TABLE `orders` (
              `id` int(11) NOT NULL,
              `customer_id` int(11) NOT NULL,
              `amount` int(11) NOT NULL,
              PRIMARY KEY (`id`),
              FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`)
            );
            INSERT INTO `orders` (`id`, `customer_id`, `amount`) VALUES (1,1,100),(2,2,200);
            INSERT INTO `orders` (`id`, `customer_id`, `amount`) VALUES (3,3,300),(4,1,400),(5,2,500);
        """.trimIndent()

        val adapter = MysqlDumpSourceAdapter(writeFixture(sql))
        adapter.connect()
        try {
            val tables = adapter.listTables()
            assertEquals(3L, tables["customers"])
            assertEquals(5L, tables["orders"])

            val structure = adapter.getTableStructure("orders")
            assertEquals(listOf("id"), structure.primaryKey)

            val fks = adapter.getForeignKeys("orders")
            assertEquals(1, fks.size)
            assertEquals("customers", fks.first().referencedTable)

            assertEquals(5L, adapter.countRows("orders"))

            val allRows = mutableListOf<Map<String, Any?>>()
            var cursor: kotlinx.serialization.json.JsonElement? = null
            do {
                val batch = adapter.readBatch("orders", cursor, batchSize = 2)
                allRows.addAll(batch.rows)
                cursor = batch.nextCursor
            } while (cursor != null)

            assertEquals(5, allRows.size)
            assertEquals((1..5).toSet(), allRows.map { (it["id"] as Long).toInt() }.toSet())

            val customerRows = mutableListOf<Map<String, Any?>>()
            var custCursor: kotlinx.serialization.json.JsonElement? = null
            do {
                val batch = adapter.readBatch("customers", custCursor, batchSize = 1)
                customerRows.addAll(batch.rows)
                custCursor = batch.nextCursor
            } while (custCursor != null)
            assertEquals("O'Brien", customerRows.first { it["id"] == 2L }["name"])
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `resumes an INSERT-format dump from a saved cursor without re-reading earlier rows`() {
        val sql = """
            CREATE TABLE `items` (
              `id` int(11) NOT NULL,
              `value` varchar(50) NOT NULL,
              PRIMARY KEY (`id`)
            );
            INSERT INTO `items` (`id`, `value`) VALUES (1,'a'),(2,'b'),(3,'c');
            INSERT INTO `items` (`id`, `value`) VALUES (4,'d'),(5,'e');
        """.trimIndent()

        val adapter = MysqlDumpSourceAdapter(writeFixture(sql))
        adapter.connect()
        try {
            val first = adapter.readBatch("items", null, batchSize = 3)
            assertEquals(3, first.rows.size)
            assertTrue(first.nextCursor != null)

            val second = adapter.readBatch("items", first.nextCursor, batchSize = 3)
            assertEquals(2, second.rows.size)
            assertEquals(null, second.nextCursor)

            val allIds = (first.rows + second.rows).map { (it["id"] as Long).toInt() }
            assertEquals(listOf(1, 2, 3, 4, 5), allIds)
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `reads structure and paginates a COPY-format PostgreSQL dump handling NULL and escapes`() {
        val sql = "CREATE TABLE customers (\n" +
            "    id integer NOT NULL,\n" +
            "    name character varying(255) NOT NULL,\n" +
            "    note text,\n" +
            "    PRIMARY KEY (id)\n" +
            ");\n" +
            "COPY customers (id, name, note) FROM stdin;\n" +
            "1\tAlice\tline1\\nline2\n" +
            "2\tO'Brien\t\\N\n" +
            "3\tCarol\thas a comma, inside\n" +
            "\\.\n" +
            "CREATE TABLE orders (\n" +
            "    id integer NOT NULL,\n" +
            "    customer_id integer NOT NULL,\n" +
            "    amount integer NOT NULL,\n" +
            "    PRIMARY KEY (id),\n" +
            "    FOREIGN KEY (customer_id) REFERENCES customers(id)\n" +
            ");\n" +
            "COPY orders (id, customer_id, amount) FROM stdin;\n" +
            "1\t1\t100\n" +
            "2\t2\t200\n" +
            "3\t3\t300\n" +
            "4\t1\t400\n" +
            "5\t2\t500\n" +
            "\\.\n"

        val adapter = PostgresDumpSourceAdapter(writeFixture(sql))
        adapter.connect()
        try {
            assertEquals(3L, adapter.countRows("customers"))
            assertEquals(5L, adapter.countRows("orders"))

            val fks = adapter.getForeignKeys("orders")
            assertEquals(1, fks.size)
            assertEquals("customers", fks.first().referencedTable)

            val customerRows = mutableListOf<Map<String, Any?>>()
            var cursor: kotlinx.serialization.json.JsonElement? = null
            do {
                val batch = adapter.readBatch("customers", cursor, batchSize = 2)
                customerRows.addAll(batch.rows)
                cursor = batch.nextCursor
            } while (cursor != null)

            assertEquals(3, customerRows.size)
            val byId = customerRows.associateBy { it["id"] }
            assertEquals("line1\nline2", byId["1"]?.get("note"))
            assertEquals(null, byId["2"]?.get("note"))
            assertEquals("has a comma, inside", byId["3"]?.get("note"))
        } finally {
            adapter.close()
        }
    }

    @Test
    fun `resumes a COPY-format dump from a saved cursor without re-reading earlier rows`() {
        val sql = "CREATE TABLE items (\n" +
            "    id integer NOT NULL,\n" +
            "    value text,\n" +
            "    PRIMARY KEY (id)\n" +
            ");\n" +
            "COPY items (id, value) FROM stdin;\n" +
            "1\ta\n" +
            "2\tb\n" +
            "3\tc\n" +
            "4\td\n" +
            "5\te\n" +
            "\\.\n"

        val adapter = PostgresDumpSourceAdapter(writeFixture(sql))
        adapter.connect()
        try {
            val first = adapter.readBatch("items", null, batchSize = 3)
            assertEquals(3, first.rows.size)
            assertTrue(first.nextCursor != null)

            val second = adapter.readBatch("items", first.nextCursor, batchSize = 3)
            assertEquals(2, second.rows.size)
            assertEquals(null, second.nextCursor)

            val allIds = (first.rows + second.rows).map { it["id"] }
            assertEquals(listOf("1", "2", "3", "4", "5"), allIds)
        } finally {
            adapter.close()
        }
    }
}
