package com.example.databasecopier.adapter

object TypeMapper {

    fun fromSqlType(dbType: DbType, sqlTypeName: String): LogicalType {
        val t = sqlTypeName.lowercase()
        return when (dbType) {
            DbType.MYSQL -> when {
                t == "tinyint(1)" || t == "boolean" || t == "bool" -> LogicalType.BOOLEAN
                t.startsWith("tinyint") || t.startsWith("smallint") || t.startsWith("mediumint") || t.startsWith("int") -> LogicalType.INTEGER
                t.startsWith("bigint") -> LogicalType.BIGINT
                t.startsWith("varchar") || t.startsWith("char") -> LogicalType.VARCHAR
                t.startsWith("text") || t.startsWith("longtext") || t.startsWith("mediumtext") -> LogicalType.TEXT
                t.startsWith("decimal") || t.startsWith("numeric") || t.startsWith("float") || t.startsWith("double") -> LogicalType.DECIMAL
                t == "date" -> LogicalType.DATE
                t.startsWith("datetime") || t.startsWith("timestamp") -> LogicalType.DATETIME
                t == "json" -> LogicalType.JSON
                // *blob (tiny/medium/long) и binary/varbinary — произвольные байты, а не строка в
                // кодировке соединения. Раньше попадали в ветку else -> TEXT и создавались на target
                // как LONGTEXT: вставка бинарных данных (например PNG-иконки) в текстовую колонку
                // падала с "Incorrect string value" (\x89PNG... не является валидной utf8mb4-строкой).
                t.endsWith("blob") || t.startsWith("binary") || t.startsWith("varbinary") -> LogicalType.BLOB
                else -> LogicalType.TEXT
            }
            DbType.POSTGRESQL -> when {
                t == "boolean" -> LogicalType.BOOLEAN
                t == "integer" || t == "smallint" || t == "serial" || t == "smallserial" -> LogicalType.INTEGER
                t == "bigint" || t == "bigserial" -> LogicalType.BIGINT
                t.startsWith("character varying") || t.startsWith("varchar") || t.startsWith("character") -> LogicalType.VARCHAR
                t == "text" -> LogicalType.TEXT
                t.startsWith("numeric") || t.startsWith("decimal") || t.startsWith("double") || t.startsWith("real") -> LogicalType.DECIMAL
                t == "date" -> LogicalType.DATE
                t.startsWith("timestamp") -> LogicalType.DATETIME
                t == "json" || t == "jsonb" -> LogicalType.JSON
                t == "uuid" -> LogicalType.UUID
                t == "bytea" -> LogicalType.BLOB
                else -> LogicalType.TEXT
            }
            DbType.SQLITE -> when {
                // SQLite динамически типизировано — колонка может хранить объявленный тип как
                // произвольную строку (в т.ч. пустую). Классифицируем по тем же эвристикам
                // "type affinity", что использует сам SQLite, вместо точного соответствия.
                t.isBlank() -> LogicalType.TEXT
                t.contains("bool") -> LogicalType.BOOLEAN
                t.contains("blob") -> LogicalType.BLOB
                t.contains("int") -> LogicalType.INTEGER
                t.contains("char") || t.contains("clob") -> LogicalType.VARCHAR
                t.contains("text") -> LogicalType.TEXT
                t.contains("json") -> LogicalType.JSON
                t.contains("uuid") -> LogicalType.UUID
                t == "date" -> LogicalType.DATE
                t.contains("datetime") || t.contains("timestamp") -> LogicalType.DATETIME
                t.contains("decimal") || t.contains("numeric") || t.contains("real") ||
                    t.contains("floa") || t.contains("doub") -> LogicalType.DECIMAL
                else -> LogicalType.TEXT
            }
            DbType.SQLSERVER -> when {
                t == "bit" -> LogicalType.BOOLEAN
                t == "tinyint" || t == "smallint" || t == "int" -> LogicalType.INTEGER
                t == "bigint" -> LogicalType.BIGINT
                t.startsWith("nvarchar") || t.startsWith("varchar") || t.startsWith("nchar") || t.startsWith("char") -> LogicalType.VARCHAR
                t == "text" || t == "ntext" -> LogicalType.TEXT
                t.startsWith("decimal") || t.startsWith("numeric") || t == "float" || t == "real" || t == "money" || t == "smallmoney" -> LogicalType.DECIMAL
                t == "date" -> LogicalType.DATE
                t.startsWith("datetime") || t == "smalldatetime" -> LogicalType.DATETIME
                t == "uniqueidentifier" -> LogicalType.UUID
                t == "binary" || t == "varbinary" || t == "image" -> LogicalType.BLOB
                else -> LogicalType.TEXT
            }
        }
    }

