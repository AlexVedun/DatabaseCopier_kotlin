package com.example.databasecopier.copy

import com.example.databasecopier.adapter.SourceAdapter
import com.example.databasecopier.adapter.TargetAdapter
import com.example.databasecopier.session.CopySessionRepository
import com.example.databasecopier.session.CopySessionTableRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Фоновый процесс копирования выбранных таблиц сессии. Не привязан к жизни конкретного окна —
 * вызывающая сторона (UI) запускает [run] в собственной корутине на уровне приложения.
 *
 * Пауза/отмена реализованы не через отмену корутины, а через проверку статуса сессии в служебной
 * БД перед каждым батчем и перед каждой таблицей: если статус сменился на paused/cancelled,
 * run() штатно завершается, не теряя уже сохранённый курсор.
 */
class CopyRunner {

    private val _progress = MutableSharedFlow<CopyProgressEvent>(extraBufferCapacity = 64)
    val progress: SharedFlow<CopyProgressEvent> = _progress.asSharedFlow()

    suspend fun run(sessionId: Int, source: SourceAdapter, target: TargetAdapter) = withContext(Dispatchers.IO) {
        val session = CopySessionRepository.getSession(sessionId) ?: return@withContext
        val needsStructure = true // оба режима (structure_only/structure_and_data) копируют структуру
        val needsData = session.copyMode == "structure_and_data"

        try {
            val allSelected = CopySessionRepository.getTables(sessionId).filter { it.isSelected }
            val pending = allSelected.filter { it.status != "done" && it.status != "skipped" }

            // FK-проверки отключаются на весь оставшийся ход сессии (переживает паузу/возобновление —
            // включаются обратно только по успешном завершении всей сессии, см. Шаг 4/9 инструкции).
            target.disableForeignKeyChecks()

            for (table in pending) {
                if (!isRunnable(sessionId)) return@withContext

                var current = table
                val structure = source.getTableStructure(current.tableName)

                if (needsStructure && !current.structureCopied) {
                    target.createTable(structure)
                    CopySessionRepository.markStructureCopied(current.id)
                    current = current.copy(structureCopied = true)
                }

                // dataCopied — отдельный флаг от cursorJson: у полностью скопированной таблицы
                // курсор тоже null (означает "данных больше нет"), что неотличимо от "копирование
                // ещё не начиналось". Без этого флага повторный запуск (например, после падения
                // на создании индекса/FK для другой таблицы уже ПОСЛЕ того, как эта таблица была
                // полностью скопирована) читал бы с начала и падал на дубликате PK при вставке
                // уже скопированных строк.
                if (needsData && !current.dataCopied) {
                    val autoIncrementColumn = structure.columns.firstOrNull { it.autoIncrement }?.name
                    current = copyTableData(sessionId, current, source, target, session.batchSize, autoIncrementColumn)
                        ?: return@withContext // паузa/отмена внутри копирования данных таблицы
                }

                // Индексы/CHECK создаются после загрузки данных (быстрее, чем поддерживать индекс
                // при каждой вставке батча) — для structure_only режима данных нет, создаются сразу.
                if (!current.indexesCopied) {
                    target.createIndexesAndConstraints(structure)
                    CopySessionRepository.markIndexesCopied(current.id)
                    current = current.copy(indexesCopied = true)
                }

                CopySessionRepository.updateTableStatus(current.id, "done")
                emit(sessionId, current.copy(status = "done"))
            }

            // FK создаются отдельным проходом ПОСЛЕ того, как все выбранные таблицы гарантированно
            // существуют на target (иначе REFERENCES на ещё не созданную таблицу упадёт).
            val selectedNames = allSelected.map { it.tableName }.toSet()
            for (table in allSelected.filter { !it.foreignKeysCopied }) {
                if (!isRunnable(sessionId)) return@withContext

                val foreignKeys = source.getForeignKeys(table.tableName)
                    .filter { it.referencedTable in selectedNames }
                target.createForeignKeys(table.tableName, foreignKeys)
                CopySessionRepository.markForeignKeysCopied(table.id)
            }

            target.enableForeignKeyChecks()

            // Views создаются последним проходом, когда все выбранные таблицы (со структурой,
            // данными, FK и индексами) уже существуют на target. Между разными диалектами тело
            // view почти никогда не является валидным SQL — вместо предварительной проверки
            // диалекта просто пробуем создать и, если СУБД отвергла синтаксис, помечаем view как
            // требующую ручной адаптации, не прерывая копирование остальных объектов сессии.
            for (view in CopySessionRepository.getViews(sessionId).filter { it.isSelected && it.status == "pending" }) {
                if (!isRunnable(sessionId)) return@withContext
                try {
                    val definition = source.getViewDefinition(view.viewName)
                    target.createView(view.viewName, definition)
                    CopySessionRepository.updateViewStatus(view.id, "done")
                } catch (e: Exception) {
                    CopySessionRepository.updateViewStatus(view.id, "manual_adaptation_required")
                }
            }

            CopySessionRepository.updateSessionStatus(sessionId, "completed")
        } catch (e: Exception) {
            CopySessionRepository.updateSessionStatus(sessionId, "failed", lastError = e.message)
        }
    }

