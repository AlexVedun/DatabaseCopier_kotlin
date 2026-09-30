package com.example.databasecopier.dump

object InsertParser {

    private val HEADER_REGEX = Regex(
        """(?is)^insert\s+into\s+[`"]?([\p{L}\p{N}_$]+)[`"]?\s*(?:\(([^)]*)\))?\s*values\s*"""
    )

    data class Result(val tableName: String, val columns: List<String>?, val rows: List<List<Any?>>)

    /** [statement] — полный текст `INSERT INTO ... VALUES (...),(...);` (с завершающей `;`). */
    fun parse(statement: String, useBackslashEscape: Boolean): Result {
        val clean = SqlText.stripLeadingComments(statement).trim().removeSuffix(";")
        val headerMatch = HEADER_REGEX.find(clean)
            ?: throw IllegalArgumentException("Not an INSERT statement: ${clean.take(80)}")
        val tableName = headerMatch.groupValues[1]
        val columns = headerMatch.groupValues[2].takeIf { it.isNotBlank() }
            ?.split(',')?.map { it.trim().trim('`', '"') }

        val valuesBody = clean.substring(headerMatch.range.last + 1).trim()
        val rowGroups = splitRowGroups(valuesBody)
        val rows = rowGroups.map { group ->
            SqlText.splitTopLevel(group, ',').map { SqlText.parseValueToken(it, useBackslashEscape) }
        }

        return Result(tableName, columns, rows)
    }

    /** Разбивает `(v1,v2),(v3,v4)` на список содержимого каждой пары скобок верхнего уровня. */
    private fun splitRowGroups(body: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var inQuote = false
        var start = -1
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (inQuote) {
                if (c == '\\' && i + 1 < body.length) { i += 2; continue }
                if (c == '\'') {
                    if (i + 1 < body.length && body[i + 1] == '\'') { i += 2; continue }
                    inQuote = false
                }
                i++
                continue
            }
            when (c) {
                '\'' -> inQuote = true
                '(' -> {
                    if (depth == 0) start = i + 1
                    depth++
                }
                ')' -> {
                    depth--
                    if (depth == 0 && start != -1) {
                        result.add(body.substring(start, i))
                        start = -1
                    }
                }
            }
            i++
        }
        return result
    }
}
