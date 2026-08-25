package com.example.databasecopier.dump

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Unit-тесты низкоуровневых парсеров на маленьких фикстурах: экранирование, многострочные значения, оба формата данных. */
class DumpParserFixtureTest {

    @Test
    fun `parses MySQL CREATE TABLE with primary key and foreign key`() {
        val sql = """
            CREATE TABLE `orders` (
              `id` int(11) NOT NULL,
              `customer_id` int(11) NOT NULL,
              `amount` decimal(10,2) NOT NULL,
              PRIMARY KEY (`id`),
              FOREIGN KEY (`customer_id`) REFERENCES `customers` (`id`)
            );
        """.trimIndent()

        val result = CreateTableParser.parse(sql, DumpDialect.MYSQL)

        assertEquals("orders", result.structure.name)
        assertEquals(listOf("id"), result.structure.primaryKey)
        assertEquals(3, result.structure.columns.size)
        assertEquals(
            com.example.databasecopier.adapter.LogicalType.DECIMAL,
            result.structure.columns.first { it.name == "amount" }.type,
        )
        assertEquals(1, result.foreignKeys.size)
        assertEquals(ForeignKeyRefFixture("customer_id", "customers", "id"), result.foreignKeys.first().toFixture())
    }

    @Test
    fun `parses PostgreSQL CREATE TABLE with inline primary key`() {
        val sql = """
            CREATE TABLE customers (
                id integer NOT NULL PRIMARY KEY,
                name character varying(255) NOT NULL,
                note text
            );
        """.trimIndent()

        val result = CreateTableParser.parse(sql, DumpDialect.POSTGRESQL)

        assertEquals("customers", result.structure.name)
        assertEquals(listOf("id"), result.structure.primaryKey)
        assertEquals(3, result.structure.columns.size)
    }

    @Test
    fun `parses multi-row INSERT with escaped quote, embedded comma and NULL`() {
        val sql = "INSERT INTO `customers` (`id`, `name`, `note`) VALUES " +
            "(1,'Alice',NULL),(2,'O\\'Brien','has a comma, inside');"

        val result = InsertParser.parse(sql, useBackslashEscape = true)

        assertEquals("customers", result.tableName)
        assertEquals(listOf("id", "name", "note"), result.columns)
        assertEquals(2, result.rows.size)
        assertEquals(listOf(1L, "Alice", null), result.rows[0])
        assertEquals(listOf(2L, "O'Brien", "has a comma, inside"), result.rows[1])
    }

    @Test
    fun `parses an INSERT value containing a raw embedded newline`() {
        // Значение содержит настоящий перевод строки внутри кавычек — наивный построчный
        // парсер сломал бы это значение на две части.
        val sql = "INSERT INTO `notes` (`id`, `text`) VALUES (1,'first line\nsecond line');"

        val result = InsertParser.parse(sql, useBackslashEscape = true)

        assertEquals(listOf(1L, "first line\nsecond line"), result.rows.single())
    }

    @Test
    fun `parses a COPY block header and unescapes tab separated data lines`() {
        val header = CopyBlockParser.parseHeader("COPY customers (id, name, note) FROM stdin;")
        assertEquals("customers", header?.tableName)
        assertEquals(listOf("id", "name", "note"), header?.columns)

        val row1 = CopyBlockParser.parseDataLine("1\tAlice\tline1\\nline2")
        assertEquals(listOf("1", "Alice", "line1\nline2"), row1)

        val row2 = CopyBlockParser.parseDataLine("2\tO'Brien\t\\N")
        assertEquals("2", row2[0])
        assertEquals("O'Brien", row2[1])
        assertNull(row2[2])
    }

    private data class ForeignKeyRefFixture(val column: String, val refTable: String, val refColumn: String)
    private fun com.example.databasecopier.adapter.ForeignKeyRef.toFixture() =
        ForeignKeyRefFixture(columnName, referencedTable, referencedColumn)
}
