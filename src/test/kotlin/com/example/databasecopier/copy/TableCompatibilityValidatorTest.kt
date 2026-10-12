package com.example.databasecopier.copy

import com.example.databasecopier.adapter.ColumnDef
import com.example.databasecopier.adapter.IndexDef
import com.example.databasecopier.adapter.LogicalType
import com.example.databasecopier.adapter.TableStructure
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TableCompatibilityValidatorTest {

    private val source = TableStructure(
        name = "items",
        columns = listOf(
            ColumnDef("id", LogicalType.INTEGER, nullable = false),
            ColumnDef("value", LogicalType.VARCHAR, nullable = true, length = 100),
        ),
        primaryKey = listOf("id"),
    )

    @Test
    fun `accepts safe widening and optional target columns`() {
        val target = TableStructure(
            name = "items",
            columns = listOf(
                ColumnDef("id", LogicalType.BIGINT, nullable = false),
                ColumnDef("value", LogicalType.TEXT, nullable = true),
                ColumnDef("note", LogicalType.TEXT, nullable = true),
            ),
            primaryKey = listOf("id"),
        )

        assertTrue(TableCompatibilityValidator.validate(source, target).isEmpty())
    }

    @Test
    fun `rejects schema that can reject source rows`() {
        val target = TableStructure(
            name = "items",
            columns = listOf(
                ColumnDef("id", LogicalType.INTEGER, nullable = false),
                ColumnDef("value", LogicalType.VARCHAR, nullable = false, length = 20),
                ColumnDef("required", LogicalType.TEXT, nullable = false),
            ),
            primaryKey = listOf("value"),
            indexes = listOf(IndexDef("uq_value", listOf("value"), unique = true)),
        )

        val errors = TableCompatibilityValidator.validate(source, target).joinToString("\n")
        assertTrue("nullable in source" in errors)
        assertTrue("incompatible types" in errors)
        assertTrue("has no source value" in errors)
        assertTrue("primary key differs" in errors)
        assertTrue("additional unique index" in errors)
    }

    @Test
    fun `rejects decimal target with insufficient integer or fractional digits`() {
        val decimalSource = TableStructure(
            "amounts",
            listOf(ColumnDef("amount", LogicalType.DECIMAL, false, numericPrecision = 12, numericScale = 4)),
            emptyList(),
        )
        val decimalTarget = TableStructure(
            "amounts",
            listOf(ColumnDef("amount", LogicalType.DECIMAL, false, numericPrecision = 10, numericScale = 2)),
            emptyList(),
        )

        assertTrue(TableCompatibilityValidator.validate(decimalSource, decimalTarget).single().contains("incompatible types"))
    }
}
