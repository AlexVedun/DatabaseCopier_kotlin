package com.example.databasecopier

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Корутин-скоуп уровня приложения (не привязан к жизни конкретного окна) — копирование должно
 * продолжаться, даже если пользователь переключился на другой экран.
 */
object AppScope {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}
