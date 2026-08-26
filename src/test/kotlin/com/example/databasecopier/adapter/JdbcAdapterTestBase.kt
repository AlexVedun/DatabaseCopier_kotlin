package com.example.databasecopier.adapter

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Общие интеграционные тесты для JdbcSourceAdapter/JdbcTargetAdapter, запускаются против
 * реальной MySQL и PostgreSQL (через Testcontainers) в конкретных наследниках.
 */
abstract class JdbcAdapterTestBase {

    abstract fun config(): ConnectionConfig
    abstract fun rawJdbcUrl(): String

    private lateinit var rawConnection: Connection
    private lateinit var source: JdbcSourceAdapter
    private lateinit var target: JdbcTargetAdapter

    @BeforeEach
    fun setUp() {
        val cfg = config()
        rawConnection = DriverManager.getConnection(rawJdbcUrl(), cfg.username, cfg.password)
        rawConnection.createStatement().use { stmt ->
            stmt.execute("DROP TABLE IF EXISTS orders")
            stmt.execute("DROP TABLE IF EXISTS customers")
            stmt.execute(
                "CREATE TABLE customers (id INTEGER PRIMARY KEY, name VARCHAR(255) NOT NULL)"
            )
            stmt.execute(
                "CREATE TABLE orders (id INTEGER PRIMARY KEY, customer_id INTEGER NOT NULL, " +
                    "amount INTEGER NOT NULL, FOREIGN KEY (customer_id) REFERENCES customers(id))"
            )
            for (i in 1..5) {
                stmt.execute("INSERT INTO customers (id, name) VALUES ($i, 'Customer $i')")
            }
            for (i in 1..25) {
                stmt.execute("INSERT INTO orders (id, customer_id, amount) VALUES ($i, ${(i % 5) + 1}, ${i * 10})")
            }
        }

        source = JdbcSourceAdapter(cfg)
        source.connect()
        target = JdbcTargetAdapter(cfg)
        target.connect()
    }

    @AfterEach
    fun tearDown() {
        source.close()
        target.close()
        rawConnection.createStatement().use { stmt ->
            stmt.execute("DROP TABLE IF EXISTS orders")
            stmt.execute("DROP TABLE IF EXISTS customers")
        }
        rawConnection.close()
    }

    @Test
    fun `lists tables with row counts`() {
        val tables = source.listTables()
        assertEquals(25L, tables["orders"])
        assertEquals(5L, tables["customers"])
    }

    @Test
    fun `reads table structure with primary key`() {
        val structure = source.getTableStructure("orders")
        assertEquals(listOf("id"), structure.primaryKey)
        assertTrue(structure.columns.any { it.name == "customer_id" && it.type == LogicalType.INTEGER })
    }

    @Test
    fun `reads foreign keys`() {
        val fks = source.getForeignKeys("orders")
        assertEquals(1, fks.size)
        assertEquals("customer_id", fks[0].columnName)
        assertEquals("customers", fks[0].referencedTable)
        assertEquals("id", fks[0].referencedColumn)
        // Фикстура не задаёт ON DELETE/ON UPDATE явно — все СУБД по умолчанию считают это NO ACTION.
        assertEquals(ReferentialAction.NO_ACTION, fks[0].onDelete)
        assertEquals(ReferentialAction.NO_ACTION, fks[0].onUpdate)
    }

    @Test
    fun `paginates through all rows without duplicates using primary key cursor`() {
        val allRows = mutableListOf<Map<String, Any?>>()
        var cursor: kotlinx.serialization.json.JsonElement? = null
        do {
            val batch = source.readBatch("orders", cursor, batchSize = 7)
            allRows.addAll(batch.rows)
            cursor = batch.nextCursor
        } while (cursor != null)

        assertEquals(25, allRows.size)
        assertEquals((1..25).toSet(), allRows.map { (it["id"] as Number).toInt() }.toSet())
    }

