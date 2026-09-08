package com.example.databasecopier.copy

// phase различает три прохода CopyRunner.run(): "data" — обычное копирование таблицы (rowsCopied/
// rowsTotal — реальные строки, исходное поведение по умолчанию), "foreign_keys"/"views" — отдельные
// проходы ПОСЛЕ копирования всех таблиц, у которых нет построчного прогресса — там rowsCopied/
// rowsTotal переиспользуются как "обработано объектов / всего объектов" (таблиц или представлений),
// чтобы у этих фаз тоже была видимая индикация вместо зависшего на последней таблице прогресс-бара.
data class CopyProgressEvent(
    val sessionId: Int,
    val tableId: Int,
    val tableName: String,
    val tableStatus: String,
    val rowsCopied: Long,
    val rowsTotal: Long?,
    val phase: String = "data",
)