    // length значим только для LogicalType.VARCHAR — фактическая длина колонки источника
    // (см. ColumnDef.length). Раньше VARCHAR всегда становился VARCHAR(255) независимо от
    // исходной длины — это либо раздувало составные индексы на target сверх лимита длины ключа
    // СУБД (например MySQL "max key length is 3072 bytes" при исходных VARCHAR(100)),
    // либо обрезало бы данные для VARCHAR длиннее 255 в источнике.
    fun toSqlType(dbType: DbType, type: LogicalType, length: Int? = null): String = when (dbType) {
        DbType.MYSQL -> when (type) {
            LogicalType.INTEGER -> "INT"
            LogicalType.BIGINT -> "BIGINT"
            LogicalType.VARCHAR -> "VARCHAR(${length?.takeIf { it > 0 } ?: 255})"
            // LogicalType.TEXT сливает TEXT/MEDIUMTEXT/LONGTEXT источника в один тип (Types.kt
            // не хранит длину) — используем LONGTEXT (до 4 ГБ), самый ёмкий вариант, а не TEXT
            // (лимит 64 КБ), иначе данные из LONGTEXT-колонки источника обрежутся при вставке.
            LogicalType.TEXT -> "LONGTEXT"
            LogicalType.DECIMAL -> "DECIMAL(20,4)"
            LogicalType.BOOLEAN -> "TINYINT(1)"
            LogicalType.DATE -> "DATE"
            LogicalType.DATETIME -> "DATETIME"
            LogicalType.JSON -> "JSON"
            LogicalType.UUID -> "VARCHAR(36)"
            LogicalType.BLOB -> "LONGBLOB"
        }
        DbType.POSTGRESQL -> when (type) {
            LogicalType.INTEGER -> "INTEGER"
            LogicalType.BIGINT -> "BIGINT"
            LogicalType.VARCHAR -> "VARCHAR(${length?.takeIf { it > 0 } ?: 255})"
            LogicalType.TEXT -> "TEXT"
            LogicalType.DECIMAL -> "NUMERIC(20,4)"
            LogicalType.BOOLEAN -> "BOOLEAN"
            LogicalType.DATE -> "DATE"
            LogicalType.DATETIME -> "TIMESTAMP"
            LogicalType.JSON -> "JSONB"
            LogicalType.UUID -> "UUID"
            LogicalType.BLOB -> "BYTEA"
        }
        DbType.SQLITE -> when (type) {
            LogicalType.INTEGER, LogicalType.BIGINT -> "INTEGER"
            LogicalType.BOOLEAN -> "INTEGER"
            LogicalType.VARCHAR, LogicalType.TEXT, LogicalType.DATE, LogicalType.DATETIME,
            LogicalType.JSON, LogicalType.UUID -> "TEXT"
            LogicalType.DECIMAL -> "NUMERIC"
            LogicalType.BLOB -> "BLOB"
        }
        DbType.SQLSERVER -> when (type) {
            LogicalType.INTEGER -> "INT"
            LogicalType.BIGINT -> "BIGINT"
            // NVARCHAR без MAX ограничен 4000 символами — длина сверх этого лимита переносится
            // как NVARCHAR(MAX), а не обрезается.
            LogicalType.VARCHAR -> {
                val len = length?.takeIf { it > 0 } ?: 255
                if (len > 4000) "NVARCHAR(MAX)" else "NVARCHAR($len)"
            }
            LogicalType.TEXT -> "NVARCHAR(MAX)"
            LogicalType.DECIMAL -> "DECIMAL(20,4)"
            LogicalType.BOOLEAN -> "BIT"
            LogicalType.DATE -> "DATE"
            LogicalType.DATETIME -> "DATETIME2"
            LogicalType.JSON -> "NVARCHAR(MAX)" // у MSSQL нет отдельного JSON-типа
            LogicalType.UUID -> "UNIQUEIDENTIFIER"
            LogicalType.BLOB -> "VARBINARY(MAX)"
        }
    }
}
