package com.example.databasecopier.adapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.sql.Connection

class ConnectionTimeoutTest {
    @Test
    fun `temporary network timeout is restored after success`() {
        val fake = fakeConnection(initialTimeout = 600_000)

        val result = fake.connection.withTemporaryNetworkTimeout(0) {
            assertEquals(0, fake.currentTimeout)
            "done"
        }

        assertEquals("done", result)
        assertEquals(600_000, fake.currentTimeout)
        assertEquals(listOf(0, 600_000), fake.assignedTimeouts)
    }

    @Test
    fun `temporary network timeout is restored after operation failure`() {
        val fake = fakeConnection(initialTimeout = 600_000)

        val error = assertThrows(IllegalStateException::class.java) {
            fake.connection.withTemporaryNetworkTimeout(0) {
                assertEquals(0, fake.currentTimeout)
                error("DDL failed")
            }
        }

        assertEquals("DDL failed", error.message)
        assertEquals(600_000, fake.currentTimeout)
        assertEquals(listOf(0, 600_000), fake.assignedTimeouts)
    }

    private fun fakeConnection(initialTimeout: Int): FakeConnection {
        var timeout = initialTimeout
        val assigned = mutableListOf<Int>()
        val connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getNetworkTimeout" -> timeout
                "setNetworkTimeout" -> {
                    timeout = args[1] as Int
                    assigned += timeout
                    null
                }
                else -> throw UnsupportedOperationException(method.name)
            }
        } as Connection
        return FakeConnection(connection, { timeout }, assigned)
    }

    private class FakeConnection(
        val connection: Connection,
        private val timeout: () -> Int,
        val assignedTimeouts: List<Int>,
    ) {
        val currentTimeout: Int get() = timeout()
    }
}
