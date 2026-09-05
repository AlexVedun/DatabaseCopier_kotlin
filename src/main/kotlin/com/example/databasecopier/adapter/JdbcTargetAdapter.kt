package com.example.databasecopier.adapter

import org.slf4j.LoggerFactory
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.security.MessageDigest

class JdbcTargetAdapter(private val config: ConnectionConfig) : TargetAdapter {

    private val log = LoggerFactory.getLogger(JdbcTargetAdapter::class.java)

    private lateinit var connection: Connection

    override fun connect() {
        log.info("Приёмник {} ({}:{}/{}) — подключаюсь", config.type, config.host, config.port, config.database)
        val start = System.currentTimeMillis()
        try {
            connection = DriverManager.getConnection(buildJdbcUrl(config), config.username, config.password)
            connection.autoCommit = false
            log.info("Приёмник {} ({}:{}/{}) — подключено за {} мс", config.type, config.host, config.port, config.database, System.currentTimeMillis() - start)
        } catch (e: Exception) {
            log.error("Приёмник {} ({}:{}/{}) — подключение не удалось за {} мс: {}", config.type, config.host, config.port, config.database, System.currentTimeMillis() - start, e.message)
            throw e
        }
    }

    override fun createTable(structure: TableStructure) {
        // В SQLite автоинкремент возможен только через "INTEGER PRIMARY KEY AUTOINCREMENT" прямо
        // в определении колонки (rowid alias) — там нельзя одновременно иметь отдельный табличный
        // PRIMARY KEY(...) для этой же колонки, поэтому это единственный особый случай ниже.
        val sqliteAutoIncPk = if (config.type == DbType.SQLITE) {
            structure.primaryKey.singleOrNull()?.let { pk -> structure.columns.firstOrNull { it.name == pk && it.autoIncrement } }
        } else null

        val columnsSql = structure.columns.joinToString(", ") { col ->
            buildColumnSql(col, isSqliteAutoIncPk = col.name == sqliteAutoIncPk?.name)
        }
        val pkSql = if (structure.primaryKey.isNotEmpty() && sqliteAutoIncPk == null) {
            ", PRIMARY KEY (${structure.primaryKey.joinToString(", ") { quote(it) }})"
        } else ""

        // MySQL позволяет задать комментарий таблицы прямо в CREATE TABLE — остальным СУБД
        // (кроме SQLite, где комментарии не поддерживаются) это делается отдельным statement'ом
        // после создания таблицы, см. applyTableAndColumnComments().
        val tableCommentSql = if (config.type == DbType.MYSQL) {
            structure.comment?.let { " COMMENT=${stringLiteral(it)}" } ?: ""
        } else ""

        connection.createStatement().use { stmt ->
            // Если таблица с таким именем уже существует на target — она безусловно удаляется
            // и создаётся заново по структуре источника (решение зафиксировано с пользователем:
            // не пытаться угадывать совместимость существующей схемы, а гарантировать, что
            // структура target всегда точно соответствует source).
            stmt.execute("DROP TABLE IF EXISTS ${quote(structure.name)}")
            stmt.execute("CREATE TABLE ${quote(structure.name)} ($columnsSql$pkSql)$tableCommentSql")
        }
        connection.commit()

        if (config.type == DbType.POSTGRESQL || config.type == DbType.SQLSERVER) {
            applyTableAndColumnComments(structure)
        }
    }

