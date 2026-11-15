package com.example.databasecopier.ui

import atlantafx.base.theme.PrimerDark
import atlantafx.base.theme.PrimerLight
import javafx.application.Application
import javafx.application.ColorScheme
import javafx.application.Platform
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

class ThemeManagerTest {

    private lateinit var originalThemeFile: File
    private lateinit var originalMode: AppThemeMode
    private lateinit var tempThemeFile: File

    @BeforeEach
    fun setUp() {
        originalThemeFile = ThemeManager.themeFile
        originalMode = ThemeManager.currentMode
        tempThemeFile = Files.createTempFile("theme-manager-test-", ".txt").toFile()
        ThemeManager.themeFile = tempThemeFile
    }

    @AfterEach
    fun tearDown() {
        ThemeManager.themeFile = originalThemeFile
        ThemeManager.currentMode = originalMode
        runCatching { tempThemeFile.delete() }
    }

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
    fun `runCommandCapture times out and terminates hung command without blocking`() {
        val result = ThemeManager.runCommandCapture("sh", "-c", "sleep 5", timeoutMs = 100L)
        assertNull(result)
    }

    @Test
    fun `switches userAgentStylesheet between PrimerLight and PrimerDark using isolated theme file`() {
        assumeTrue(ensureJavaFxToolkit(), "JavaFX DISPLAY is not available in this environment")
        val latch = CountDownLatch(1)
        Platform.runLater {
            try {
                ThemeManager.setMode(AppThemeMode.DARK)
                assertEquals(PrimerDark().userAgentStylesheet, Application.getUserAgentStylesheet())
                assertEquals("DARK", tempThemeFile.readText())

                ThemeManager.setMode(AppThemeMode.LIGHT)
                assertEquals(PrimerLight().userAgentStylesheet, Application.getUserAgentStylesheet())
                assertEquals("LIGHT", tempThemeFile.readText())
            } finally {
                latch.countDown()
            }
        }
        latch.await()
    }
}
