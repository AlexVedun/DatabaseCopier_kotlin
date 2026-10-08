package com.example.databasecopier

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class AppInfoTest {
    @Test
    fun `window title contains expanded application version`() {
        assertFalse(AppInfo.version.contains('$'))
        assertEquals("${AppInfo.NAME} - ${AppInfo.version}", AppInfo.windowTitle)
    }
}
