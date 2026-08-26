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
                else -> LogicalType.TEXT
            }
            DbType.SQLITE -> when {
                // SQLite динамически типизировано — колонка может хранить объявленный тип как
                // произвольную строку (в т.ч. пустую). Классифицируем по тем же эвристикам
                // "type affinity", что использует сам SQLite, вместо точного соответствия.
                t.isBlank() -> LogicalType.TEXT
                t.contains("bool") -> LogicalType.BOOLEAN
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
                else -> LogicalType.TEXT
            }
        }
    }

    fun toSqlType(dbType: DbType, type: LogicalType): String = when (dbType) {
        DbType.MYSQL -> when (type) {
            LogicalType.INTEGER -> "INT"
            LogicalType.BIGINT -> "BIGINT"
            LogicalType.VARCHAR -> "VARCHAR(255)"
            LogicalType.TEXT -> "TEXT"
            LogicalType.DECIMAL -> "DECIMAL(20,4)"
            LogicalType.BOOLEAN -> "TINYINT(1)"
            LogicalType.DATE -> "DATE"
            LogicalType.DATETIME -> "DATETIME"
            LogicalType.JSON -> "JSON"
            LogicalType.UUID -> "VARCHAR(36)"
        }
        DbType.POSTGRESQL -> when (type) {
            LogicalType.INTEGER -> "INTEGER"
            LogicalType.BIGINT -> "BIGINT"
            LogicalType.VARCHAR -> "VARCHAR(255)"
            LogicalType.TEXT -> "TEXT"
            LogicalType.DECIMAL -> "NUMERIC(20,4)"
            LogicalType.BOOLEAN -> "BOOLEAN"
            LogicalType.DATE -> "DATE"
            LogicalType.DATETIME -> "TIMESTAMP"
            LogicalType.JSON -> "JSONB"
            LogicalType.UUID -> "UUID"
        }
        DbType.SQLITE -> when (type) {
            LogicalType.INTEGER, LogicalType.BIGINT -> "INTEGER"
            LogicalType.BOOLEAN -> "INTEGER"
            LogicalType.VARCHAR, LogicalType.TEXT, LogicalType.DATE, LogicalType.DATETIME,
            LogicalType.JSON, LogicalType.UUID -> "TEXT"
            LogicalType.DECIMAL -> "NUMERIC"
        }
        DbType.SQLSERVER -> when (type) {
            LogicalType.INTEGER -> "INT"
            LogicalType.BIGINT -> "BIGINT"
            LogicalType.VARCHAR -> "NVARCHAR(255)"
            LogicalType.TEXT -> "NVARCHAR(MAX)"
            LogicalType.DECIMAL -> "DECIMAL(20,4)"
            LogicalType.BOOLEAN -> "BIT"
            LogicalType.DATE -> "DATE"
            LogicalType.DATETIME -> "DATETIME2"
            LogicalType.JSON -> "NVARCHAR(MAX)" // у MSSQL нет отдельного JSON-типа
            LogicalType.UUID -> "UNIQUEIDENTIFIER"
        }
    }
}