    @Test
    fun `resumes from a saved cursor without re-reading earlier rows`() {
        val firstBatch = source.readBatch("orders", null, batchSize = 10)
        assertEquals(10, firstBatch.rows.size)

        val resumed = mutableListOf<Map<String, Any?>>()
        var cursor = firstBatch.nextCursor
        do {
            val batch = source.readBatch("orders", cursor, batchSize = 10)
            resumed.addAll(batch.rows)
            cursor = batch.nextCursor
        } while (cursor != null)

        assertEquals(15, resumed.size)
        val resumedIds = resumed.map { (it["id"] as Number).toInt() }.toSet()
        val firstIds = firstBatch.rows.map { (it["id"] as Number).toInt() }.toSet()
        assertTrue(resumedIds.intersect(firstIds).isEmpty())
    }

    @Test
    fun `creates table and inserts batch on target`() {
        val structure = TableStructure(
            name = "copy_target",
            columns = listOf(
                ColumnDef("id", LogicalType.INTEGER, nullable = false),
                ColumnDef("label", LogicalType.VARCHAR, nullable = true),
            ),
            primaryKey = listOf("id"),
        )
        assertFalse(target.tableExists("copy_target"))
        target.createTable(structure)
        assertTrue(target.tableExists("copy_target"))

        target.insertBatch(
            "copy_target",
            listOf(mapOf("id" to 1, "label" to "a"), mapOf("id" to 2, "label" to "b")),
        )

        rawConnection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM copy_target").use { rs ->
                rs.next()
                assertEquals(2, rs.getInt(1))
            }
            stmt.execute("DROP TABLE copy_target")
        }
    }

    @Test
    fun `recreates an existing target table dropping its old structure and data`() {
        rawConnection.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE copy_target (old_col VARCHAR(50))")
            stmt.execute("INSERT INTO copy_target (old_col) VALUES ('stale')")
        }

        val newStructure = TableStructure(
            name = "copy_target",
            columns = listOf(ColumnDef("id", LogicalType.INTEGER, nullable = false)),
            primaryKey = listOf("id"),
        )
        target.createTable(newStructure)
        target.insertBatch("copy_target", listOf(mapOf("id" to 1)))

        rawConnection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM copy_target").use { rs ->
                rs.next()
                assertEquals(1, rs.getInt(1))
            }
            stmt.execute("DROP TABLE copy_target")
        }
    }

    @Test
    fun `reads unique index from source and enforces it on target after copy`() {
        rawConnection.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE idx_src (id INTEGER PRIMARY KEY, email VARCHAR(100))")
            stmt.execute("CREATE UNIQUE INDEX idx_src_email ON idx_src (email)")
            stmt.execute("INSERT INTO idx_src (id, email) VALUES (1, 'a@example.com')")
        }

        val structure = source.getTableStructure("idx_src")
        assertEquals(1, structure.indexes.size)
        assertTrue(structure.indexes[0].unique)
        assertEquals(listOf("email"), structure.indexes[0].columns)

        // Индекс переносится под тем же именем, что и у источника (без префикса таблицей) — раз
        // source/target здесь физически одна и та же БД (только в этом тесте, не в реальном
        // использовании), исходную таблицу нужно убрать до создания одноимённого индекса на
        // target, иначе имя будет занято собственным индексом idx_src, что и есть настоящая
        // коллизия имён между двумя разными таблицами одной БД.
        rawConnection.createStatement().use { stmt -> stmt.execute("DROP TABLE idx_src") }

        target.createTable(structure.copy(name = "idx_target"))
        target.insertBatch("idx_target", listOf(mapOf("id" to 1, "email" to "a@example.com")))
        target.createIndexesAndConstraints(structure.copy(name = "idx_target"))

        val duplicateInsertFails = try {
            rawConnection.createStatement().use { stmt ->
                stmt.execute("INSERT INTO idx_target (id, email) VALUES (2, 'a@example.com')")
            }
            false
        } catch (e: Exception) {
            true
        }
        assertTrue(duplicateInsertFails, "unique index должен запретить вставку дубликата email")

        rawConnection.createStatement().use { stmt -> stmt.execute("DROP TABLE idx_target") }
    }

    @Test
    fun `raises a clear error when two different tables have an index with the same name`() {
        // Индексы схемо-уникальны в Postgres — коллизия имени между РАЗНЫМИ таблицами структурно
        // возможна только там (MySQL/MSSQL скопируют оба индекса без конфликта, т.к. там имя
        // индекса уникально в рамках одной таблицы; SQLite тоже уникально в рамках БД, но
        // множественные конкурентные соединения к одному файлу в этом тесте дают нестабильный
        // "database is locked" — не стоит того ради дублирующей проверки той же семантики).
        assumeTrue(config().type == DbType.POSTGRESQL)

        target.createTable(TableStructure("clash_a", listOf(ColumnDef("id", LogicalType.INTEGER, false)), listOf("id")))
        target.createTable(TableStructure("clash_b", listOf(ColumnDef("id", LogicalType.INTEGER, false)), listOf("id")))

        val indexOnA = TableStructure(
            "clash_a",
            listOf(ColumnDef("id", LogicalType.INTEGER, false)),
            listOf("id"),
            indexes = listOf(IndexDef("shared_idx_name", listOf("id"), unique = false)),
        )
        val indexOnB = indexOnA.copy(name = "clash_b")

        target.createIndexesAndConstraints(indexOnA)
        val ex = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException::class.java) {
            target.createIndexesAndConstraints(indexOnB)
        }
        assertTrue(ex.message?.contains("shared_idx_name") == true, "error should name the conflicting object: ${ex.message}")
        assertTrue(ex.message?.contains("clash_a") == true, "error should name the table that already owns it: ${ex.message}")

        rawConnection.createStatement().use { stmt ->
            stmt.execute("DROP TABLE clash_a")
            stmt.execute("DROP TABLE clash_b")
        }
    }

    @Test
    fun `calling createIndexesAndConstraints twice for the same table is a safe no-op retry`() {
        target.createTable(TableStructure("retry_a", listOf(ColumnDef("id", LogicalType.INTEGER, false)), listOf("id")))
        val structure = TableStructure(
            "retry_a",
            listOf(ColumnDef("id", LogicalType.INTEGER, false)),
            listOf("id"),
            indexes = listOf(IndexDef("retry_idx_name", listOf("id"), unique = false)),
        )

        target.createIndexesAndConstraints(structure)
        // Тот же вызов для той же таблицы — эмуляция повторного запуска после падения где-то
        // дальше в сессии — не должен бросить исключение.
        target.createIndexesAndConstraints(structure)

        rawConnection.createStatement().use { stmt -> stmt.execute("DROP TABLE retry_a") }
    }

    @Test
    fun `calling createIndexesAndConstraints twice does not fail on already-existing objects`() {
        // indexesCopied выставляется только по успеху ВСЕГО набора индексов/CHECK для таблицы —
        // если сессия падает ПОСЛЕ частичного создания (например, на другой таблице/шаге) и потом
        // перезапускается, эта функция вызывается заново с теми же объектами. Раньше это падало с
        // "Duplicate key name"/"Duplicate CHECK constraint name" вместо того, чтобы просто
        // пропустить уже существующие индекс/ограничение.
        rawConnection.createStatement().use { stmt ->
            stmt.execute(
                "CREATE TABLE idx_retry (id INTEGER PRIMARY KEY, email VARCHAR(100), amount INTEGER CHECK (amount > 0))"
            )
            stmt.execute("CREATE UNIQUE INDEX idx_retry_email ON idx_retry (email)")
        }

        val structure = source.getTableStructure("idx_retry")
        // См. комментарий в тесте про индексы выше — исходную таблицу нужно убрать перед
        // созданием одноимённых индекса/CHECK на target (иначе это настоящая коллизия имён).
        rawConnection.createStatement().use { stmt -> stmt.execute("DROP TABLE idx_retry") }
        target.createTable(structure.copy(name = "idx_retry_target"))

        target.createIndexesAndConstraints(structure.copy(name = "idx_retry_target"))
        // Повторный вызов не должен бросить исключение.
        target.createIndexesAndConstraints(structure.copy(name = "idx_retry_target"))

        rawConnection.createStatement().use { stmt -> stmt.execute("DROP TABLE idx_retry_target") }
    }

    @Test
    fun `truncates a generated index name that would exceed the target identifier length limit`() {
        // Имя индекса переносится как есть (см. `надо копировать имена индексов как есть`), но
        // само по себе всё равно может превышать лимит идентификатора СУБД (64 у MySQL, 63 у
        // Postgres) — это уже физическое ограничение целевой БД, а не решение о префиксации.
        // Ровно 64 символа: превышает лимит Postgres (63, там сработает truncation), но всё ещё
        // помещается в лимит MySQL (64) на источнике — иначе исходную таблицу не создать вообще.
        val longIndexName = "i".repeat(64)
        rawConnection.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE idx_long (id INTEGER PRIMARY KEY, email VARCHAR(100))")
            stmt.execute("CREATE INDEX $longIndexName ON idx_long (email)")
        }

        val structure = source.getTableStructure("idx_long")
        assertEquals(1, structure.indexes.size)

        // См. комментарий в тесте про индексы выше — исходную таблицу нужно убрать перед
        // созданием одноимённого индекса на target (иначе это настоящая коллизия имён).
        rawConnection.createStatement().use { stmt -> stmt.execute("DROP TABLE idx_long") }

        target.createTable(structure.copy(name = "idx_long_target"))
        target.createIndexesAndConstraints(structure.copy(name = "idx_long_target"))

        rawConnection.createStatement().use { stmt -> stmt.execute("DROP TABLE idx_long_target") }
    }

    @Test
    fun `reads CHECK constraint from source and enforces it on target after copy`() {
        // SQLite не поддерживает ALTER TABLE ADD CONSTRAINT CHECK — createIndexesAndConstraints()
        // там осознанно пропускает CHECK (см. JdbcTargetAdapter), проверять здесь нечего.
        assumeTrue(config().type != DbType.SQLITE)

        rawConnection.createStatement().use { stmt ->
            stmt.execute("CREATE TABLE chk_src (id INTEGER PRIMARY KEY, amount INTEGER CHECK (amount > 0))")
            stmt.execute("INSERT INTO chk_src (id, amount) VALUES (1, 10)")
        }

        val structure = source.getTableStructure("chk_src")
        assertEquals(1, structure.checkConstraints.size)
        assertTrue(structure.checkConstraints[0].expression.contains("amount"))

        // См. комментарий в тесте про индексы выше — source/target здесь одна и та же БД только
        // в рамках теста, исходную таблицу нужно убрать перед созданием одноимённого CHECK.
        rawConnection.createStatement().use { stmt -> stmt.execute("DROP TABLE chk_src") }

        target.createTable(structure.copy(name = "chk_target"))
        target.insertBatch("chk_target", listOf(mapOf("id" to 1, "amount" to 10)))
        target.createIndexesAndConstraints(structure.copy(name = "chk_target"))

        val invalidInsertFails = try {
            rawConnection.createStatement().use { stmt ->
                stmt.execute("INSERT INTO chk_target (id, amount) VALUES (2, -5)")
            }
            false
        } catch (e: Exception) {
            true
        }
        assertTrue(invalidInsertFails, "CHECK-ограничение должно запретить вставку amount <= 0")

        rawConnection.createStatement().use { stmt -> stmt.execute("DROP TABLE chk_target") }
    }

    @Test
    fun `reads table and column comments, then recreates them on target`() {
        // SQLite не поддерживает комментарии таблиц/колонок — ни на чтение, ни на запись.
        assumeTrue(config().type != DbType.SQLITE)

        when (config().type) {
            DbType.MYSQL -> rawConnection.createStatement().use { stmt ->
                stmt.execute(
                    "CREATE TABLE cmt_src (id INTEGER PRIMARY KEY, note VARCHAR(50) COMMENT 'col comment') " +
                        "COMMENT='table comment'"
                )
            }
            DbType.POSTGRESQL -> rawConnection.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE cmt_src (id INTEGER PRIMARY KEY, note VARCHAR(50))")
                stmt.execute("COMMENT ON TABLE cmt_src IS 'table comment'")
                stmt.execute("COMMENT ON COLUMN cmt_src.note IS 'col comment'")
            }
            DbType.SQLSERVER -> rawConnection.createStatement().use { stmt ->
                stmt.execute("CREATE TABLE cmt_src (id INTEGER PRIMARY KEY, note NVARCHAR(50))")
                stmt.execute(
                    "EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'table comment', " +
                        "@level0type=N'SCHEMA', @level0name=N'dbo', @level1type=N'TABLE', @level1name=N'cmt_src'"
                )
                stmt.execute(
                    "EXEC sys.sp_addextendedproperty @name=N'MS_Description', @value=N'col comment', " +
                        "@level0type=N'SCHEMA', @level0name=N'dbo', @level1type=N'TABLE', @level1name=N'cmt_src', " +
                        "@level2type=N'COLUMN', @level2name=N'note'"
                )
            }
            DbType.SQLITE -> Unit
        }

        val structure = source.getTableStructure("cmt_src")
        assertEquals("table comment", structure.comment)
        assertEquals("col comment", structure.columns.first { it.name == "note" }.comment)

        target.createTable(structure.copy(name = "cmt_target"))

        // Читаем обратно через тот же JdbcSourceAdapter (другой инстанс), чтобы убедиться, что
        // комментарий реально записан в каталог target, а не просто не упал молча.
        val readBack = JdbcSourceAdapter(config()).apply { connect() }
        try {
            val targetStructure = readBack.getTableStructure("cmt_target")
            assertEquals("table comment", targetStructure.comment)
            assertEquals("col comment", targetStructure.columns.first { it.name == "note" }.comment)
        } finally {
            readBack.close()
        }

        rawConnection.createStatement().use { stmt ->
            stmt.execute("DROP TABLE cmt_target")
            stmt.execute("DROP TABLE cmt_src")
        }
    }

    @Test
    fun `creates foreign key constraint with referential action after both tables exist`() {
        // SQLite не поддерживает ALTER TABLE ADD CONSTRAINT FOREIGN KEY — createForeignKeys()
        // там осознанно no-op (см. JdbcTargetAdapter), проверять здесь нечего.
        assumeTrue(config().type != DbType.SQLITE)

        target.createTable(
            TableStructure("t_customers", listOf(ColumnDef("id", LogicalType.INTEGER, nullable = false)), listOf("id"))
        )
        target.createTable(
            TableStructure(
                "t_orders",
                listOf(
                    ColumnDef("id", LogicalType.INTEGER, nullable = false),
                    ColumnDef("customer_id", LogicalType.INTEGER, nullable = true),
                ),
                listOf("id"),
            )
        )
        target.insertBatch("t_customers", listOf(mapOf("id" to 1)))
        target.insertBatch("t_orders", listOf(mapOf("id" to 100, "customer_id" to 1)))

        target.createForeignKeys(
            "t_orders",
            listOf(ForeignKeyRef("customer_id", "t_customers", "id", onDelete = ReferentialAction.CASCADE))
        )
        // Повторный вызов (эмуляция retry после падения на другом шаге сессии) не должен упасть
        // с "Duplicate foreign key constraint name" — конфликт "уже существует" здесь ожидаем.
        target.createForeignKeys(
            "t_orders",
            listOf(ForeignKeyRef("customer_id", "t_customers", "id", onDelete = ReferentialAction.CASCADE))
        )

        // Реальное поведение ON DELETE CASCADE доказывает, что constraint создался, а не просто
        // не упал молча: удаление родителя должно каскадно удалить зависимую строку.
        rawConnection.createStatement().use { stmt -> stmt.execute("DELETE FROM t_customers WHERE id = 1") }
        rawConnection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT COUNT(*) FROM t_orders").use { rs ->
                rs.next()
                assertEquals(0, rs.getInt(1))
            }
        }

        rawConnection.createStatement().use { stmt ->
            stmt.execute("DROP TABLE t_orders")
            stmt.execute("DROP TABLE t_customers")
        }
    }

    @Test
    fun `reads auto-increment and default value, then recreates natively on target with synced counter`() {
        val ddl = when (config().type) {
            DbType.MYSQL -> "CREATE TABLE auto_src (id INTEGER PRIMARY KEY AUTO_INCREMENT, note VARCHAR(50) DEFAULT 'hi')"
            DbType.POSTGRESQL -> "CREATE TABLE auto_src (id SERIAL PRIMARY KEY, note VARCHAR(50) DEFAULT 'hi')"
            DbType.SQLITE -> "CREATE TABLE auto_src (id INTEGER PRIMARY KEY AUTOINCREMENT, note TEXT DEFAULT 'hi')"
            DbType.SQLSERVER -> "CREATE TABLE auto_src (id INT IDENTITY(1,1) PRIMARY KEY, note NVARCHAR(50) DEFAULT 'hi')"
        }
        rawConnection.createStatement().use { stmt ->
            stmt.execute(ddl)
            stmt.execute("INSERT INTO auto_src (note) VALUES ('a')")
            stmt.execute("INSERT INTO auto_src (note) VALUES ('b')")
            stmt.execute("INSERT INTO auto_src (note) VALUES ('c')")
        }

        val structure = source.getTableStructure("auto_src")
        val idCol = structure.columns.first { it.name == "id" }
        val noteCol = structure.columns.first { it.name == "note" }
        assertTrue(idCol.autoIncrement)
        assertEquals(null, idCol.defaultValue)
        assertFalse(noteCol.autoIncrement)
        assertTrue(noteCol.defaultValue?.contains("hi") == true)

        // Пересоздаём структуру на target (та же СУБД, чтобы не смешивать с кросс-диалектным
        // маппингом типов — это отдельно проверяется тестами TypeMapper) и вручную "копируем"
        // существующие строки, как это делает CopyRunner.
        target.createTable(structure.copy(name = "auto_target"))
        target.insertBatch(
            "auto_target",
            listOf(mapOf("id" to 1, "note" to "x"), mapOf("id" to 2, "note" to "y"), mapOf("id" to 3, "note" to "z")),
        )
        target.syncAutoIncrement("auto_target", "id", 3)

        // Новая строка без явного id должна продолжить нумерацию с 4, а не конфликтовать с уже
        // скопированными — это и доказывает, что счётчик автоинкремента реально синхронизирован.
        rawConnection.createStatement().use { stmt -> stmt.execute("INSERT INTO auto_target (note) VALUES ('new')") }
        rawConnection.createStatement().use { stmt ->
            stmt.executeQuery("SELECT id FROM auto_target WHERE note = 'new'").use { rs ->
                rs.next()
                assertEquals(4, rs.getInt("id"))
            }
        }

        rawConnection.createStatement().use { stmt ->
            stmt.execute("DROP TABLE auto_target")
            stmt.execute("DROP TABLE auto_src")
        }
    }

    @Test
    fun `allows inserting a row with a dangling foreign key while checks are disabled`() {
        // FOREIGN_KEY_CHECKS/session_replication_role — настройки уровня сессии, поэтому и
        // отключение, и сама вставка обязаны идти через одно и то же JDBC-соединение (target).
        target.disableForeignKeyChecks()
        try {
            // customer_id = 999 не существует в customers — с включёнными проверками это упало бы
            target.insertBatch("orders", listOf(mapOf("id" to 999, "customer_id" to 999, "amount" to 100)))
        } finally {
            target.enableForeignKeyChecks()
            rawConnection.createStatement().use { stmt -> stmt.execute("DELETE FROM orders WHERE id = 999") }
        }
    }
}
