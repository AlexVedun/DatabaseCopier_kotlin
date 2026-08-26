package com.example.databasecopier.session

import com.example.databasecopier.CopySessionTables
import com.example.databasecopier.CopySessionViews
import com.example.databasecopier.CopySessions
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

object CopySessionRepository {

    fun createSession(
        name: String,
        sourceType: String,
        sourceConnectionId: Int?,
        sourceDumpPath: String?,
        sourceDumpDialect: String?,
        targetConnectionId: Int,
        copyMode: String,
        batchSize: Int = 1000,
    ): Int = transaction {
        val now = System.currentTimeMillis()
        (CopySessions.insert {
            it[CopySessions.name] = name
            it[status] = "draft"
            it[CopySessions.sourceType] = sourceType
            it[CopySessions.sourceConnectionId] = sourceConnectionId
            it[CopySessions.sourceDumpPath] = sourceDumpPath
            it[CopySessions.sourceDumpDialect] = sourceDumpDialect
            it[CopySessions.targetConnectionId] = targetConnectionId
            it[CopySessions.copyMode] = copyMode
            it[CopySessions.batchSize] = batchSize
            it[createdAt] = now
            it[updatedAt] = now
        } get CopySessions.id)
    }

    fun addTable(sessionId: Int, tableName: String, isSelected: Boolean = true): Int = transaction {
        (CopySessionTables.insert {
            it[copySessionId] = sessionId
            it[sourceTableName] = tableName
            it[CopySessionTables.isSelected] = isSelected
            it[status] = "pending"
            it[rowsCopied] = 0
            it[structureCopied] = false
        } get CopySessionTables.id)
    }

    fun addView(sessionId: Int, viewName: String, isSelected: Boolean = false): Int = transaction {
        (CopySessionViews.insert {
            it[copySessionId] = sessionId
            it[CopySessionViews.viewName] = viewName
            it[CopySessionViews.isSelected] = isSelected
            it[status] = "pending"
        } get CopySessionViews.id)
    }

    fun getViews(sessionId: Int): List<CopySessionViewRecord> = transaction {
        CopySessionViews.select { CopySessionViews.copySessionId eq sessionId }.map { it.toViewRecord() }
    }

    fun updateViewStatus(id: Int, status: String) = transaction {
        CopySessionViews.update({ CopySessionViews.id eq id }) {
            it[CopySessionViews.status] = status
        }
    }

    fun getSession(id: Int): CopySessionRecord? = transaction {
        CopySessions.select { CopySessions.id eq id }.map { it.toSessionRecord() }.firstOrNull()
    }

    fun getTables(sessionId: Int): List<CopySessionTableRecord> = transaction {
        CopySessionTables.select { CopySessionTables.copySessionId eq sessionId }
            .map { it.toTableRecord() }
    }

    /** Сессии, которые имеет смысл показать на стартовом экране как "продолжаемые". */
    fun listResumable(): List<CopySessionRecord> = transaction {
        CopySessions.select {
            CopySessions.status inList listOf("draft", "paused", "running", "failed")
        }.orderBy(CopySessions.updatedAt, org.jetbrains.exposed.sql.SortOrder.DESC)
            .map { it.toSessionRecord() }
    }

    fun updateSessionStatus(id: Int, status: String, lastError: String? = null) = transaction {
        CopySessions.update({ CopySessions.id eq id }) {
            it[CopySessions.status] = status
            it[CopySessions.lastError] = lastError
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    fun updateTableStatus(id: Int, status: String) = transaction {
        CopySessionTables.update({ CopySessionTables.id eq id }) {
            it[CopySessionTables.status] = status
        }
    }

    fun markStructureCopied(id: Int) = transaction {
        CopySessionTables.update({ CopySessionTables.id eq id }) {
            it[structureCopied] = true
        }
    }

    fun markForeignKeysCopied(id: Int) = transaction {
        CopySessionTables.update({ CopySessionTables.id eq id }) {
            it[foreignKeysCopied] = true
        }
    }

    fun markIndexesCopied(id: Int) = transaction {
        CopySessionTables.update({ CopySessionTables.id eq id }) {
            it[indexesCopied] = true
        }
    }

    fun updateTableProgress(id: Int, rowsCopied: Long, cursorJson: String?) = transaction {
        CopySessionTables.update({ CopySessionTables.id eq id }) {
            it[CopySessionTables.rowsCopied] = rowsCopied
            it[CopySessionTables.cursorJson] = cursorJson
        }
    }

    /**
     * Вызывается при старте приложения: сессии, оставшиеся в статусе "running" после
     * некорректного завершения предыдущего запуска (закрытие окна во время копирования),
     * переводятся в "paused" — пользователь сможет продолжить их вручную с сохранённого курсора.
     */
    fun pauseAllRunningSessions() = transaction {
        CopySessions.update({ CopySessions.status eq "running" }) {
            it[status] = "paused"
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    private fun ResultRow.toSessionRecord() = CopySessionRecord(
        id = this[CopySessions.id],
        name = this[CopySessions.name],
        status = this[CopySessions.status],
        sourceType = this[CopySessions.sourceType],
        sourceConnectionId = this[CopySessions.sourceConnectionId],
        sourceDumpPath = this[CopySessions.sourceDumpPath],
        sourceDumpDialect = this[CopySessions.sourceDumpDialect],
        targetConnectionId = this[CopySessions.targetConnectionId],
        copyMode = this[CopySessions.copyMode],
        batchSize = this[CopySessions.batchSize],
        lastError = this[CopySessions.lastError],
        updatedAt = this[CopySessions.updatedAt],
    )

    private fun ResultRow.toTableRecord() = CopySessionTableRecord(
        id = this[CopySessionTables.id],
        copySessionId = this[CopySessionTables.copySessionId],
        tableName = this[CopySessionTables.sourceTableName],
        isSelected = this[CopySessionTables.isSelected],
        status = this[CopySessionTables.status],
        rowsTotal = this[CopySessionTables.rowsTotal],
        rowsCopied = this[CopySessionTables.rowsCopied],
        cursorJson = this[CopySessionTables.cursorJson],
        structureCopied = this[CopySessionTables.structureCopied],
        foreignKeysCopied = this[CopySessionTables.foreignKeysCopied],
        indexesCopied = this[CopySessionTables.indexesCopied],
    )

    private fun ResultRow.toViewRecord() = CopySessionViewRecord(
        id = this[CopySessionViews.id],
        copySessionId = this[CopySessionViews.copySessionId],
        viewName = this[CopySessionViews.viewName],
        isSelected = this[CopySessionViews.isSelected],
        status = this[CopySessionViews.status],
    )
}
