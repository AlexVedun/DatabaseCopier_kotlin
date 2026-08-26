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

                if (needsData) {
                    current = copyTableData(sessionId, current, source, target, session.batchSize)
                        ?: return@withContext // паузa/отмена внутри копирования данных таблицы
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
    ): CopySessionTableRecord? {
        var current = initial
        CopySessionRepository.updateTableStatus(current.id, "in_progress")
        current = current.copy(status = "in_progress")
        emit(sessionId, current)

        var cursor: JsonElement? = current.cursorJson?.let { Json.parseToJsonElement(it) }

        while (true) {
            if (!isRunnable(sessionId)) return null

            val batch = source.readBatch(current.tableName, cursor, batchSize)
            if (batch.rows.isNotEmpty()) {
                target.insertBatch(current.tableName, batch.rows)
            }

            val rowsCopied = current.rowsCopied + batch.rows.size
            val cursorJson = batch.nextCursor?.toString()
            CopySessionRepository.updateTableProgress(current.id, rowsCopied, cursorJson)
            current = current.copy(rowsCopied = rowsCopied, cursorJson = cursorJson)
            emit(sessionId, current)

            cursor = batch.nextCursor
            if (cursor == null) break
        }

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
