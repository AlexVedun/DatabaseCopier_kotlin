package com.example.databasecopier.adapter

// Без явного connect/login-таймаута недоступный удалённый сервер (закрытый firewall'ом порт,
// молча дропающий пакеты, а не отвечающий отказом) заставляет DriverManager.getConnection зависать
// на TCP-таймауте ОС (десятки минут), а UI при этом не показывает вообще никакой ошибки — процесс
// копирования выглядит просто зависшим без единого сообщения.
private const val CONNECT_TIMEOUT_SECONDS = 15

// Отдельный (гораздо более щедрый) таймаут на чтение ответа уже установленного соединения нужен,
// чтобы намертво зависший обычный запрос (например, джойн по information_schema, который на
// практике наблюдался висящим по много минут на удалённом MySQL с большим числом таблиц) не вешал
// весь процесс копирования безо всякой ошибки. DDL, время которой закономерно растёт с размером
// таблицы (CREATE INDEX/ADD CHECK/ADD FOREIGN KEY), временно отключает этот таймаут через
// JdbcTargetAdapter.withLongRunningDdl(): сервер не сообщает промежуточный прогресс, и построение
// индекса на сотнях миллионов строк может корректно занимать больше 10 минут.
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
    //
    // loginTimeout здесь НАМЕРЕННО равен SOCKET_TIMEOUT_SECONDS, а не CONNECT_TIMEOUT_SECONDS —
    // экспериментально подтверждено (см. историю коммита), что в mssql-jdbc 12.6.1 именно
    // loginTimeout, а не socketTimeout, реально ограничивает ожидание ответа на ЛЮБОЙ statement,
    // а не только сам логин: запрос к уже установленному соединению, специально рассчитанный на
    // 20 секунд (WAITFOR DELAY), падал с "Read timed out" примерно через 15 секунд при
    // loginTimeout=15 — при том же socketTimeout=600000 — и завершался успешно при loginTimeout=60.
    // Реальный кейс — CREATE UNIQUE INDEX на таблице в 13.4 млн строк, упавший с той же ошибкой
    // через ~15 секунд вместо ожидаемых до 10 минут. socketTimeout всё равно оставлен как более
    // строгая по семантике заявленная защита (см. выше) — на случай если в будущей версии
    // драйвера баг поправят и timeout снова станет тем, что описан в документации.
    DbType.SQLSERVER -> "jdbc:sqlserver://${config.host}:${config.port};databaseName=${config.database}" +
        ";encrypt=true;trustServerCertificate=true;loginTimeout=$SOCKET_TIMEOUT_SECONDS" +
        ";socketTimeout=${SOCKET_TIMEOUT_SECONDS * 1000}"
    DbType.SQLITE -> "jdbc:sqlite:${config.database}"
}
