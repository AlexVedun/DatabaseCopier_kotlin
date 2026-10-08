package com.example.databasecopier.adapter

import java.sql.Connection
import java.util.concurrent.Executor

private val directExecutor = Executor(Runnable::run)

internal fun <T> Connection.withTemporaryNetworkTimeout(
    timeoutMillis: Int,
    operation: () -> T,
): T {
    val previousTimeout = networkTimeout
    setNetworkTimeout(directExecutor, timeoutMillis)
    var operationFailure: Throwable? = null
    try {
        return operation()
    } catch (e: Throwable) {
        operationFailure = e
        throw e
    } finally {
        try {
            setNetworkTimeout(directExecutor, previousTimeout)
        } catch (restoreFailure: Throwable) {
            if (operationFailure != null) {
                operationFailure.addSuppressed(restoreFailure)
            } else {
                throw restoreFailure
            }
        }
    }
}