    /** Возвращает обновлённую запись таблицы, либо null если копирование было приостановлено/отменено. */
    private fun copyTableData(
        sessionId: Int,
        initial: CopySessionTableRecord,
        source: SourceAdapter,
        target: TargetAdapter,
        batchSize: Int,
        autoIncrementColumn: String?,
    ): CopySessionTableRecord? {
        var current = initial
        CopySessionRepository.updateTableStatus(current.id, "in_progress")
        current = current.copy(status = "in_progress")
        emit(sessionId, current)

        var cursor: JsonElement? = current.cursorJson?.let { Json.parseToJsonElement(it) }
        var maxAutoIncrementValue: Long? = null

        while (true) {
            if (!isRunnable(sessionId)) return null

            val batch = source.readBatch(current.tableName, cursor, batchSize)
            if (batch.rows.isNotEmpty()) {
                target.insertBatch(current.tableName, batch.rows)
                if (autoIncrementColumn != null) {
                    for (row in batch.rows) {
                        val value = (row[autoIncrementColumn] as? Number)?.toLong() ?: continue
                        if (maxAutoIncrementValue == null || value > maxAutoIncrementValue!!) maxAutoIncrementValue = value
                    }
                }
            }

            val rowsCopied = current.rowsCopied + batch.rows.size
            val cursorJson = batch.nextCursor?.toString()
            CopySessionRepository.updateTableProgress(current.id, rowsCopied, cursorJson)
            current = current.copy(rowsCopied = rowsCopied, cursorJson = cursorJson)
            emit(sessionId, current)

            cursor = batch.nextCursor
            if (cursor == null) break
        }

        // Синхронизация счётчика — только после того, как ВСЕ строки таблицы скопированы (не на
        // каждом батче), иначе новые строки, вставленные в target вручную между батчами, рискуют
        // получить PK, конфликтующий с ещё не скопированными строками источника.
        if (autoIncrementColumn != null && maxAutoIncrementValue != null) {
            target.syncAutoIncrement(current.tableName, autoIncrementColumn, maxAutoIncrementValue!!)
        }

        CopySessionRepository.markDataCopied(current.id)
        current = current.copy(dataCopied = true)

        return current
    }

    private fun isRunnable(sessionId: Int): Boolean {
        val status = CopySessionRepository.getSession(sessionId)?.status
        return status == "running"
    }

    private fun emit(sessionId: Int, table: CopySessionTableRecord) {
        _progress.tryEmit(
            CopyProgressEvent(
                sessionId = sessionId,
                tableId = table.id,
                tableName = table.tableName,
                tableStatus = table.status,
                rowsCopied = table.rowsCopied,
                rowsTotal = table.rowsTotal,
            )
        )
    }
}