    private fun applyTableAndColumnComments(structure: TableStructure) {
        connection.createStatement().use { stmt ->
            structure.comment?.let { comment ->
                val sql = when (config.type) {
                    DbType.POSTGRESQL -> "COMMENT ON TABLE ${quote(structure.name)} IS ${stringLiteral(comment)}"
                    DbType.SQLSERVER -> "EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N${stringLiteral(comment)}, " +
                        "@level0type=N'SCHEMA', @level0name=N'dbo', @level1type=N'TABLE', @level1name=N'${structure.name.replace("'", "''")}'"
                    else -> return@let
                }
                stmt.execute(sql)
            }
            for (col in structure.columns) {
                val comment = col.comment ?: continue
                val sql = when (config.type) {
                    DbType.POSTGRESQL ->
                        "COMMENT ON COLUMN ${quote(structure.name)}.${quote(col.name)} IS ${stringLiteral(comment)}"
                    DbType.SQLSERVER ->
                        "EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N${stringLiteral(comment)}, " +
                            "@level0type=N'SCHEMA', @level0name=N'dbo', @level1type=N'TABLE', @level1name=N'${structure.name.replace("'", "''")}', " +
                            "@level2type=N'COLUMN', @level2name=N'${col.name.replace("'", "''")}'"
                    else -> continue
                }
                stmt.execute(sql)
            }
        }
        connection.commit()
    }

    private fun stringLiteral(value: String): String = "'${value.replace("'", "''")}'"

    private fun buildColumnSql(col: ColumnDef, isSqliteAutoIncPk: Boolean): String {
        if (isSqliteAutoIncPk) return "${quote(col.name)} INTEGER PRIMARY KEY AUTOINCREMENT"

        val sqlType = TypeMapper.toSqlType(config.type, col.type, col.length)
        // Автоинкремент всегда генерируется нативным механизмом целевой СУБД, а не переносом
        // сырого выражения источника (nextval('seq') в MySQL не имеет смысла и наоборот).
        val autoIncrementSql = if (col.autoIncrement) when (config.type) {
            DbType.MYSQL -> " AUTO_INCREMENT"
            // BY DEFAULT (а не ALWAYS) — копирование вставляет оригинальные PK-значения источника
            // явно, а ALWAYS запрещает это без отдельного OVERRIDING SYSTEM VALUE на каждый INSERT.
            DbType.POSTGRESQL -> " GENERATED BY DEFAULT AS IDENTITY"
            DbType.SQLSERVER -> " IDENTITY(1,1)"
            // Композитный PK с автоинкрементом в SQLite не имеет смысла — тут ничего не добавляем,
            // единственный поддерживаемый случай (одиночный PK) обработан через isSqliteAutoIncPk.
            DbType.SQLITE -> ""
        } else ""
        val nullability = if (col.nullable) "" else " NOT NULL"
        val defaultSql = safeDefaultSql(col.defaultValue, col.type)?.let { " DEFAULT $it" } ?: ""
        val collationSql = safeCollationSql(col.collation)
        // MySQL — единственная СУБД здесь, где комментарий колонки можно (и нужно) задать прямо
        // в CREATE TABLE; для Postgres/MSSQL это отдельный statement после создания таблицы,
        // см. applyTableAndColumnComments().
        val commentSql = if (config.type == DbType.MYSQL) col.comment?.let { " COMMENT ${stringLiteral(it)}" } ?: "" else ""
        return "${quote(col.name)} $sqlType$collationSql$autoIncrementSql$nullability$defaultSql$commentSql"
    }

    // Имя collation одного диалекта почти никогда не валидно в другом (например Postgres
    // "en_US.utf8" против MySQL "utf8mb4_unicode_ci"), поэтому переносим только для MySQL/MSSQL —
    // но раньше проверялось лишь то, что имя из источника — простой идентификатор, без учёта того,
    // что этот идентификатор в принципе может быть из ДРУГОГО диалекта (например MySQL-источник →
    // MSSQL-target буквально копировал "utf8mb4_unicode_ci" в COLLATE, а MSSQL такое название не
    // знает: "Invalid collation 'utf8mb4_unicode_ci'"). JdbcTargetAdapter не знает тип источника,
    // поэтому вместо этого валидируем по форме, характерной именно для target-диалекта: у MySQL
    // имена всегда строчные ("charset_variant_ci/cs/bin"), у MSSQL — всегда заканчиваются на
    // "_CI_AS"/"_CI_AI"/"_CS_AS"/"_CS_AI" (опционально с доп. суффиксами вроде "_SC"/"_UTF8").
    // Postgres/SQLite как target — молча пропускаем, там формат совсем другой.
    private fun safeCollationSql(raw: String?): String {
        if (raw == null) return ""
        val pattern = when (config.type) {
            DbType.MYSQL -> Regex("""^[a-z0-9]+(_[a-z0-9]+)*$""")
            DbType.SQLSERVER -> Regex("""^[A-Za-z0-9]+(_[A-Za-z0-9]+)*_C[IS]_A[IS](_[A-Za-z0-9]+)*$""")
            else -> return ""
        }
        if (!pattern.matches(raw)) return ""
        return " COLLATE $raw"
    }

