package com.example.databasecopier.adapter

// Без явного connect/login-таймаута недоступный удалённый сервер (закрытый firewall'ом порт,
// молча дропающий пакеты, а не отвечающий отказом) заставляет DriverManager.getConnection зависать
// на TCP-таймауте ОС (десятки минут), а UI при этом не показывает вообще никакой ошибки — процесс
// копирования выглядит просто зависшим без единого сообщения.
private const val CONNECT_TIMEOUT_SECONDS = 15

fun buildJdbcUrl(config: ConnectionConfig): String = when (config.type) {
    DbType.MYSQL -> "jdbc:mysql://${config.host}:${config.port}/${config.database}" +
        "?connectTimeout=${CONNECT_TIMEOUT_SECONDS * 1000}"
    DbType.POSTGRESQL -> "jdbc:postgresql://${config.host}:${config.port}/${config.database}" +
        "?connectTimeout=$CONNECT_TIMEOUT_SECONDS"
    // trustServerCertificate=true — большинство локальных/внутренних MSSQL-инсталляций используют
    // самоподписанный сертификат; encrypt=true оставляет соединение зашифрованным, но не проверяет
    // цепочку доверия сертификата (иначе современный mssql-jdbc отказывается подключаться).
    DbType.SQLSERVER -> "jdbc:sqlserver://${config.host}:${config.port};databaseName=${config.database}" +
        ";encrypt=true;trustServerCertificate=true;loginTimeout=$CONNECT_TIMEOUT_SECONDS"
    DbType.SQLITE -> "jdbc:sqlite:${config.database}"
}
