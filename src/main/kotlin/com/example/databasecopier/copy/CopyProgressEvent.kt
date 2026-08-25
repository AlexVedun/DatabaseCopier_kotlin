package com.example.databasecopier.copy

data class CopyProgressEvent(
    val sessionId: Int,
    val tableId: Int,
    val tableName: String,
    val tableStatus: String,
    val rowsCopied: Long,
    val rowsTotal: Long?,
)
