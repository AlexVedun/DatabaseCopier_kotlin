package com.example.databasecopier.adapter

fun buildJdbcUrl(config: ConnectionConfig): String = when (config.type) {
    DbType.MYSQL -> "jdbc:mysql://${config.host}:${config.port}/${config.database}"
    DbType.POSTGRESQL -> "jdbc:postgresql://${config.host}:${config.port}/${config.database}"
    // trustServerCertificate=true — большинство локальных/внутренних MSSQL-инсталляций используют
    // самоподписанный сертификат; encrypt=true оставляет соединение зашифрованным, но не проверяет
    // цепочку доверия сертификата (иначе современный mssql-jdbc отказывается подключаться).
    DbType.SQLSERVER -> "jdbc:sqlserver://${config.host}:${config.port};databaseName=${config.database}" +
        ";encrypt=true;trustServerCertificate=true"
    DbType.SQLITE -> "jdbc:sqlite:${config.database}"
}
