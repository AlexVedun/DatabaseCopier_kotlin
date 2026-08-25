package com.example.databasecopier.dump

import com.example.databasecopier.adapter.ColumnDef
import com.example.databasecopier.adapter.ForeignKeyRef
import com.example.databasecopier.adapter.TableStructure
import com.example.databasecopier.adapter.TypeMapper

object CreateTableParser {

    private val NAME_REGEX = Regex("""(?is)create\s+table\s+(?:if\s+not\s+exists\s+)?[`"]?([\w]+)[`"]?\s*\(""")
    private val COLUMN_NAME_TYPE_REGEX = Regex("""^[`"]?([\w]+)[`"]?\s+([\w]+)(?:\s*\([^)]*\))?""")
    private val FK_REGEX = Regex(
        """(?is)FOREIGN\s+KEY\s*\(\s*[`"]?([\w]+)[`"]?\s*\)\s*REFERENCES\s+[`"]?([\w]+)[`"]?\s*\(\s*[`"]?([\w]+)[`"]?\s*\)"""
    )
    private val SKIP_PREFIXES = listOf("KEY", "UNIQUE", "INDEX", "CONSTRAINT", "CHECK")

    data class Result(val structure: TableStructure, val foreignKeys: List<ForeignKeyRef>)

    fun parse(statement: String, dialect: DumpDialect): Result {
        val clean = SqlText.stripLeadingComments(statement).trim()
        val nameMatch = NAME_REGEX.find(clean)
            ?: throw IllegalArgumentException("Not a CREATE TABLE statement: ${clean.take(80)}")
        val tableName = nameMatch.groupValues[1]
        val body = SqlText.extractParenBody(clean, nameMatch.range.first)
            ?: throw IllegalArgumentException("Could not find CREATE TABLE body for $tableName")

        val columns = mutableListOf<ColumnDef>()
        val primaryKey = mutableListOf<String>()
        val foreignKeys = mutableListOf<ForeignKeyRef>()
        val dbType = dialect.toDbType()

        for (rawEntry in SqlText.splitTopLevel(body, ',')) {
            val entry = rawEntry.trim()
            if (entry.isEmpty()) continue
            val upper = entry.uppercase()

            when {
                upper.startsWith("PRIMARY KEY") -> {
                    val pkBody = SqlText.extractParenBody(entry, 0) ?: continue
                    primaryKey.addAll(pkBody.split(',').map { it.trim().trim('`', '"') })
                }
                upper.contains("FOREIGN KEY") -> {
                    FK_REGEX.find(entry)?.let { m ->
                        foreignKeys.add(ForeignKeyRef(m.groupValues[1], m.groupValues[2], m.groupValues[3]))
                    }
                }
                SKIP_PREFIXES.any { upper.startsWith(it) } -> {
                    // индексы/constraints без FK — структура копирования их не воспроизводит
                }
                else -> {
                    val colMatch = COLUMN_NAME_TYPE_REGEX.find(entry) ?: continue
                    val colName = colMatch.groupValues[1]
                    val sqlType = colMatch.groupValues[2]
                    val nullable = !upper.contains("NOT NULL")
                    if (upper.contains("PRIMARY KEY")) primaryKey.add(colName)
                    columns.add(ColumnDef(colName, TypeMapper.fromSqlType(dbType, sqlType), nullable))
                }
            }
        }

        return Result(TableStructure(tableName, columns, primaryKey), foreignKeys)
    }
}
