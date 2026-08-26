package com.example.databasecopier.session

data class CopySessionRecord(
    val id: Int,
    val name: String,
    val status: String, // draft/running/paused/completed/cancelled/failed
    val sourceType: String, // connection/dump
    val sourceConnectionId: Int?,
    val sourceDumpPath: String?,
    val sourceDumpDialect: String?,
    val targetConnectionId: Int,
    val copyMode: String, // structure_only/structure_and_data
    val batchSize: Int,
    val lastError: String?,
    val updatedAt: Long,
)

data class CopySessionTableRecord(
    val id: Int,
    val copySessionId: Int,
    val tableName: String,
    val isSelected: Boolean,
    val status: String, // pending/in_progress/done/skipped/failed
    val rowsTotal: Long?,
    val rowsCopied: Long,
    val cursorJson: String?,
    val structureCopied: Boolean,
    val foreignKeysCopied: Boolean,
    val indexesCopied: Boolean,
)
