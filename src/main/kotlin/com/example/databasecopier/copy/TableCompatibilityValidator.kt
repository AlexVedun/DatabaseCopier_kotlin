package com.example.databasecopier.copy

import com.example.databasecopier.adapter.ColumnDef
import com.example.databasecopier.adapter.LogicalType
import com.example.databasecopier.adapter.TableStructure

/**
 * Консервативная проверка режима data-only. Если безопасность преобразования нельзя доказать по
 * доступным метаданным, копирование блокируется до первого DELETE.
 */
object TableCompatibilityValidator {

    fun validate(source: TableStructure, target: TableStructure): List<String> {
        val errors = mutableListOf<String>()
        val sourceColumns = source.columns.associateBy { it.name }
        val targetColumns = target.columns.associateBy { it.name }

        for (sourceColumn in source.columns) {
            val targetColumn = targetColumns[sourceColumn.name]
            if (targetColumn == null) {
                errors += "column '${sourceColumn.name}' is missing in target"
                continue
            }
            if (targetColumn.generated) {
                errors += "target column '${targetColumn.name}' is generated and cannot accept source values"
                continue
            }
            errors += validateColumn(sourceColumn, targetColumn)
        }

        for (targetColumn in target.columns.filter { it.name !in sourceColumns }) {
            if (!targetColumn.nullable && targetColumn.defaultValue == null && !targetColumn.autoIncrement && !targetColumn.generated) {
                errors += "target column '${targetColumn.name}' has no source value, default or auto-increment"
            }
        }

        if (source.primaryKey != target.primaryKey) {
            errors += "primary key differs: source=${source.primaryKey}, target=${target.primaryKey}"
        }

        val sourceUniqueKeys = source.indexes.filter { it.unique }.map { it.columns }.toSet() + setOf(source.primaryKey)
        for (targetIndex in target.indexes.filter { it.unique }) {
            if (targetIndex.columns !in sourceUniqueKeys) {
                errors += "target has an additional unique index on ${targetIndex.columns}"
            }
        }

        val sourceChecks = source.checkConstraints.map { normalizeExpression(it.expression) }.toSet()
        for (targetCheck in target.checkConstraints) {
            if (normalizeExpression(targetCheck.expression) !in sourceChecks) {
                errors += "target has an additional CHECK constraint '${targetCheck.name}'"
            }
        }

        return errors
    }

    private fun validateColumn(source: ColumnDef, target: ColumnDef): List<String> {
        val errors = mutableListOf<String>()
        if (source.nullable && !target.nullable) {
            errors += "column '${source.name}' is nullable in source but NOT NULL in target"
        }
        if (!typesCompatible(source, target)) {
            errors += "column '${source.name}' has incompatible types: source=${describe(source)}, target=${describe(target)}"
        }
        return errors
    }

    private fun typesCompatible(source: ColumnDef, target: ColumnDef): Boolean {
        if (source.type == target.type) {
            return when (source.type) {
                LogicalType.VARCHAR -> {
                    val sourceLength = source.length ?: return false
                    val targetLength = target.length ?: return false
                    sourceLength <= targetLength
                }
                LogicalType.DECIMAL -> decimalCompatible(source, target)
                else -> true
            }
        }
        return when (source.type to target.type) {
            LogicalType.INTEGER to LogicalType.BIGINT,
            LogicalType.VARCHAR to LogicalType.TEXT -> true
            else -> false
        }
    }

    private fun decimalCompatible(source: ColumnDef, target: ColumnDef): Boolean {
        val sourcePrecision = source.numericPrecision ?: return false
        val sourceScale = source.numericScale ?: return false
        val targetPrecision = target.numericPrecision ?: return false
        val targetScale = target.numericScale ?: return false
        return sourceScale <= targetScale &&
            sourcePrecision - sourceScale <= targetPrecision - targetScale
    }

    private fun describe(column: ColumnDef): String = when (column.type) {
        LogicalType.VARCHAR -> "VARCHAR(${column.length ?: "?"})"
        LogicalType.DECIMAL -> "DECIMAL(${column.numericPrecision ?: "?"},${column.numericScale ?: "?"})"
        else -> column.type.name
    }

    private fun normalizeExpression(expression: String): String = expression
        .trim()
        .removeSurrounding("(", ")")
        .replace(Regex("\\s+"), " ")
        .lowercase()
}
