package com.example.databasecopier.dump

import com.example.databasecopier.adapter.ColumnDef
import com.example.databasecopier.adapter.ForeignKeyRef
import com.example.databasecopier.adapter.ReferentialAction
import com.example.databasecopier.adapter.TableStructure
import com.example.databasecopier.adapter.TypeMapper

object CreateTableParser {

    // Java's default \w is ASCII-only. MySQL allows Unicode identifiers, including names whose
    // Cyrillic letters look identical to Latin ones; skipping such a column breaks its PRIMARY KEY.
    private val IDENTIFIER = """[\p{L}\p{N}_$]+"""
    private val NAME_REGEX = Regex("""(?is)create\s+table\s+(?:if\s+not\s+exists\s+)?[`"]?($IDENTIFIER)[`"]?\s*\(""")
    private val COLUMN_NAME_TYPE_REGEX = Regex("""^[`"]?($IDENTIFIER)[`"]?\s+([\w]+)(?:\s*\(([^)]*)\))?""")
    // Только для VARCHAR/CHAR "(N)" — это длина строки; у DECIMAL/NUMERIC "(N,M)" это
    // точность/масштаб, а не длина, поэтому не трогаем при наличии запятой.
    private val VARCHAR_LENGTH_REGEX = Regex("""^\d+$""")
    private val FK_REGEX = Regex(
        """(?is)FOREIGN\s+KEY\s*\(\s*[`"]?($IDENTIFIER)[`"]?\s*\)\s*REFERENCES\s+[`"]?($IDENTIFIER)[`"]?\s*\(\s*[`"]?($IDENTIFIER)[`"]?\s*\)""" +
            """(?:\s*ON\s+DELETE\s+(CASCADE|SET\s+NULL|RESTRICT|NO\s+ACTION|SET\s+DEFAULT))?""" +
            """(?:\s*ON\s+UPDATE\s+(CASCADE|SET\s+NULL|RESTRICT|NO\s+ACTION|SET\s+DEFAULT))?"""
    )

    private fun parseAction(raw: String?): ReferentialAction = when (raw?.uppercase()?.replace(Regex("\\s+"), " ")) {
        "CASCADE" -> ReferentialAction.CASCADE
        "SET NULL" -> ReferentialAction.SET_NULL
        "RESTRICT" -> ReferentialAction.RESTRICT
        "SET DEFAULT" -> ReferentialAction.SET_DEFAULT
        else -> ReferentialAction.NO_ACTION
    }
    private val SKIP_PREFIXES = listOf("KEY", "UNIQUE", "INDEX", "CONSTRAINT", "CHECK")
    private val DEFAULT_REGEX = Regex(
        """(?i)DEFAULT\s+('(?:[^'\\]|\\.)*'|-?\d+(?:\.\d+)?|CURRENT_TIMESTAMP\w*|NULL)"""
    )
    private val SERIAL_TYPES = setOf("serial", "bigserial", "smallserial")

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
                        foreignKeys.add(
                            ForeignKeyRef(
                                columnName = m.groupValues[1],
                                referencedTable = m.groupValues[2],
                                referencedColumn = m.groupValues[3],
                                onDelete = parseAction(m.groupValues[4].ifBlank { null }),
                                onUpdate = parseAction(m.groupValues[5].ifBlank { null }),
                            )
                        )
                    }
                }
                SKIP_PREFIXES.any { upper.startsWith(it) } -> {
                    // индексы/constraints без FK — структура копирования их не воспроизводит
                }
                else -> {
                    val colMatch = COLUMN_NAME_TYPE_REGEX.find(entry) ?: continue
                    val colName = colMatch.groupValues[1]
                    val sqlType = colMatch.groupValues[2]
                    val typeArgs = colMatch.groupValues[3].trim()
                    val length = typeArgs.takeIf { VARCHAR_LENGTH_REGEX.matches(it) }?.toIntOrNull()
                    val nullable = !upper.contains("NOT NULL")
                    if (upper.contains("PRIMARY KEY")) primaryKey.add(colName)
                    val autoIncrement = upper.contains("AUTO_INCREMENT") || upper.contains("AUTOINCREMENT") ||
                        sqlType.lowercase() in SERIAL_TYPES
                    val defaultValue = if (autoIncrement) null else DEFAULT_REGEX.find(entry)?.groupValues?.get(1)
                    columns.add(
                        ColumnDef(colName, TypeMapper.fromSqlType(dbType, sqlType), nullable, autoIncrement, defaultValue, length = length)
                    )
                }
            }
        }

        return Result(TableStructure(tableName, columns, primaryKey), foreignKeys)
    }
}