    // Переносим только простые литералы дефолта (число/строка/NULL/CURRENT_TIMESTAMP) — сложные
    // SQL-выражения источника специфичны для его диалекта, транслятор выражений не пишем
    // (см. Шаг 10 инструкции), такие дефолты молча пропускаются.
    private fun safeDefaultSql(raw: String?, type: LogicalType): String? {
        if (raw == null) return null
        val trimmed = raw.trim()
        // MySQL хранит DEFAULT булевой tinyint(1)-колонки как сырое число ('0'/'1', иногда в
        // кавычках) — оно и остаётся числом в остальных ветках ниже. Postgres не приводит integer
        // к boolean неявно даже в DEFAULT-выражении ("column is of type boolean but default
        // expression is of type integer"), поэтому для BOOLEAN-колонки нормализуем 0/1 в TRUE/FALSE
        // до общих числовых/строковых веток. MySQL/MSSQL/SQLite сами принимают 0/1 как валидный
        // boolean/bit-литерал, так что для них это не нужно.
        if (type == LogicalType.BOOLEAN && config.type == DbType.POSTGRESQL) {
            when (trimmed.trim('\'').lowercase()) {
                "0", "false" -> return "FALSE"
                "1", "true" -> return "TRUE"
            }
        }
        return when {
            trimmed.equals("NULL", ignoreCase = true) -> "NULL"
            trimmed.uppercase().startsWith("CURRENT_TIMESTAMP") -> "CURRENT_TIMESTAMP"
            Regex("""^-?\d+(\.\d+)?$""").matches(trimmed) -> trimmed
            Regex("""^'(?:[^'\\]|\\.)*'$""").matches(trimmed) -> trimmed
            else -> null
        }
    }

    override fun syncAutoIncrement(table: String, column: String, maxValue: Long) {
        val sql = when (config.type) {
            DbType.MYSQL -> "ALTER TABLE ${quote(table)} AUTO_INCREMENT = ${maxValue + 1}"
            DbType.POSTGRESQL ->
                "SELECT setval(pg_get_serial_sequence('${table}', '${column}'), $maxValue)"
            DbType.SQLITE -> {
                connection.createStatement().use { stmt ->
                    val updated = stmt.executeUpdate("UPDATE sqlite_sequence SET seq = $maxValue WHERE name = '$table'")
                    if (updated == 0) {
                        stmt.execute("INSERT INTO sqlite_sequence (name, seq) VALUES ('$table', $maxValue)")
                    }
                }
                connection.commit()
                return
            }
            DbType.SQLSERVER -> "DBCC CHECKIDENT ('${table}', RESEED, $maxValue)"
        }
        connection.createStatement().use { it.execute(sql) }
        connection.commit()
    }

    override fun createView(name: String, definition: String) {
        connection.createStatement().use { stmt ->
            // Тот же принцип, что и для таблиц (Шаг 4): существующая view безусловно
            // пересоздаётся, без попытки проверить совместимость.
            stmt.execute("DROP VIEW IF EXISTS ${quote(name)}")
            stmt.execute("CREATE VIEW ${quote(name)} AS $definition")
        }
        connection.commit()
    }

