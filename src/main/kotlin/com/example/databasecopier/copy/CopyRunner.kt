package com.example.databasecopier.copy

import com.example.databasecopier.adapter.RoutineKind
import com.example.databasecopier.adapter.RoutineRef
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
import org.slf4j.LoggerFactory

/**
 * Фоновый процесс копирования выбранных таблиц сессии. Не привязан к жизни конкретного окна —
 * вызывающая сторона (UI) запускает [run] в собственной корутине на уровне приложения.
 *
 * Пауза/отмена реализованы не через отмену корутины, а через проверку статуса сессии в служебной
 * БД перед каждым батчем и перед каждой таблицей: если статус сменился на paused/cancelled,
 * run() штатно завершается, не теряя уже сохранённый курсор.
 */
class CopyRunner {

    private val log = LoggerFactory.getLogger(CopyRunner::class.java)

    private val _progress = MutableSharedFlow<CopyProgressEvent>(extraBufferCapacity = 64)
    val progress: SharedFlow<CopyProgressEvent> = _progress.asSharedFlow()

    suspend fun run(sessionId: Int, source: SourceAdapter, target: TargetAdapter) = withContext(Dispatchers.IO) {
        val session = CopySessionRepository.getSession(sessionId) ?: return@withContext
        val needsStructure = true // оба режима (structure_only/structure_and_data) копируют структуру
        val needsData = session.copyMode == "structure_and_data"

        log.info("Сессия {}: старт, таблиц выбрано={}, needsData={}", sessionId, CopySessionRepository.getTables(sessionId).count { it.isSelected }, needsData)
        try {
            val allSelected = CopySessionRepository.getTables(sessionId).filter { it.isSelected }
            val pending = allSelected.filter { it.status != "done" && it.status != "skipped" }
            log.info("Сессия {}: к копированию осталось {} из {} таблиц", sessionId, pending.size, allSelected.size)

            // FK-проверки отключаются на весь оставшийся ход сессии (переживает паузу/возобновление —
            // включаются обратно только по успешном завершении всей сессии, см. Шаг 4/9 инструкции).
            log.debug("Сессия {}: отключаю проверку внешних ключей на приёмнике", sessionId)
            target.disableForeignKeyChecks()

            for (table in pending) {
                if (!isRunnable(sessionId)) {
                    log.info("Сессия {}: остановлена (пауза/отмена) перед таблицей {}", sessionId, table.tableName)
                    return@withContext
                }

                var current = table
                log.info("Сессия {}: таблица {} — читаю структуру из источника", sessionId, current.tableName)
                val structureStart = System.currentTimeMillis()
                val structure = source.getTableStructure(current.tableName)
                log.info(
                    "Сессия {}: таблица {} — структура получена за {} мс, колонок={}",
                    sessionId, current.tableName, System.currentTimeMillis() - structureStart, structure.columns.size,
                )

                if (needsStructure && !current.structureCopied) {
                    log.info("Сессия {}: таблица {} — создаю на приёмнике", sessionId, current.tableName)
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
                    log.info("Сессия {}: таблица {} — создаю индексы/CHECK-констрейнты", sessionId, current.tableName)
                    target.createIndexesAndConstraints(structure)
                    CopySessionRepository.markIndexesCopied(current.id)
                    current = current.copy(indexesCopied = true)
                }

                CopySessionRepository.updateTableStatus(current.id, "done")
                log.info("Сессия {}: таблица {} — готово", sessionId, current.tableName)
                emit(sessionId, current.copy(status = "done"))
            }

            // FK создаются отдельным проходом ПОСЛЕ того, как все выбранные таблицы гарантированно
            // существуют на target (иначе REFERENCES на ещё не созданную таблицу упадёт). У этого
            // прохода нет построчного прогресса (все таблицы уже помечены "done" в основном цикле
            // выше) — без явного emit() здесь UI зависает с показателями последней скопированной
            // таблицы на всё время этого прохода, не давая понять, сколько ещё осталось.
            val fkPending = allSelected.filter { !it.foreignKeysCopied }
            log.info("Сессия {}: прохожу внешние ключи для {} таблиц", sessionId, fkPending.size)
            val selectedNames = allSelected.map { it.tableName }.toSet()
            for ((index, table) in fkPending.withIndex()) {
                if (!isRunnable(sessionId)) {
                    log.info("Сессия {}: остановлена перед внешними ключами таблицы {}", sessionId, table.tableName)
                    return@withContext
                }

                val foreignKeys = source.getForeignKeys(table.tableName)
                    .filter { it.referencedTable in selectedNames }
                log.debug("Сессия {}: таблица {} — внешних ключей={}", sessionId, table.tableName, foreignKeys.size)
                target.createForeignKeys(table.tableName, foreignKeys)
                CopySessionRepository.markForeignKeysCopied(table.id)
                emitPhaseProgress(sessionId, "foreign_keys", table.tableName, (index + 1).toLong(), fkPending.size.toLong())
            }

            target.enableForeignKeyChecks()
            log.info("Сессия {}: внешние ключи и представления — переход к вьюхам", sessionId)

            // Views создаются последним проходом, когда все выбранные таблицы (со структурой,
            // данными, FK и индексами) уже существуют на target. Между разными диалектами тело
            // view почти никогда не является валидным SQL — вместо предварительной проверки
            // диалекта просто пробуем создать и, если СУБД отвергла синтаксис, помечаем view как
            // требующую ручной адаптации, не прерывая копирование остальных объектов сессии.
            val viewsPending = CopySessionRepository.getViews(sessionId).filter { it.isSelected && it.status == "pending" }
            for ((index, view) in viewsPending.withIndex()) {
                if (!isRunnable(sessionId)) {
                    log.info("Сессия {}: остановлена перед вьюхой {}", sessionId, view.viewName)
                    return@withContext
                }
                try {
                    val definition = source.getViewDefinition(view.viewName)
                    target.createView(view.viewName, definition)
                    CopySessionRepository.updateViewStatus(view.id, "done")
                } catch (e: Exception) {
                    log.warn("Сессия {}: вьюха {} требует ручной адаптации — {}", sessionId, view.viewName, e.message)
                    CopySessionRepository.updateViewStatus(view.id, "manual_adaptation_required")
                }
                emitPhaseProgress(sessionId, "views", view.viewName, (index + 1).toLong(), viewsPending.size.toLong())
            }

            // Хранимые процедуры/функции — тот же принцип, что и views: UI разрешает их выбор только
            // когда тип source и target совпадает (см. SourcePanelController), поэтому здесь не
            // делается отдельной проверки диалектов — только защитный try/catch на случай, если
            // target СУБД всё же отвергнет синтаксис (например, из-за версии сервера).
            val routinesPending = CopySessionRepository.getRoutines(sessionId).filter { it.isSelected && it.status == "pending" }
            for ((index, routine) in routinesPending.withIndex()) {
                if (!isRunnable(sessionId)) {
                    log.info("Сессия {}: остановлена перед процедурой/функцией {}", sessionId, routine.routineName)
                    return@withContext
                }
                try {
                    val ref = RoutineRef(routine.routineName, RoutineKind.valueOf(routine.routineKind.uppercase()))
                    val definition = source.getRoutineDefinition(ref)
                    target.createRoutine(ref, definition)
                    CopySessionRepository.updateRoutineStatus(routine.id, "done")
                } catch (e: Exception) {
                    log.warn("Сессия {}: процедура/функция {} требует ручной адаптации — {}", sessionId, routine.routineName, e.message)
                    CopySessionRepository.updateRoutineStatus(routine.id, "manual_adaptation_required")
                }
                emitPhaseProgress(sessionId, "routines", routine.routineName, (index + 1).toLong(), routinesPending.size.toLong())
            }

            CopySessionRepository.updateSessionStatus(sessionId, "completed")
            log.info("Сессия {}: завершена успешно", sessionId)
        } catch (e: Exception) {
            log.error("Сессия {}: упала с ошибкой", sessionId, e)
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
        log.info("Сессия {}: таблица {} — начинаю копирование данных (batchSize={}, уже скопировано строк={})", sessionId, current.tableName, batchSize, current.rowsCopied)
        CopySessionRepository.updateTableStatus(current.id, "in_progress")
        current = current.copy(status = "in_progress")
        emit(sessionId, current)

        var cursor: JsonElement? = current.cursorJson?.let { Json.parseToJsonElement(it) }
        var maxAutoIncrementValue: Long? = null

        while (true) {
            if (!isRunnable(sessionId)) {
                log.info("Сессия {}: таблица {} — остановлена (пауза/отмена) на {} скопированных строках", sessionId, current.tableName, current.rowsCopied)
                return null
            }

            val batch = source.readBatch(current.tableName, cursor, batchSize)
            log.debug("Сессия {}: таблица {} — прочитан батч из источника, строк={}", sessionId, current.tableName, batch.rows.size)
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
        log.info("Сессия {}: таблица {} — данные скопированы полностью, всего строк={}", sessionId, current.tableName, current.rowsCopied)
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

    // tableId=0 — у прохода FK/views нет своей CopySessionTableRecord на объект (это таблица или
    // представление, обрабатываемое как единица прохода, а не строка данных); UI ориентируется на
    // phase/rowsCopied/rowsTotal, а не на tableId, для этих событий.
    private fun emitPhaseProgress(sessionId: Int, phase: String, name: String, processed: Long, total: Long) {
        _progress.tryEmit(
            CopyProgressEvent(
                sessionId = sessionId,
                tableId = 0,
                tableName = name,
                tableStatus = "in_progress",
                rowsCopied = processed,
                rowsTotal = total,
                phase = phase,
            )
        )
    }
}
