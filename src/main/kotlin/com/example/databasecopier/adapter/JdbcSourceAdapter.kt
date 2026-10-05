package com.example.databasecopier.adapter

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.SQLException

class JdbcSourceAdapter(private val config: ConnectionConfig) : SourceAdapter {

    companion object {
        private val log = LoggerFactory.getLogger(JdbcSourceAdapter::class.java)
        // SQLite не хранит длину отдельно от объявленного типа (PRAGMA table_info возвращает
        // её как часть строки, например "VARCHAR(100)") — извлекаем тем же способом, что и для
        // дампов, см. CreateTableParser.
        private val VARCHAR_LENGTH_REGEX = Regex("""(?i)^(?:varchar|char)\s*\((\d+)\)""")
    }

    private lateinit var connection: Connection

    override fun connect() {
        log.info("Источник {} ({}:{}/{}) — подключаюсь", config.type, config.host, config.port, config.database)
        val start = System.currentTimeMillis()
        try {
            connection = DriverManager.getConnection(buildJdbcUrl(config), config.username, config.password)
            log.info("Источник {} ({}:{}/{}) — подключено за {} мс", config.type, config.host, config.port, config.database, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            log.error("Источник {} ({}:{}/{}) — подключение не удалось за {} мс: {}", config.type, config.host, config.port, config.database, System.currentTimeMillis() - start, e.message)
            throw e
        }
    }

    // Некоторые источники (в частности удалённый shared-хостинг MySQL, см. историю коммита) молча
    // роняют простаивающее соединение — например, пока на приёмнике долго строится индекс/FK для
    // ДРУГИХ таблиц (source в это время не используется). Причём это не всегда чистое закрытие с
    // FIN/RST: если соединение обрывает промежуточный firewall/NAT (частый случай на shared-хостинге),
    // ни один из концов об этом не узнаёт — connection.isValid() в таком случае ничего не замечает
    // (сокет с точки зрения клиента выглядит совершенно живым), и падает уже РЕАЛЬНЫЙ запрос, причём
    // не через 5 секунд, а только через полный socketTimeout. Поэтому вместо (ненадёжной здесь)
    // проверки connection.isValid() ДО запроса — перехватываем настоящую ошибку связи ПОСЛЕ запроса
    // и повторяем его один раз на свежем соединении; это работает независимо от того, как именно
    // соединение умерло.
    private inline fun <T> withConnectionRetry(operation: () -> T): T {
        return try {
            operation()
        } catch (e: SQLException) {
            if (!looksLikeConnectionFailure(e)) throw e
            log.warn("Источник {} ({}:{}/{}) — соединение разорвано ({}), переподключаюсь и повторяю запрос", config.type, config.host, config.port, config.database, e.message)
            connect()
            operation()
        }
    }

    // SQLState класса "08" ("connection exception") — портируемый между драйверами признак именно
    // сетевой/соединенческой ошибки, а не ошибки в самом SQL. Дополнительно сверяемся с текстом
    // сообщения на случай, если конкретный драйвер не проставил sqlState как положено (так и
    // оказалось у mysql-connector-j для "Communications link failure").
    private fun looksLikeConnectionFailure(e: SQLException): Boolean {
        if (e.sqlState?.startsWith("08") == true) return true
        val msg = e.message?.lowercase() ?: ""
        return "communications link failure" in msg ||
            "connection reset" in msg ||
            "broken pipe" in msg ||
            "connection is closed" in msg ||
            "connection has been closed" in msg
    }

    override fun listTables(): Map<String, Long?> = withConnectionRetry {
        val startedAt = System.currentTimeMillis()
        log.info("Источник {} ({}) — получаю список таблиц и оценки количества строк", config.type, config.database)
        // Точный SELECT COUNT(*) по каждой таблице недопустим при первоначальной проверке
        // подключения: на базе в сотни гигабайт он читает практически все данные. Кроме того,
        // MySQL может закрыть ResultSet со списком таблиц при выполнении вложенного COUNT(*) на
        // том же соединении ("Operation not allowed after ResultSet closed"). Системные каталоги
        // возвращают все имена и дешёвые оценки строк одним запросом. Оценка nullable: у новой/
        // ещё не проанализированной таблицы СУБД может пока не иметь статистики.
        val sql = when (config.type) {
            DbType.MYSQL ->
                "SELECT table_name, table_rows AS row_count FROM information_schema.tables " +
                    "WHERE table_type = 'BASE TABLE' AND ${schemaClause()} ORDER BY table_name"
            DbType.POSTGRESQL ->
                "SELECT c.relname AS table_name, " +
                    "CASE WHEN c.reltuples >= 0 THEN c.reltuples::bigint ELSE NULL END AS row_count " +
                    "FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace " +
                    "WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') ORDER BY c.relname"
            DbType.SQLSERVER ->
                "SELECT t.name AS table_name, SUM(p.rows) AS row_count " +
                    "FROM sys.tables t JOIN sys.schemas s ON s.schema_id = t.schema_id " +
                    "JOIN sys.partitions p ON p.object_id = t.object_id AND p.index_id IN (0, 1) " +
                    "WHERE s.name = 'dbo' GROUP BY t.name ORDER BY t.name"
            // SQLite не хранит статистику числа строк; точный подсчёт выполняется ниже уже после
            // закрытия ResultSet со списком таблиц.
            DbType.SQLITE ->
                "SELECT name AS table_name, NULL AS row_count FROM sqlite_master " +
                    "WHERE type = 'table' AND name NOT LIKE 'sqlite\\_%' ESCAPE '\\' ORDER BY name"
        }
        val result = LinkedHashMap<String, Long?>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    val name = rs.getString("table_name")
                    val estimate = rs.getLong("row_count")
                    result[name] = if (rs.wasNull()) null else estimate
                }
            }
        }
        if (config.type == DbType.SQLITE) {
            // Для SQLite пользователь предпочёл прежнее точное поведение: локальные файлы обычно
            // невелики, а системной оценки количества строк SQLite не предоставляет. Имена уже
            // собраны и ResultSet закрыт, поэтому COUNT(*) не конфликтует с его обходом.
            for (table in result.keys.toList()) result[table] = countRows(table)
        }
        log.info(
            "Источник {} ({}) — список таблиц получен: {}, оценки строк доступны для {}, {} мс",
            config.type,
            config.database,
            result.size,
            result.values.count { it != null },
            System.currentTimeMillis() - startedAt,
        )
        return result
    }

    override fun listViews(): List<String> = withConnectionRetry {
        val sql = if (config.type == DbType.SQLITE) {
            "SELECT name FROM sqlite_master WHERE type = 'view'"
        } else {
            "SELECT table_name AS name FROM information_schema.views WHERE ${schemaClause()}"
        }
        val result = mutableListOf<String>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs -> while (rs.next()) result.add(rs.getString("name")) }
        }
        return@withConnectionRetry result
    }

    override fun getViewDefinition(view: String): String = withConnectionRetry {
        val raw = when (config.type) {
            DbType.SQLITE ->
                connection.prepareStatement("SELECT sql FROM sqlite_master WHERE type = 'view' AND name = ?").use { ps ->
                    ps.setString(1, view)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "" }
                }
            // information_schema.views.view_definition в MySQL нередко урезан/переписан сервером —
            // SHOW CREATE VIEW отдаёт оригинальный текст надёжнее.
            DbType.MYSQL ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SHOW CREATE VIEW ${quote(view)}").use { rs ->
                        rs.next()
                        rs.getString("Create View")
                    }
                }
            DbType.POSTGRESQL ->
                connection.prepareStatement(
                    "SELECT view_definition FROM information_schema.views WHERE table_name = ? AND ${schemaClause()}"
                ).use { ps ->
                    ps.setString(1, view)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "" }
                }
            DbType.SQLSERVER ->
                connection.prepareStatement(
                    "SELECT sm.definition FROM sys.sql_modules sm JOIN sys.views v ON sm.object_id = v.object_id WHERE v.name = ?"
                ).use { ps ->
                    ps.setString(1, view)
                    ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else "" }
                }
        }
        // Каждый источник возвращает разное обрамление (полный CREATE VIEW ... AS у MySQL/MSSQL/
        // SQLite, голое тело у Postgres) — приводим к единому виду "только тело SELECT", чтобы
        // TargetAdapter.createView() мог единообразно оборачивать его в CREATE VIEW ... AS <тело>.
        val selectIdx = Regex("""(?i)\bSELECT\b""").find(raw)?.range?.first ?: return raw.trim().trimEnd(';')
        return raw.substring(selectIdx).trim().trimEnd(';')
    }

    override fun listRoutines(): List<RoutineRef> = withConnectionRetry {
        val result = mutableListOf<RoutineRef>()
        when (config.type) {
            DbType.SQLITE -> {} // SQLite не поддерживает хранимые процедуры/функции вовсе
            DbType.MYSQL ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT routine_name, routine_type FROM information_schema.routines WHERE routine_schema = '${config.database}'"
                    ).use { rs ->
                        while (rs.next()) {
                            val kind = if (rs.getString("routine_type") == "FUNCTION") RoutineKind.FUNCTION else RoutineKind.PROCEDURE
                            result.add(RoutineRef(rs.getString("routine_name"), kind))
                        }
                    }
                }
            // Postgres/MSSQL: по решению пользователя копируются только PROCEDURE, не FUNCTION —
            // в этих диалектах процедуры и функции синтаксически и семантически расходятся сильнее,
            // чем в MySQL (в Postgres, например, у функций и процедур разная семантика вызова —
            // CALL vs SELECT/обычное выражение), так что унифицированный перенос обеих категорий
            // не был бы такой же безопасной операцией, как для MySQL.
            DbType.POSTGRESQL ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace " +
                            "WHERE n.nspname = 'public' AND p.prokind = 'p'"
                    ).use { rs -> while (rs.next()) result.add(RoutineRef(rs.getString("proname"), RoutineKind.PROCEDURE)) }
                }
            DbType.SQLSERVER ->
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT name FROM sys.objects WHERE type = 'P' AND is_ms_shipped = 0").use { rs ->
                        while (rs.next()) result.add(RoutineRef(rs.getString("name"), RoutineKind.PROCEDURE))
                    }
                }
        }
        result
    }

    // MySQL включает DEFINER=`user`@`host` в текст, возвращаемый SHOW CREATE PROCEDURE/FUNCTION —
    // если пользователь target-подключения не совпадает с этим definer'ом (обычный случай — разные
    // среды/учётки на источнике и приёмнике) и/или не обладает SUPER-привилегией, CREATE падает с
    // "Access denied; you need ... SUPER privilege(s) ... to perform this operation". DEFINER не
    // несёт содержательного смысла при переносе в другую БД — вырезаем его, тогда MySQL сам
    // подставит текущего пользователя target-соединения.
    private val mysqlDefinerPattern = Regex("""DEFINER\s*=\s*`[^`]*`@`[^`]*`\s*""", RegexOption.IGNORE_CASE)

    override fun getRoutineDefinition(routine: RoutineRef): String = withConnectionRetry {
        when (config.type) {
            DbType.MYSQL -> {
                val showKeyword = if (routine.kind == RoutineKind.FUNCTION) "FUNCTION" else "PROCEDURE"
                val columnName = if (routine.kind == RoutineKind.FUNCTION) "Create Function" else "Create Procedure"
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SHOW CREATE $showKeyword ${quote(routine.name)}").use { rs ->
                        rs.next()
                        mysqlDefinerPattern.replace(rs.getString(columnName), "")
                    }
                }
            }
            // pg_get_functiondef возвращает готовый "CREATE OR REPLACE PROCEDURE ... AS $$ ... $$
            // LANGUAGE ..." целиком — в отличие от view, тело процедуры не нужно ни выделять, ни
            // переупаковывать во что-то другое перед выполнением на target.
            DbType.POSTGRESQL ->
                connection.prepareStatement(
                    "SELECT pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace " +
                        "WHERE n.nspname = 'public' AND p.proname = ? AND p.prokind = 'p'"
                ).use { ps ->
                    ps.setString(1, routine.name)
                    ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
                }
            DbType.SQLSERVER ->
                connection.prepareStatement(
                    "SELECT sm.definition FROM sys.sql_modules sm JOIN sys.objects o ON sm.object_id = o.object_id " +
                        "WHERE o.name = ? AND o.type = 'P'"
                ).use { ps ->
                    ps.setString(1, routine.name)
                    ps.executeQuery().use { rs -> rs.next(); rs.getString(1) }
                }
            DbType.SQLITE -> throw UnsupportedOperationException("SQLite does not support stored routines")
        }
    }

    override fun getTableStructure(table: String): TableStructure = withConnectionRetry {
        if (config.type == DbType.SQLITE) return@withConnectionRetry getSqliteTableStructure(table)

        val identityColumns = if (config.type == DbType.SQLSERVER) readMssqlIdentityColumns(table) else emptySet()
        val columnComments = getColumnComments(table)
        val columns = mutableListOf<ColumnDef>()
        val columnsSql = if (config.type == DbType.MYSQL) {
            // column_type (а не только data_type) нужен, чтобы отличить tinyint(1) от прочих
            // tinyint — data_type для обоих просто "tinyint", теряя ширину. Это важно, потому что
            // mysql-connector-j по умолчанию (tinyInt1isBit=true) читает значения tinyint(1) как
            // Boolean через getObject(), а не Int — если TypeMapper классифицирует такую колонку
            // как INTEGER (как это было раньше), target-колонка создаётся INTEGER, но в неё летят
            // Boolean-значения, и JDBC-драйвер target'а падает с "column is of type X but expression
            // is of type boolean" (например Postgres).
            "SELECT column_name, data_type, column_type, is_nullable, extra, column_default, collation_name, character_maximum_length " +
                "FROM information_schema.columns WHERE table_name = ? AND ${schemaClause()} ORDER BY ordinal_position"
        } else {
            "SELECT column_name, data_type, is_nullable, column_default, collation_name, character_maximum_length " +
                "FROM information_schema.columns WHERE table_name = ? AND ${schemaClause()} ORDER BY ordinal_position"
        }
        connection.prepareStatement(columnsSql).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val name = rs.getString("column_name")
                    val rawDefault = rs.getString("column_default")
                    val autoIncrement = when (config.type) {
                        DbType.MYSQL -> rs.getString("extra")?.contains("auto_increment", ignoreCase = true) == true
                        DbType.POSTGRESQL -> rawDefault?.startsWith("nextval(") == true
                        DbType.SQLSERVER -> name in identityColumns
                        DbType.SQLITE -> false
                    }
                    columns.add(
                        ColumnDef(
                            name = name,
                            type = TypeMapper.fromSqlType(
                                config.type,
                                if (config.type == DbType.MYSQL) rs.getString("column_type") else rs.getString("data_type"),
                            ),
                            nullable = rs.getString("is_nullable") == "YES",
                            autoIncrement = autoIncrement,
                            // nextval(...)/identity уже подразумевают генерацию значения — обычный
                            // DEFAULT для таких колонок не нужен и не переносится.
                            defaultValue = if (autoIncrement) null else rawDefault,
                            collation = rs.getString("collation_name"),
                            comment = columnComments[name],
                            // getLong, а не getInt: для TEXT/LONGTEXT character_maximum_length может
                            // быть 4294967295 (не помещается в Int) — такие значения не относятся к
                            // VARCHAR (для которого только и значим length) и просто отбрасываются.
                            length = rs.getLong("character_maximum_length")
                                .takeIf { !rs.wasNull() && it in 1..Int.MAX_VALUE }?.toInt(),
                        )
                    )
                }
            }
        }

        return TableStructure(table, columns, primaryKeyColumns(table), getIndexes(table), getCheckConstraints(table), getTableComment(table))
    }

    // Кэшируется на всю сессию (наполняется одним запросом при первом обращении), а не на таблицу —
    // изначальный JOIN information_schema.table_constraints × key_column_usage по table_name=?
    // реально наблюдался зависающим на МИНУТЫ на одном вызове против удалённого MySQL-сервера,
    // хостящего десятки схем: подтверждено дважды через jstack (блокировка на чтении сокета внутри
    // executeQuery) и напрямую в phpMyAdmin (тот же самый JOIN упал по Gateway Timeout). JOIN двух
    // I_S-представлений на MySQL не всегда проталкивает фильтр внутрь каждого представления и может
    // разворачиваться в материализацию по всем схемам сервера, а не только по нужной. При 493
    // таблицах в сессии это означало бы копирование, не начинающееся вообще никогда.
    private var primaryKeysBySchema: Map<String, List<String>>? = null

    private fun primaryKeyColumns(table: String): List<String> {
        val cache = primaryKeysBySchema ?: loadAllPrimaryKeys().also { primaryKeysBySchema = it }
        return cache[table] ?: emptyList()
    }

    private fun loadAllPrimaryKeys(): Map<String, List<String>> {
        // Для MySQL join с table_constraints вообще не нужен: constraint_name для PRIMARY KEY в
        // MySQL всегда буквально "PRIMARY" — этого достаточно, чтобы отфильтровать нужные строки
        // прямо в key_column_usage, без второй I_S-таблицы. Один отфильтрованный SELECT по одной
        // таблице — именно то, что не провоцирует деградацию оптимизатора, в отличие от JOIN двух
        // I_S-представлений (см. комментарий у primaryKeysBySchema).
        //
        // Postgres/MSSQL этой деградацией не страдают (их каталоги — обычные системные таблицы, а
        // не пересчитываемые на лету представления) и не гарантируют "PRIMARY" как имя constraint'а,
        // поэтому для них join остаётся необходимым.
        val sql = if (config.type == DbType.MYSQL) {
            "SELECT table_name, column_name FROM information_schema.key_column_usage " +
                "WHERE constraint_name = 'PRIMARY' AND ${schemaClause()} " +
                "ORDER BY table_name, ordinal_position"
        } else {
            "SELECT tc.table_name, kcu.column_name FROM information_schema.table_constraints tc " +
                "JOIN information_schema.key_column_usage kcu " +
                "  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema " +
                "  AND tc.table_name = kcu.table_name " +
                "WHERE tc.constraint_type = 'PRIMARY KEY' AND ${schemaClause("tc")} " +
                "ORDER BY tc.table_name, kcu.ordinal_position"
        }
        val result = LinkedHashMap<String, MutableList<String>>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery(sql).use { rs ->
                while (rs.next()) {
                    result.getOrPut(rs.getString("table_name")) { mutableListOf() }.add(rs.getString("column_name"))
                }
            }
        }
        return result
    }

    // Кэшируется на всю сессию (тот же приём, что и у primaryKeysBySchema/checkConstraintsBySchema/
    // foreignKeysBySchema) — но здесь причина не JOIN: даже однотабличный WHERE table_name = ?
    // запрос к information_schema.columns на практике наблюдался зависающим на минуты (подтверждено
    // jstack: блокировка на чтении сокета внутри getColumnComments) на этом же удалённом MySQL,
    // хостящем десятки схем — похоже, information_schema там не проталкивает фильтр по имени таблицы
    // на уровне сервера и материализует ответ по всему словарю независимо от WHERE. При 493 таблицах
    // в сессии повторение такого запроса один раз на таблицу означает часы простоя вместо одного
    // запроса, отфильтрованного только по схеме (более дешёвого предиката).
    private var columnCommentsBySchema: Map<String, Map<String, String>>? = null

    private fun loadAllMysqlColumnComments(): Map<String, Map<String, String>> {
        val result = LinkedHashMap<String, MutableMap<String, String>>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT table_name, column_name, column_comment FROM information_schema.columns " +
                    "WHERE column_comment != '' AND ${schemaClause()}"
            ).use { rs ->
                while (rs.next()) {
                    result.getOrPut(rs.getString("table_name")) { mutableMapOf() }[rs.getString("column_name")] =
                        rs.getString("column_comment")
                }
            }
        }
        return result
    }

    private fun getColumnComments(table: String): Map<String, String> {
        if (config.type == DbType.MYSQL) {
            val cache = columnCommentsBySchema ?: loadAllMysqlColumnComments().also { columnCommentsBySchema = it }
            return cache[table] ?: emptyMap()
        }
        val sql = when (config.type) {
            DbType.POSTGRESQL ->
                "SELECT a.attname AS column_name, d.description AS column_comment " +
                    "FROM pg_description d JOIN pg_class c ON d.objoid = c.oid " +
                    "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = d.objsubid " +
                    "WHERE c.relname = ? AND d.objsubid > 0"
            DbType.SQLSERVER ->
                "SELECT c.name AS column_name, CAST(ep.value AS NVARCHAR(MAX)) AS column_comment " +
                    "FROM sys.extended_properties ep " +
                    "JOIN sys.tables t ON ep.major_id = t.object_id " +
                    "JOIN sys.columns c ON ep.major_id = c.object_id AND ep.minor_id = c.column_id " +
                    "WHERE t.name = ? AND ep.name = 'MS_Description' AND ep.minor_id > 0"
            else -> return emptyMap()
        }
        val result = mutableMapOf<String, String>()
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs -> while (rs.next()) result[rs.getString("column_name")] = rs.getString("column_comment") }
        }
        return result
    }

    // Тот же приём кэширования на сессию, что и у columnCommentsBySchema — см. её комментарий.
    private var tableCommentsBySchema: Map<String, String>? = null

    private fun loadAllMysqlTableComments(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT table_name, table_comment FROM information_schema.tables WHERE table_comment != '' AND ${schemaClause()}"
            ).use { rs -> while (rs.next()) result[rs.getString("table_name")] = rs.getString("table_comment") }
        }
        return result
    }

    private fun getTableComment(table: String): String? {
        if (config.type == DbType.MYSQL) {
            val cache = tableCommentsBySchema ?: loadAllMysqlTableComments().also { tableCommentsBySchema = it }
            return cache[table]
        }
        val sql = when (config.type) {
            DbType.POSTGRESQL ->
                "SELECT d.description FROM pg_description d JOIN pg_class c ON d.objoid = c.oid " +
                    "WHERE c.relname = ? AND d.objsubid = 0"
            DbType.SQLSERVER ->
                "SELECT CAST(ep.value AS NVARCHAR(MAX)) AS description FROM sys.extended_properties ep " +
                    "JOIN sys.tables t ON ep.major_id = t.object_id " +
                    "WHERE t.name = ? AND ep.name = 'MS_Description' AND ep.minor_id = 0"
            else -> return null
        }
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getString(1) else null }
        }
    }

    private data class IndexRow(val indexName: String, val columnName: String, val unique: Boolean, val position: Int, val indexType: String?)

    private fun buildIndexDefs(rows: List<IndexRow>): List<IndexDef> =
        rows.groupBy { it.indexName }.map { (name, cols) ->
            // FULLTEXT/SPATIAL индексы MySQL не подчиняются обычному лимиту длины ключа BTREE-индекса —
            // без различения типа они пересоздавались бы на target как обычный составной CREATE INDEX
            // и падали с "Specified key was too long" на любых TEXT/BLOB-колонках (см. Types.kt).
            val kind = when (cols.first().indexType?.uppercase()) {
                "FULLTEXT" -> IndexKind.FULLTEXT
                "SPATIAL" -> IndexKind.SPATIAL
                else -> IndexKind.NORMAL
            }
            IndexDef(name, cols.sortedBy { it.position }.map { it.columnName }, cols.first().unique, kind)
        }

    // Тот же приём кэширования на сессию, что и у columnCommentsBySchema — см. её комментарий.
    private var indexesBySchema: Map<String, List<IndexDef>>? = null

    private fun loadAllMysqlIndexes(): Map<String, List<IndexDef>> {
        data class RawRow(val tableName: String, val row: IndexRow)
        val rows = mutableListOf<RawRow>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT table_name, index_name, column_name, non_unique = 0 AS is_unique, seq_in_index AS position, index_type " +
                    "FROM information_schema.statistics " +
                    "WHERE index_name != 'PRIMARY' AND ${schemaClause()} " +
                    "ORDER BY table_name, index_name, seq_in_index"
            ).use { rs ->
                while (rs.next()) {
                    rows.add(
                        RawRow(
                            rs.getString("table_name"),
                            IndexRow(rs.getString("index_name"), rs.getString("column_name"), rs.getBoolean("is_unique"), rs.getInt("position"), rs.getString("index_type")),
                        )
                    )
                }
            }
        }
        return rows.groupBy { it.tableName }.mapValues { (_, tableRows) -> buildIndexDefs(tableRows.map { it.row }) }
    }

    private fun getIndexes(table: String): List<IndexDef> {
        if (config.type == DbType.MYSQL) {
            val cache = indexesBySchema ?: loadAllMysqlIndexes().also { indexesBySchema = it }
            return cache[table] ?: emptyList()
        }
        val sql = when (config.type) {
            DbType.POSTGRESQL ->
                "SELECT ic.relname AS index_name, a.attname AS column_name, ix.indisunique AS is_unique, " +
                    "array_position(ix.indkey, a.attnum) AS position " +
                    "FROM pg_index ix " +
                    "JOIN pg_class ic ON ic.oid = ix.indexrelid " +
                    "JOIN pg_class tc ON tc.oid = ix.indrelid " +
                    "JOIN pg_attribute a ON a.attrelid = tc.oid AND a.attnum = ANY(ix.indkey) " +
                    "WHERE tc.relname = ? AND NOT ix.indisprimary " +
                    "ORDER BY index_name, position"
            DbType.SQLSERVER ->
                "SELECT i.name AS index_name, c.name AS column_name, i.is_unique, ic.key_ordinal AS position " +
                    "FROM sys.indexes i " +
                    "JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id " +
                    "JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id " +
                    "JOIN sys.tables t ON t.object_id = i.object_id " +
                    "WHERE t.name = ? AND i.is_primary_key = 0 AND i.name IS NOT NULL " +
                    "ORDER BY i.name, ic.key_ordinal"
            else -> return emptyList()
        }
        val rows = mutableListOf<IndexRow>()
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    rows.add(IndexRow(rs.getString("index_name"), rs.getString("column_name"), rs.getBoolean("is_unique"), rs.getInt("position"), null))
                }
            }
        }
        return buildIndexDefs(rows)
    }

    // Тот же джойн-по-словарю паттерн, что и у PRIMARY KEY (см. loadAllPrimaryKeys) — тоже кэшируется
    // на всю сессию одним запросом, а не по одному на таблицу.
    private var checkConstraintsBySchema: Map<String, List<CheckConstraintDef>>? = null

    private fun getCheckConstraints(table: String): List<CheckConstraintDef> {
        val cache = checkConstraintsBySchema ?: loadAllCheckConstraints().also { checkConstraintsBySchema = it }
        return cache[table] ?: emptyList()
    }

    private data class RawCheck(val tableName: String, val constraintName: String, val checkClause: String)

    private fun loadAllCheckConstraints(): Map<String, List<CheckConstraintDef>> {
        val raw = when (config.type) {
            // Тот же приём, что и для PRIMARY KEY (см. loadAllPrimaryKeys): вместо JOIN двух I_S-
            // представлений — два отдельных однотабличных запроса, склеенных по constraint_name уже
            // в Kotlin. table_constraints даёт (table_name, constraint_name), check_constraints даёт
            // (constraint_name, check_clause) — порознь каждый фильтруется по своему полю схемы
            // напрямую и не провоцирует деградацию оптимизатора, которая наблюдалась у JOIN-варианта.
            DbType.MYSQL -> {
                val tableByConstraint = mutableMapOf<String, String>()
                connection.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT table_name, constraint_name FROM information_schema.table_constraints " +
                            "WHERE constraint_type = 'CHECK' AND ${schemaClause()}"
                    ).use { rs -> while (rs.next()) tableByConstraint[rs.getString("constraint_name")] = rs.getString("table_name") }
                }
                val result = mutableListOf<RawCheck>()
                connection.createStatement().use { stmt ->
                    stmt.executeQuery(
                        "SELECT constraint_name, check_clause FROM information_schema.check_constraints " +
                            "WHERE constraint_schema = '${config.database}'"
                    ).use { rs ->
                        while (rs.next()) {
                            val constraintName = rs.getString("constraint_name")
                            val tableName = tableByConstraint[constraintName] ?: continue
                            result.add(RawCheck(tableName, constraintName, rs.getString("check_clause")))
                        }
                    }
                }
                result
            }
            DbType.POSTGRESQL -> {
                val result = mutableListOf<RawCheck>()
                val sql = "SELECT tc.table_name, tc.constraint_name, cc.check_clause FROM information_schema.table_constraints tc " +
                    "JOIN information_schema.check_constraints cc " +
                    "  ON tc.constraint_name = cc.constraint_name AND tc.table_schema = cc.constraint_schema " +
                    "WHERE tc.constraint_type = 'CHECK' AND ${schemaClause("tc")}"
                connection.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) result.add(RawCheck(rs.getString("table_name"), rs.getString("constraint_name"), rs.getString("check_clause")))
                    }
                }
                result
            }
            DbType.SQLSERVER -> {
                val result = mutableListOf<RawCheck>()
                val sql = "SELECT t.name AS table_name, cc.name AS constraint_name, cc.definition AS check_clause " +
                    "FROM sys.check_constraints cc JOIN sys.tables t ON cc.parent_object_id = t.object_id"
                connection.createStatement().use { stmt ->
                    stmt.executeQuery(sql).use { rs ->
                        while (rs.next()) result.add(RawCheck(rs.getString("table_name"), rs.getString("constraint_name"), rs.getString("check_clause")))
                    }
                }
                result
            }
            else -> return emptyMap()
        }
        // Postgres 12+ отражает обычный NOT NULL на колонке как отдельный синтетический CHECK
        // ("col IS NOT NULL", имя вида "2200_16384_1_not_null") — это уже покрыто ColumnDef.nullable,
        // поэтому такие записи отфильтровываются, чтобы не создавать избыточный/дублирующий CHECK.
        val notNullPattern = Regex("""(?i)^"?[\w]+"?\s+IS\s+NOT\s+NULL$""")
        // MySQL 8 автоматически добавляет CHECK (json_valid(`col`)) на каждую JSON-колонку — это
        // системный чек, не часть пользовательской схемы. Он копируется буквально (с backtick-
        // квотированием идентификатора, которое не является валидным синтаксисом ни в Postgres, ни
        // в MSSQL — "syntax error at or near ')'"), да и функции json_valid() в этих СУБД просто нет.
        // Валидность JSON на target и так обеспечивается самим типом колонки (JSONB у Postgres
        // валидирует при вставке), поэтому такой чек безопасно и достаточно пропустить целиком.
        val mysqlJsonValidPattern = Regex("""(?i)^json_valid\(`[\w]+`\)$""")
        val result = LinkedHashMap<String, MutableList<CheckConstraintDef>>()
        for ((tableName, constraintName, clause) in raw) {
            if (notNullPattern.matches(clause.trim())) continue
            if (config.type == DbType.MYSQL && mysqlJsonValidPattern.matches(clause.trim())) continue
            result.getOrPut(tableName) { mutableListOf() }.add(CheckConstraintDef(constraintName, clause))
        }
        return result
    }

    /** SQLite не поддерживает `information_schema` — структура читается через `PRAGMA table_info`. */
    private fun getSqliteTableStructure(table: String): TableStructure {
        data class Raw(val name: String, val type: String, val notNull: Boolean, val default: String?, val pk: Int)
        val raws = mutableListOf<Raw>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA table_info(${quote(table)})").use { rs ->
                while (rs.next()) {
                    raws.add(
                        Raw(
                            name = rs.getString("name"),
                            type = rs.getString("type") ?: "",
                            notNull = rs.getInt("notnull") != 0,
                            default = rs.getString("dflt_value"),
                            pk = rs.getInt("pk"),
                        )
                    )
                }
            }
        }
        // В SQLite единственная INTEGER-колонка PK — это alias rowid, который автоинкрементится
        // сам по себе (без явного ключевого слова AUTOINCREMENT в исходном CREATE TABLE).
        val singlePk = raws.filter { it.pk > 0 }.singleOrNull()
        val columns = raws.map { r ->
            val autoIncrement = singlePk?.name == r.name && r.type.contains("int", ignoreCase = true)
            ColumnDef(
                name = r.name,
                type = TypeMapper.fromSqlType(DbType.SQLITE, r.type),
                nullable = !r.notNull,
                autoIncrement = autoIncrement,
                defaultValue = if (autoIncrement) null else r.default,
                length = VARCHAR_LENGTH_REGEX.find(r.type)?.groupValues?.get(1)?.toIntOrNull(),
            )
        }
        val primaryKey = raws.filter { it.pk > 0 }.sortedBy { it.pk }.map { it.name }
        return TableStructure(table, columns, primaryKey, getSqliteIndexes(table), getSqliteCheckConstraints(table))
    }

    private fun getSqliteIndexes(table: String): List<IndexDef> {
        data class IndexMeta(val name: String, val unique: Boolean, val origin: String)
        val indexMetas = mutableListOf<IndexMeta>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery("PRAGMA index_list(${quote(table)})").use { rs ->
                while (rs.next()) {
                    indexMetas.add(IndexMeta(rs.getString("name"), rs.getInt("unique") != 0, rs.getString("origin")))
                }
            }
        }
        // origin='pk' — неявный индекс, который SQLite сам создаёт для PRIMARY KEY(...);
        // он уже отражён в structure.primaryKey и не должен дублироваться как обычный индекс.
        return indexMetas.filter { it.origin != "pk" }.map { meta ->
            val columns = mutableListOf<String>()
            connection.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA index_info(${quote(meta.name)})").use { rs ->
                    while (rs.next()) columns.add(rs.getString("name"))
                }
            }
            IndexDef(meta.name, columns, meta.unique)
        }
    }

    /** SQLite не хранит CHECK-ограничения отдельным каталогом — извлекаются регэкспом из
     *  исходного текста CREATE TABLE, хранящегося в sqlite_master.sql. */
    private fun getSqliteCheckConstraints(table: String): List<CheckConstraintDef> {
        val ddl = connection.prepareStatement("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?").use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString("sql") else null }
        } ?: return emptyList()
        return Regex("""(?i)CHECK\s*\(([^()]*(?:\([^()]*\)[^()]*)*)\)""").findAll(ddl)
            .mapIndexed { idx, m -> CheckConstraintDef("chk_${table}_$idx", m.groupValues[1].trim()) }
            .toList()
    }

    private fun readMssqlIdentityColumns(table: String): Set<String> {
        val result = mutableSetOf<String>()
        val sql = "SELECT c.name FROM sys.identity_columns c JOIN sys.tables t ON c.object_id = t.object_id WHERE t.name = ?"
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs -> while (rs.next()) result.add(rs.getString("name")) }
        }
        return result
    }

    override fun getForeignKeys(table: String): List<ForeignKeyRef> = withConnectionRetry {
        if (config.type == DbType.SQLITE) {
            val result = mutableListOf<ForeignKeyRef>()
            connection.createStatement().use { stmt ->
                stmt.executeQuery("PRAGMA foreign_key_list(${quote(table)})").use { rs ->
                    while (rs.next()) {
                        result.add(
                            ForeignKeyRef(
                                columnName = rs.getString("from"),
                                referencedTable = rs.getString("table"),
                                referencedColumn = rs.getString("to"),
                                onDelete = parseAction(rs.getString("on_delete")),
                                onUpdate = parseAction(rs.getString("on_update")),
                            )
                        )
                    }
                }
            }
            return result
        }

        if (config.type == DbType.SQLSERVER) {
            // У MSSQL INFORMATION_SCHEMA.CONSTRAINT_COLUMN_USAGE (в отличие от Postgres) отдаёт
            // constrained-сторону, а не referenced — поэтому FK читаем через системные каталоги.
            val result = mutableListOf<ForeignKeyRef>()
            val sql = "SELECT cp.name AS column_name, tr.name AS referenced_table, cr.name AS referenced_column, " +
                "fk.delete_referential_action_desc AS on_delete, fk.update_referential_action_desc AS on_update " +
                "FROM sys.foreign_keys fk " +
                "JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id " +
                "JOIN sys.tables tp ON fkc.parent_object_id = tp.object_id " +
                "JOIN sys.columns cp ON fkc.parent_object_id = cp.object_id AND fkc.parent_column_id = cp.column_id " +
                "JOIN sys.tables tr ON fkc.referenced_object_id = tr.object_id " +
                "JOIN sys.columns cr ON fkc.referenced_object_id = cr.object_id AND fkc.referenced_column_id = cr.column_id " +
                "WHERE tp.name = ?"
            connection.prepareStatement(sql).use { ps ->
                ps.setString(1, table)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        result.add(
                            ForeignKeyRef(
                                columnName = rs.getString("column_name"),
                                referencedTable = rs.getString("referenced_table"),
                                referencedColumn = rs.getString("referenced_column"),
                                onDelete = parseAction(rs.getString("on_delete")),
                                onUpdate = parseAction(rs.getString("on_update")),
                            )
                        )
                    }
                }
            }
            return result
        }

        // MySQL: тот же джойн-по-словарю паттерн, что и у PRIMARY KEY/CHECK (см. loadAllPrimaryKeys/
        // loadAllCheckConstraints) — этот JOIN изначально стоял и здесь, по одному запросу на
        // таблицу, и на практике зависал на минуты на каждой таблице против удалённого MySQL-сервера,
        // хостящего десятки схем (493 таблицы в сессии = часы простоя, соединение к источнику успевало
        // протухнуть по wait_timeout ещё до завершения самой первой такой таблицы).
        if (config.type == DbType.MYSQL) {
            val cache = foreignKeysBySchema ?: loadAllMysqlForeignKeys().also { foreignKeysBySchema = it }
            return cache[table] ?: emptyList()
        }

        // Остальные варианты завершились выше, поэтому здесь тип уже сужен до PostgreSQL.
        val sql = "SELECT kcu.column_name, ccu.table_name AS referenced_table, ccu.column_name AS referenced_column, " +
            "rc.delete_rule AS on_delete, rc.update_rule AS on_update " +
            "FROM information_schema.table_constraints tc " +
            "JOIN information_schema.key_column_usage kcu " +
            "  ON tc.constraint_name = kcu.constraint_name AND tc.table_schema = kcu.table_schema " +
            "JOIN information_schema.constraint_column_usage ccu " +
            "  ON tc.constraint_name = ccu.constraint_name AND tc.table_schema = ccu.table_schema " +
            "JOIN information_schema.referential_constraints rc " +
            "  ON tc.constraint_name = rc.constraint_name AND tc.table_schema = rc.constraint_schema " +
            "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_name = ? AND ${schemaClause("tc")}"
        val result = mutableListOf<ForeignKeyRef>()
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    result.add(
                        ForeignKeyRef(
                            columnName = rs.getString("column_name"),
                            referencedTable = rs.getString("referenced_table"),
                            referencedColumn = rs.getString("referenced_column"),
                            onDelete = parseAction(rs.getString("on_delete")),
                            onUpdate = parseAction(rs.getString("on_update")),
                        )
                    )
                }
            }
        }
        return result
    }

    // Кэшируется на всю сессию, наполняется один раз (см. primaryKeysBySchema/checkConstraintsBySchema
    // для того же паттерна и подробного обоснования).
    private var foreignKeysBySchema: Map<String, List<ForeignKeyRef>>? = null

    private data class RawFk(
        val tableName: String,
        val constraintName: String,
        val columnName: String,
        val referencedTable: String,
        val referencedColumn: String,
    )

    private fun loadAllMysqlForeignKeys(): Map<String, List<ForeignKeyRef>> {
        // key_column_usage у MySQL (в отличие от Postgres) уже хранит referenced_table_name/
        // referenced_column_name прямо в себе — без JOIN с table_constraints. Единственное, зачем
        // раньше был нужен JOIN — delete_rule/update_rule, которые лежат только в
        // referential_constraints. Читаем их отдельным однотабличным запросом и склеиваем по
        // (table_name, constraint_name) уже в Kotlin — тот же приём, что и для CHECK-ограничений.
        val fkColumns = mutableListOf<RawFk>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT table_name, constraint_name, column_name, referenced_table_name, referenced_column_name " +
                    "FROM information_schema.key_column_usage " +
                    "WHERE referenced_table_name IS NOT NULL AND ${schemaClause()}"
            ).use { rs ->
                while (rs.next()) {
                    fkColumns.add(
                        RawFk(
                            tableName = rs.getString("table_name"),
                            constraintName = rs.getString("constraint_name"),
                            columnName = rs.getString("column_name"),
                            referencedTable = rs.getString("referenced_table_name"),
                            referencedColumn = rs.getString("referenced_column_name"),
                        )
                    )
                }
            }
        }
        val actionsByConstraint = mutableMapOf<Pair<String, String>, Pair<String?, String?>>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT table_name, constraint_name, delete_rule, update_rule " +
                    "FROM information_schema.referential_constraints WHERE constraint_schema = '${config.database}'"
            ).use { rs ->
                while (rs.next()) {
                    actionsByConstraint[rs.getString("table_name") to rs.getString("constraint_name")] =
                        rs.getString("delete_rule") to rs.getString("update_rule")
                }
            }
        }
        val result = LinkedHashMap<String, MutableList<ForeignKeyRef>>()
        for (fk in fkColumns) {
            val (onDelete, onUpdate) = actionsByConstraint[fk.tableName to fk.constraintName] ?: (null to null)
            result.getOrPut(fk.tableName) { mutableListOf() }.add(
                ForeignKeyRef(
                    columnName = fk.columnName,
                    referencedTable = fk.referencedTable,
                    referencedColumn = fk.referencedColumn,
                    onDelete = parseAction(onDelete),
                    onUpdate = parseAction(onUpdate),
                )
            )
        }
        return result
    }

    /** Приводит текстовое обозначение referential action (разное у каждой СУБД) к [ReferentialAction]. */
    private fun parseAction(raw: String?): ReferentialAction = when (raw?.uppercase()?.replace("_", " ")) {
        "CASCADE" -> ReferentialAction.CASCADE
        "SET NULL" -> ReferentialAction.SET_NULL
        "RESTRICT" -> ReferentialAction.RESTRICT
        "SET DEFAULT" -> ReferentialAction.SET_DEFAULT
        else -> ReferentialAction.NO_ACTION
    }

    override fun countRows(table: String): Long? {
        val startedAt = System.currentTimeMillis()
        log.info("Источник {} ({}) — таблица {}: точный подсчёт строк", config.type, config.database, table)
        return try {
            val count = withConnectionRetry {
                connection.createStatement().use { stmt ->
                    stmt.executeQuery("SELECT COUNT(*) FROM ${quote(table)}").use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            }
            log.info(
                "Источник {} ({}) — таблица {}: строк={}, подсчёт занял {} мс",
                config.type,
                config.database,
                table,
                count,
                System.currentTimeMillis() - startedAt,
            )
            count
        } catch (e: Exception) {
            log.warn(
                "Источник {} ({}) — таблица {}: не удалось определить точное число строк за {} мс: {}",
                config.type,
                config.database,
                table,
                System.currentTimeMillis() - startedAt,
                e.message,
            )
            null
        }
    }

    override fun readBatch(table: String, cursor: JsonElement?, batchSize: Int): BatchResult = withConnectionRetry {
        val structure = getTableStructure(table)
        val pkColumn = structure.primaryKey.firstOrNull()

        if (pkColumn != null) {
            val pkType = structure.columns.first { it.name == pkColumn }.type
            readBatchByPrimaryKey(table, pkColumn, pkType, structure.columns, cursor, batchSize)
        } else {
            readBatchByOffset(table, structure.columns, cursor, batchSize)
        }
    }

    private fun readBatchByPrimaryKey(
        table: String,
        pkColumn: String,
        pkType: LogicalType,
        columns: List<ColumnDef>,
        cursor: JsonElement?,
        batchSize: Int,
    ): BatchResult {
        val lastValue = cursor?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("value")?.jsonPrimitive?.content }
        val isMssql = config.type == DbType.SQLSERVER
        // MSSQL не поддерживает LIMIT — постраничность там выражается через
        // ORDER BY ... OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY (offset всегда 0, т.к. отсечение уже
        // сделано условием WHERE pk > ?, либо его нет вовсе на первом батче).
        val sql = if (lastValue != null) {
            if (isMssql) {
                "SELECT * FROM ${quote(table)} WHERE ${quote(pkColumn)} > ? " +
                    "ORDER BY ${quote(pkColumn)} OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY"
            } else {
                "SELECT * FROM ${quote(table)} WHERE ${quote(pkColumn)} > ? ORDER BY ${quote(pkColumn)} LIMIT ?"
            }
        } else {
            if (isMssql) {
                "SELECT * FROM ${quote(table)} ORDER BY ${quote(pkColumn)} OFFSET 0 ROWS FETCH NEXT ? ROWS ONLY"
            } else {
                "SELECT * FROM ${quote(table)} ORDER BY ${quote(pkColumn)} LIMIT ?"
            }
        }

        val rows = mutableListOf<Map<String, Any?>>()
        var lastPk: Any? = null

        connection.prepareStatement(sql).use { ps ->
            var idx = 1
            // Курсор всегда хранится как строка в JSON, но PostgreSQL (в отличие от MySQL) не
            // приводит типы неявно в сравнении — нужно биндить значение как реальный тип колонки.
            if (lastValue != null) {
                when (pkType) {
                    LogicalType.INTEGER -> ps.setInt(idx++, lastValue.toInt())
                    LogicalType.BIGINT -> ps.setLong(idx++, lastValue.toLong())
                    else -> ps.setString(idx++, lastValue)
                }
            }
            ps.setInt(idx, batchSize)
            val columnsByName = columns.associateBy { it.name }
            ps.executeQuery().use { rs ->
                val meta = rs.metaData
                while (rs.next()) {
                    rows.add(rowToMap(rs, meta, columnsByName))
                    lastPk = rs.getObject(pkColumn)
                }
            }
        }

        val nextCursor = if (rows.size < batchSize || lastPk == null) {
            null
        } else {
            buildJsonObject {
                put("type", JsonPrimitive("primary_key"))
                put("column", JsonPrimitive(pkColumn))
                put("value", JsonPrimitive(lastPk.toString()))
            }
        }

        return BatchResult(rows, nextCursor)
    }

    private fun readBatchByOffset(table: String, columns: List<ColumnDef>, cursor: JsonElement?, batchSize: Int): BatchResult {
        val offset = cursor?.let { (it as? kotlinx.serialization.json.JsonObject)?.get("value")?.jsonPrimitive?.content?.toLong() } ?: 0L
        // Без PK нет естественного столбца для ORDER BY — но MSSQL требует ORDER BY для
        // OFFSET/FETCH синтаксически, поэтому используем заведомо "пустую" сортировку.
        val sql = if (config.type == DbType.SQLSERVER) {
            "SELECT * FROM ${quote(table)} ORDER BY (SELECT NULL) OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"
        } else {
            "SELECT * FROM ${quote(table)} LIMIT ? OFFSET ?"
        }

        val rows = mutableListOf<Map<String, Any?>>()
        connection.prepareStatement(sql).use { ps ->
            if (config.type == DbType.SQLSERVER) {
                ps.setLong(1, offset)
                ps.setInt(2, batchSize)
            } else {
                ps.setInt(1, batchSize)
                ps.setLong(2, offset)
            }
            val columnsByName = columns.associateBy { it.name }
            ps.executeQuery().use { rs ->
                val meta = rs.metaData
                while (rs.next()) rows.add(rowToMap(rs, meta, columnsByName))
            }
        }

        val nextCursor = if (rows.size < batchSize) {
            null
        } else {
            buildJsonObject {
                put("type", JsonPrimitive("offset"))
                put("value", JsonPrimitive(offset + rows.size))
            }
        }

        return BatchResult(rows, nextCursor)
    }

    // MySQL допускает "нулевые" даты ("0000-00-00"/"0000-00-00 00:00:00") в DATE/DATETIME/TIMESTAMP
    // без реального календарного значения. С zeroDateTimeBehavior=CONVERT_TO_NULL (см. JdbcUrl.kt)
    // драйвер отдаёт для них NULL вместо падения при чтении — но если колонка объявлена NOT NULL
    // (частый в legacy-схемах паттерн, где 0000-00-00 использовался как "нет значения" вместо NULL),
    // такой NULL нарушит NOT NULL при вставке на любом target. Раз колонка NOT NULL, любой NULL,
    // прочитанный из неё, ГАРАНТИРОВАННО пришёл именно из такой нулевой даты (иначе БД сама не дала
    // бы её туда записать) — поэтому его безопасно заменить на фиксированный "нет реальной даты"
    // плейсхолдер (эпоха, 1970-01-01), а не потерять всю строку или уронить копирование.
    private val EPOCH_DATE: java.sql.Date = java.sql.Date.valueOf("1970-01-01")
    private val EPOCH_DATETIME: java.sql.Timestamp = java.sql.Timestamp.valueOf("1970-01-01 00:00:00")

    private fun rowToMap(rs: ResultSet, meta: java.sql.ResultSetMetaData, columnsByName: Map<String, ColumnDef>): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        for (i in 1..meta.columnCount) {
            val name = meta.getColumnName(i)
            var value = rs.getObject(i)
            if (value == null && config.type == DbType.MYSQL) {
                val col = columnsByName[name]
                if (col != null && !col.nullable) {
                    value = when (col.type) {
                        LogicalType.DATE -> EPOCH_DATE
                        LogicalType.DATETIME -> EPOCH_DATETIME
                        else -> null
                    }
                }
            }
            map[name] = value
        }
        return map
    }

    private fun schemaClause(alias: String? = null): String {
        val prefix = if (alias != null) "$alias." else ""
        return when (config.type) {
            DbType.MYSQL -> "${prefix}table_schema = '${config.database}'"
            DbType.POSTGRESQL -> "${prefix}table_schema = 'public'"
            DbType.SQLSERVER -> "${prefix}table_schema = 'dbo'"
            else -> "1=1"
        }
    }

    private fun quote(identifier: String): String = when (config.type) {
        DbType.MYSQL -> "`$identifier`"
        DbType.POSTGRESQL, DbType.SQLITE -> "\"$identifier\""
        DbType.SQLSERVER -> "[$identifier]"
    }

    override fun close() {
        if (::connection.isInitialized) connection.close()
    }
}