    override fun createIndexesAndConstraints(structure: TableStructure) {
        connection.createStatement().use { stmt ->
            for (idx in structure.indexes) {
                // FULLTEXT/SPATIAL не поддерживаются вне MySQL/MariaDB в том же синтаксисе (Postgres
                // требует отдельных GIN/GiST-индексов с другим набором операторов, что не является
                // эквивалентным автоматическим переносом) — такой индекс просто пропускается на
                // non-MySQL target, вместо того чтобы падать или тихо создавать бесполезный BTREE.
                if (idx.kind != IndexKind.NORMAL && config.type != DbType.MYSQL) continue
                val keywordSql = when (idx.kind) {
                    IndexKind.FULLTEXT -> "FULLTEXT "
                    IndexKind.SPATIAL -> "SPATIAL "
                    IndexKind.NORMAL -> if (idx.unique) "UNIQUE " else ""
                }
                val cols = idx.columns.joinToString(", ") { quote(it) }
                // Имя переносится как есть (только санация длины, см. safeIdentifier) — по
                // требованию: если два разных объекта источника конфликтуют по имени на target
                // (в Postgres/MSSQL имена индексов схемо-уникальны), это реальная проблема данных
                // источника и должна быть видна пользователю, а не молча замаскирована префиксом.
                val name = safeIdentifier(idx.name)
                executeCreateOrDetectCollision(
                    stmt,
                    "CREATE ${keywordSql}INDEX ${quote(name)} ON ${quote(structure.name)} ($cols)",
                    objectLabel = "Индекс",
                    objectName = name,
                    ownerTable = structure.name,
                    lookupOwner = ::existingIndexOwner,
                )
            }
            // SQLite не поддерживает ALTER TABLE ADD CONSTRAINT CHECK — CHECK там можно задать
            // только в момент CREATE TABLE, которое createTable() (пока) не делает; пропускаем.
            if (config.type != DbType.SQLITE) {
                for (chk in structure.checkConstraints) {
                    createCheckConstraint(stmt, structure.name, chk)
                }
            }
        }
        connection.commit()
    }

    // В отличие от индексов (см. выше — там коллизия имени между разными таблицами считается
    // ошибкой данных источника и явно поднимается), для CHECK-ограничений по решению пользователя
    // при таком конфликте имя детерминированно переименовывается (добавлением имени таблицы), а не
    // ронять сессию — это частый паттерн для ORM (например Doctrine), где имя CHECK генерируется
    // из общего трейта/интерфейса нескольких сущностей и потому случайно совпадает между таблицами.
    private fun createCheckConstraint(stmt: Statement, table: String, chk: CheckConstraintDef) {
        var name = safeIdentifier(chk.name)
        var attempt = 0
        while (true) {
            val savepoint = if (config.type == DbType.POSTGRESQL) connection.setSavepoint() else null
            try {
                stmt.execute("ALTER TABLE ${quote(table)} ADD CONSTRAINT ${quote(name)} CHECK (${chk.expression})")
                if (savepoint != null) connection.releaseSavepoint(savepoint)
                return
            } catch (e: SQLException) {
                if (savepoint != null) connection.rollback(savepoint)
                if (!looksLikeDuplicate(e)) throw e

                val existingOwner = existingCheckConstraintOwner(name)
                if (existingOwner != null && existingOwner.equals(table, ignoreCase = true)) {
                    return // тот же constraint той же таблицы — безопасный повторный запуск, не ошибка
                }

                attempt++
                if (attempt > 5) {
                    throw IllegalStateException(
                        "Не удалось создать CHECK-ограничение '${chk.name}' на таблице '$table' — имя " +
                            "конфликтует с объектами других таблиц даже после переименования " +
                            "(последняя попытка: '$name').",
                        e,
                    )
                }
                // Детерминированное переименование: имя_таблица, при повторной коллизии — со счётчиком.
                name = safeIdentifier(if (attempt == 1) "${chk.name}_$table" else "${chk.name}_${table}_$attempt")
            }
        }
    }

