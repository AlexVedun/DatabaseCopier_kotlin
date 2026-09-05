package com.example.databasecopier.adapter

// Без явного connect/login-таймаута недоступный удалённый сервер (закрытый firewall'ом порт,
// молча дропающий пакеты, а не отвечающий отказом) заставляет DriverManager.getConnection зависать
// на TCP-таймауте ОС (десятки минут), а UI при этом не показывает вообще никакой ошибки — процесс
// копирования выглядит просто зависшим без единого сообщения.
private const val CONNECT_TIMEOUT_SECONDS = 15

// Отдельный (гораздо более щедрый) таймаут на чтение ответа уже установленного соединения — большие
// батчи данных или создание индекса на огромной таблице легитимно могут занимать минуты. Но без
// ЛЮБОГО таймаута чтения намертво зависший запрос (например, джойн по information_schema, который
// на практике наблюдался висящим по много минут на удалённом MySQL с большим числом таблиц) вешает
// весь процесс копирования безо всякой ошибки — 10 минут ловит именно такие настоящие зависания, не
// мешая обычным медленным операциям.
private const val SOCKET_TIMEOUT_SECONDS = 600

fun buildJdbcUrl(config: ConnectionConfig): String = when (config.type) {
    // MySQL допускает "нулевые" даты ("0000-00-00", "0000-00-00 00:00:00") в DATE/DATETIME/
    // TIMESTAMP-колонках, если не включён строгий режим NO_ZERO_DATE — у них нет реального
    // календарного значения. По умолчанию mysql-connector-j (zeroDateTimeBehavior=EXCEPTION)
    // отказывается конвертировать такое значение в java.sql.Date/Timestamp и падает с "Zero date
    // value prohibited" прямо при чтении строки. CONVERT_TO_NULL — штатный режим драйвера для этого
    // случая: такая дата читается как NULL, а не роняет копирование всей таблицы.
    DbType.MYSQL -> "jdbc:mysql://${config.host}:${config.port}/${config.database}" +
        "?connectTimeout=${CONNECT_TIMEOUT_SECONDS * 1000}&socketTimeout=${SOCKET_TIMEOUT_SECONDS * 1000}" +
        "&zeroDateTimeBehavior=CONVERT_TO_NULL"
    DbType.POSTGRESQL -> "jdbc:postgresql://${config.host}:${config.port}/${config.database}" +
        "?connectTimeout=$CONNECT_TIMEOUT_SECONDS&socketTimeout=$SOCKET_TIMEOUT_SECONDS"
    // trustServerCertificate=true — большинство локальных/внутренних MSSQL-инсталляций используют
    // самоподписанный сертификат; encrypt=true оставляет соединение зашифрованным, но не проверяет
    // цепочку доверия сертификата (иначе современный mssql-jdbc отказывается подключаться).
    DbType.SQLSERVER -> "jdbc:sqlserver://${config.host}:${config.port};databaseName=${config.database}" +
        ";encrypt=true;trustServerCertificate=true;loginTimeout=$CONNECT_TIMEOUT_SECONDS" +
        ";socketTimeout=${SOCKET_TIMEOUT_SECONDS * 1000}"
    DbType.SQLITE -> "jdbc:sqlite:${config.database}"
}
