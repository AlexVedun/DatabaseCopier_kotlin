package com.example.databasecopier.adapter

fun buildJdbcUrl(config: ConnectionConfig): String = when (config.type) {
    DbType.MYSQL -> "jdbc:mysql://${config.host}:${config.port}/${config.database}"
    DbType.POSTGRESQL -> "jdbc:postgresql://${config.host}:${config.port}/${config.database}"
    DbType.SQLSERVER -> "jdbc:sqlserver://${config.host}:${config.port};databaseName=${config.database}"
    DbType.SQLITE -> "jdbc:sqlite:${config.database}"
}