    private fun existingIndexOwner(name: String): String? {
        val sql = when (config.type) {
            DbType.MYSQL -> "SELECT table_name FROM information_schema.statistics WHERE index_name = ? AND table_schema = database() LIMIT 1"
            DbType.POSTGRESQL ->
                "SELECT tc.relname AS table_name FROM pg_class ic " +
                    "JOIN pg_index ix ON ix.indexrelid = ic.oid JOIN pg_class tc ON tc.oid = ix.indrelid WHERE ic.relname = ?"
            DbType.SQLSERVER -> "SELECT t.name AS table_name FROM sys.indexes i JOIN sys.tables t ON t.object_id = i.object_id WHERE i.name = ?"
            DbType.SQLITE -> "SELECT tbl_name AS table_name FROM sqlite_master WHERE type = 'index' AND name = ?"
        }
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getString("table_name") else null }
        }
    }

    private fun existingCheckConstraintOwner(name: String): String? {
        val sql = when (config.type) {
            DbType.MYSQL, DbType.POSTGRESQL ->
                "SELECT table_name FROM information_schema.table_constraints WHERE constraint_name = ? AND ${schemaClause()}"
            DbType.SQLSERVER ->
                "SELECT t.name AS table_name FROM sys.check_constraints cc JOIN sys.tables t ON cc.parent_object_id = t.object_id WHERE cc.name = ?"
            DbType.SQLITE -> return null // CHECK на SQLite не создаются вообще, сюда не дойдёт
        }
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, name)
            ps.executeQuery().use { rs -> return if (rs.next()) rs.getString("table_name") else null }
        }
    }

    override fun createForeignKeys(table: String, foreignKeys: List<ForeignKeyRef>) {
        // SQLite не поддерживает ADD CONSTRAINT FOREIGN KEY через ALTER TABLE — FK там можно
        // задать только в момент CREATE TABLE. Осознанно пропускаем без ошибки: остальные
        // СУБД получают полноценные FK-constraint'ы, для SQLite это известное ограничение.
        if (foreignKeys.isEmpty() || config.type == DbType.SQLITE) return
        connection.createStatement().use { stmt ->
            for ((idx, fk) in foreignKeys.withIndex()) {
                val constraintName = safeIdentifier("fk_${table}_${fk.columnName}_$idx")
                val sql = "ALTER TABLE ${quote(table)} ADD CONSTRAINT ${quote(constraintName)} " +
                    "FOREIGN KEY (${quote(fk.columnName)}) REFERENCES ${quote(fk.referencedTable)} (${quote(fk.referencedColumn)}) " +
                    "ON DELETE ${actionSql(fk.onDelete)} ON UPDATE ${actionSql(fk.onUpdate)}"
                executeIgnoringDuplicate(stmt, sql)
            }
        }
        connection.commit()
    }

    // createIndexesAndConstraints()/createForeignKeys() помечаются "скопировано" только по успеху
    // ВСЕГО набора (indexesCopied/foreignKeysCopied) — если один из объектов на середине списка
    // упал, повторный запуск выполняет всю функцию заново, включая уже успешно созданные объекты
    // и ловит "уже существует" на них. Это ожидаемый повторный запуск, а не ошибка — но раз имена
    // индексов/CHECK теперь переносятся как есть (без префикса таблицей), нужно отличать его от
    // настоящего конфликта: два РАЗНЫХ объекта источника (из разных таблиц) с одинаковым именем.
    // Различаем через lookupOwner: если существующий на target объект принадлежит ТОЙ ЖЕ таблице —
    // это наш же повтор, пропускаем; если другой — это ошибка данных источника, поднимаем явно.
    private fun executeCreateOrDetectCollision(
        stmt: Statement,
        sql: String,
        objectLabel: String,
        objectName: String,
        ownerTable: String,
        lookupOwner: (String) -> String?,
    ) {
        val savepoint = if (config.type == DbType.POSTGRESQL) connection.setSavepoint() else null
        try {
            stmt.execute(sql)
            if (savepoint != null) connection.releaseSavepoint(savepoint)
        } catch (e: SQLException) {
            if (savepoint != null) connection.rollback(savepoint)
            if (!looksLikeDuplicate(e)) throw e

            val existingOwner = lookupOwner(objectName)
            if (existingOwner != null && existingOwner.equals(ownerTable, ignoreCase = true)) {
                return // тот же объект той же таблицы — это наш же повторный запуск, а не ошибка
            }
            throw IllegalStateException(
                "$objectLabel с именем '$objectName' уже существует на target" +
                    (existingOwner?.let { " (принадлежит таблице '$it')" } ?: "") +
                    ", а копируемая таблица — '$ownerTable'. В исходной БД есть два разных объекта " +
                    "с одинаковым именем '$objectName' — переименуйте один из них в источнике.",
                e,
            )
        }
    }

    // FK-имя всегда синтезируется как "fk_<table>_<column>_<index>" (у ForeignKeyRef нет
    // исходного имени constraint'а — см. Шаг 9) и потому уже детерминированно уникально между
    // разными таблицами; конфликт по такому имени может возникнуть только при повторном запуске
    // для ТОЙ ЖЕ таблицы, so owner-проверка здесь не нужна.
    private fun executeIgnoringDuplicate(stmt: Statement, sql: String) {
        val savepoint = if (config.type == DbType.POSTGRESQL) connection.setSavepoint() else null
        try {
            stmt.execute(sql)
            if (savepoint != null) connection.releaseSavepoint(savepoint)
        } catch (e: SQLException) {
            if (savepoint != null) connection.rollback(savepoint)
            if (!looksLikeDuplicate(e)) throw e
        }
    }

    // В Postgres (в отличие от MySQL/SQLite/MSSQL) ЛЮБАЯ ошибка внутри транзакции "отравляет" всю
    // транзакцию — все следующие statement'ы на этом же соединении падают с "current transaction
    // is aborted" до явного ROLLBACK. SAVEPOINT перед каждым statement'ом (см. вызовы выше) даёт
    // откатиться только до него; для MySQL/SQLite/MSSQL savepoint не используется — MySQL делает
    // implicit commit на каждом DDL, который сам уничтожает savepoint раньше releaseSavepoint().
    private fun looksLikeDuplicate(e: SQLException): Boolean {
        val msg = e.message?.lowercase() ?: ""
        return "duplicate" in msg || "already exists" in msg || "there is already an object" in msg
    }

    private fun actionSql(action: ReferentialAction): String = when (action) {
        ReferentialAction.CASCADE -> "CASCADE"
        ReferentialAction.SET_NULL -> "SET NULL"
        ReferentialAction.RESTRICT -> "RESTRICT"
        ReferentialAction.SET_DEFAULT -> "SET DEFAULT"
        ReferentialAction.NO_ACTION -> "NO ACTION"
    }

    override fun insertBatch(table: String, rows: List<Map<String, Any?>>) {
        if (rows.isEmpty()) return
        val columns = rows.first().keys.toList()
        val placeholders = columns.joinToString(", ") { "?" }
        val columnsSql = columns.joinToString(", ") { quote(it) }
        val sql = "INSERT INTO ${quote(table)} ($columnsSql) VALUES ($placeholders)"

        // MSSQL по умолчанию запрещает явно вставлять значения в IDENTITY-колонку — а копирование
        // всегда переносит оригинальные PK-значения источника, поэтому для таких таблиц вставку
        // нужно обрамить SET IDENTITY_INSERT ON/OFF (в отличие от MySQL AUTO_INCREMENT/Postgres
        // GENERATED BY DEFAULT/SQLite AUTOINCREMENT, которые разрешают явные значения без этого).
        val needsIdentityInsert = config.type == DbType.SQLSERVER && hasIdentityColumn(table)
        if (needsIdentityInsert) connection.createStatement().use { it.execute("SET IDENTITY_INSERT ${quote(table)} ON") }
        try {
            connection.prepareStatement(sql).use { ps ->
                for (row in rows) {
                    columns.forEachIndexed { idx, col -> ps.setObject(idx + 1, row[col]) }
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        } finally {
            if (needsIdentityInsert) connection.createStatement().use { it.execute("SET IDENTITY_INSERT ${quote(table)} OFF") }
        }
        connection.commit()
    }

    private fun hasIdentityColumn(table: String): Boolean {
        connection.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT 1 FROM sys.identity_columns c JOIN sys.tables t ON c.object_id = t.object_id " +
                    "WHERE t.name = '${table.replace("'", "''")}'"
            ).use { rs -> return rs.next() }
        }
    }

    override fun tableExists(table: String): Boolean {
        val sql = if (config.type == DbType.SQLITE) {
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?"
        } else {
            "SELECT table_name FROM information_schema.tables WHERE table_name = ? AND ${schemaClause()}"
        }
        connection.prepareStatement(sql).use { ps ->
            ps.setString(1, table)
            ps.executeQuery().use { rs -> return rs.next() }
        }
    }

    override fun disableForeignKeyChecks() {
        when (config.type) {
            DbType.MYSQL -> connection.createStatement().use { it.execute("SET FOREIGN_KEY_CHECKS=0") }
            DbType.POSTGRESQL -> connection.createStatement().use { it.execute("SET session_replication_role = 'replica'") }
            DbType.SQLITE -> connection.createStatement().use { it.execute("PRAGMA foreign_keys = OFF") }
            // У MSSQL нет сессионного тумблера — переключается по каждой таблице отдельно.
            DbType.SQLSERVER -> forEachTable { fullName ->
                connection.createStatement().use { it.execute("ALTER TABLE $fullName NOCHECK CONSTRAINT ALL") }
            }
        }
        connection.commit()
    }

    override fun enableForeignKeyChecks() {
        when (config.type) {
            DbType.MYSQL -> connection.createStatement().use { it.execute("SET FOREIGN_KEY_CHECKS=1") }
            DbType.POSTGRESQL -> connection.createStatement().use { it.execute("SET session_replication_role = 'origin'") }
            DbType.SQLITE -> connection.createStatement().use { it.execute("PRAGMA foreign_keys = ON") }
            // CHECK CONSTRAINT ALL (без WITH CHECK) включает проверку для новых DML, не
            // перепроверяя уже вставленные строки — то же поведение, что и у остальных СУБД здесь.
            DbType.SQLSERVER -> forEachTable { fullName ->
                connection.createStatement().use { it.execute("ALTER TABLE $fullName CHECK CONSTRAINT ALL") }
            }
        }
        connection.commit()
    }

    private fun forEachTable(action: (String) -> Unit) {
        val names = mutableListOf<String>()
        connection.createStatement().use { stmt ->
            stmt.executeQuery(
                "SELECT s.name AS schema_name, t.name AS table_name " +
                    "FROM sys.tables t JOIN sys.schemas s ON t.schema_id = s.schema_id"
            ).use { rs ->
                while (rs.next()) names.add("[${rs.getString("schema_name")}].[${rs.getString("table_name")}]")
            }
        }
        names.forEach(action)
    }

    private fun schemaClause(): String = when (config.type) {
        DbType.MYSQL -> "table_schema = '${config.database}'"
        DbType.POSTGRESQL -> "table_schema = 'public'"
        DbType.SQLSERVER -> "table_schema = 'dbo'"
        else -> "1=1"
    }

    // Сгенерированные имена (index/constraint) складываются из имени таблицы + имени колонки
    // источника и легко превышают лимит длины идентификатора СУБД (MySQL — 64, Postgres — 63,
    // MSSQL — 128), особенно на длинных исходных именах — это реально ронялось на практике с
    // ошибкой "Identifier name '...' is too long". При превышении обрезаем и добавляем короткий
    // хеш от полного имени, чтобы не потерять уникальность разных длинных имён с общим префиксом.
    private fun safeIdentifier(raw: String): String {
        val maxLength = when (config.type) {
            DbType.MYSQL -> 64
            DbType.POSTGRESQL -> 63
            DbType.SQLSERVER -> 128
            DbType.SQLITE -> Int.MAX_VALUE
        }
        if (raw.length <= maxLength) return raw
        val hash = MessageDigest.getInstance("MD5").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }.take(8)
        val prefixLength = (maxLength - hash.length - 1).coerceAtLeast(0)
        return "${raw.take(prefixLength)}_$hash"
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
