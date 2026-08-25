package com.example.databasecopier.dump

/** Разбор блока PostgreSQL `COPY table (columns) FROM stdin;` ... `\.` (text-формат, tab-разделители). */
object CopyBlockParser {

    private val HEADER_REGEX = Regex(
        """(?is)^copy\s+[`"]?([\w]+)[`"]?\s*(?:\(([^)]*)\))?\s*from\s+stdin"""
    )

    const val TERMINATOR = "\\."

    data class Header(val tableName: String, val columns: List<String>?)

    fun parseHeader(statement: String): Header? {
        val clean = SqlText.stripLeadingComments(statement).trim()
        val match = HEADER_REGEX.find(clean) ?: return null
        val columns = match.groupValues[2].takeIf { it.isNotBlank() }
            ?.split(',')?.map { it.trim().trim('"') }
        return Header(match.groupValues[1], columns)
    }

    /** Разбирает одну строку данных COPY (tab-разделённую) в список значений, `\N` -> null. */
    fun parseDataLine(line: String): List<Any?> =
        line.split('\t').map { field ->
            if (field == "\\N") null else unescapeField(field)
        }

    private fun unescapeField(field: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < field.length) {
            val c = field[i]
            if (c == '\\' && i + 1 < field.length) {
                when (field[i + 1]) {
                    't' -> sb.append('\t')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    else -> sb.append(field[i + 1])
                }
                i += 2
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }
}
