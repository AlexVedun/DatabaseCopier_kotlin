package com.example.databasecopier.ui

import atlantafx.base.theme.PrimerDark
import atlantafx.base.theme.PrimerLight
import javafx.application.Application
import javafx.application.ColorScheme
import javafx.application.Platform
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch

class ThemeManagerTest {

    private fun ensureJavaFxToolkit(): Boolean {
        val latch = CountDownLatch(1)
        return try {
            Platform.startup { latch.countDown() }
            latch.await()
            true
        } catch (_: IllegalStateException) {
            Platform.runLater { latch.countDown() }
            latch.await()
            true
        } catch (_: UnsupportedOperationException) {
            false
        }
    }

    @Test
    fun `parses GNOME and XDG Desktop Portal color-scheme outputs`() {
        assertEquals(true, ThemeManager.parseLinuxDarkPreference(null, "'prefer-dark'"))
        assertEquals(false, ThemeManager.parseLinuxDarkPreference(null, "'prefer-light'"))
        assertNull(ThemeManager.parseLinuxDarkPreference(null, "'default'"))

        assertEquals(true, ThemeManager.parseLinuxDarkPreference("(<<uint32 1>>,)", "'default'"))
        assertEquals(false, ThemeManager.parseLinuxDarkPreference("(<<uint32 2>>,)", "'default'"))
        assertNull(ThemeManager.parseLinuxDarkPreference("(<<uint32 0>>,)", "'default'"))
    }

    @Test
    fun `detectSystemDark falls back to JavaFX ColorScheme when portal has no preference`() {
        assertTrue(
            ThemeManager.detectSystemDark(
                osName = "linux",
                fxColorScheme = ColorScheme.DARK,
                linuxPortalScheme = "(<<uint32 0>>,)",
                gnomeColorScheme = "'default'",
            )
        )
        assertFalse(
            ThemeManager.detectSystemDark(
                osName = "linux",
                fxColorScheme = ColorScheme.LIGHT,
                linuxPortalScheme = "(<<uint32 0>>,)",
                gnomeColorScheme = "'default'",
            )
        )
        assertTrue(
            ThemeManager.detectSystemDark(
                osName = "linux",
                fxColorScheme = ColorScheme.LIGHT,
                linuxPortalScheme = "(<<uint32 1>>,)",
                gnomeColorScheme = "'prefer-dark'",
            )
        )
    }

    @Test
    fun `switches userAgentStylesheet between PrimerLight and PrimerDark`() {
        assumeTrue(ensureJavaFxToolkit(), "JavaFX DISPLAY is not available in this environment")
        val latch = CountDownLatch(1)
        Platform.runLater {
            try {
                ThemeManager.setMode(AppThemeMode.DARK)
                assertEquals(PrimerDark().userAgentStylesheet, Application.getUserAgentStylesheet())

                ThemeManager.setMode(AppThemeMode.LIGHT)
                assertEquals(PrimerLight().userAgentStylesheet, Application.getUserAgentStylesheet())

                ThemeManager.setMode(AppThemeMode.SYSTEM)
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }
}
