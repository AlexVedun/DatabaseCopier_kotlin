package com.example.databasecopier.update

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class AppUpdaterTest {

    @Test
    fun `compares semantic versions accurately`() {
        assertTrue(AppUpdater.compareVersions("1.2.8", "1.2.7") > 0)
        assertTrue(AppUpdater.compareVersions("v1.10.0", "1.2.9") > 0)
        assertTrue(AppUpdater.compareVersions("2.0.0", "1.99.99") > 0)
        assertEquals(0, AppUpdater.compareVersions("v1.2.7", "1.2.7"))
        assertTrue(AppUpdater.compareVersions("1.2.6", "1.2.7") < 0)
    }

    @Test
    fun `parses GitHub release JSON and selects platform asset`() {
        val sampleJson = """
            {
              "tag_name": "v1.3.0",
              "html_url": "https://github.com/AlexVedun/DatabaseCopier_kotlin/releases/tag/v1.3.0",
              "body": "Bug fixes and improvements",
              "assets": [
                {
                  "name": "database-copier-1.3.0-x86_64.AppImage",
                  "browser_download_url": "https://example.com/database-copier-1.3.0-x86_64.AppImage",
                  "size": 75000000
                },
                {
                  "name": "database-copier-1.3.0.dmg",
                  "browser_download_url": "https://example.com/database-copier-1.3.0.dmg",
                  "size": 68000000
                },
                {
                  "name": "database-copier-1.3.0.exe",
                  "browser_download_url": "https://example.com/database-copier-1.3.0.exe",
                  "size": 64000000
                }
              ]
            }
        """.trimIndent()

        val macRelease = AppUpdater.parseReleaseJson(sampleJson, UpdatePlatform.MACOS)
        assertEquals("1.3.0", macRelease.version)
        assertEquals("v1.3.0", macRelease.tagName)
        assertEquals("Bug fixes and improvements", macRelease.releaseNotes)
        assertNotNull(macRelease.asset)
        assertEquals("database-copier-1.3.0.dmg", macRelease.asset?.name)

        val linuxRelease = AppUpdater.parseReleaseJson(sampleJson, UpdatePlatform.LINUX)
        assertEquals("database-copier-1.3.0-x86_64.AppImage", linuxRelease.asset?.name)

        val windowsRelease = AppUpdater.parseReleaseJson(sampleJson, UpdatePlatform.WINDOWS)
        assertEquals("database-copier-1.3.0.exe", windowsRelease.asset?.name)
    }

    @Test
    fun `resolves enclosing macOS app bundle path`() {
        val resolved = AppUpdater.resolveMacAppBundlePath(
            jpackageAppPath = "/Applications/database-copier.app/Contents/MacOS/database-copier",
            processCommandPath = null,
            codeSourcePath = null,
        )
        assertEquals(File("/Applications/database-copier.app"), resolved)

        val fallback = AppUpdater.resolveMacAppBundlePath(
            jpackageAppPath = "/usr/local/bin/java",
            processCommandPath = "/usr/local/bin/java",
            codeSourcePath = "/tmp/build/libs/app.jar",
            defaultApplicationsDir = File("/Applications"),
        )
        assertEquals(File("/Applications/database-copier.app"), fallback)
    }

    @Test
    fun `macOS DMG update keeps backup until relaunch succeeds, then removes backup`() {
        val tempDir = Files.createTempDirectory("mac-update-test-").toFile()
        try {
            val dmgFile = File(tempDir, "database-copier-1.3.0.dmg").apply { writeText("fake-dmg") }
            val targetApp = File(tempDir, "Applications/database-copier.app").apply { mkdirs() }
            val backupApp = File(tempDir, "Applications/database-copier.app.old")

            val events = mutableListOf<String>()
            var relaunchedBundle: File? = null

            AppUpdater.installMacDmgAndRestart(
                dmgFile = dmgFile,
                targetAppBundle = targetApp,
                commandRunner = { cmd ->
                    events.add(cmd.joinToString(" "))
                    if (cmd.take(2) == listOf("hdiutil", "attach")) {
                        val mountPointPath = cmd[cmd.indexOf("-mountpoint") + 1]
                        File(mountPointPath, "database-copier.app").mkdirs()
                    }
                    AppUpdater.CommandResult(exitCode = 0, output = "")
                },
                relauncher = { app ->
                    events.add("relaunch ${app.absolutePath}")
                    relaunchedBundle = app
                },
            )

            val moveToBackupIdx = events.indexOf("mv ${targetApp.absolutePath} ${backupApp.absolutePath}")
            val dittoIdx = events.indexOfFirst { it.startsWith("ditto ") && it.endsWith(targetApp.absolutePath) }
            val relaunchIdx = events.indexOf("relaunch ${targetApp.absolutePath}")
            val removeBackupIdx = events.indexOf("rm -rf ${backupApp.absolutePath}")

            assertTrue(moveToBackupIdx >= 0)
            assertTrue(dittoIdx > moveToBackupIdx)
            assertTrue(relaunchIdx > dittoIdx, "Relaunch must happen after ditto")
            assertTrue(removeBackupIdx > relaunchIdx, "Backup .app.old must be removed only AFTER relaunch succeeds")

            assertEquals(targetApp, relaunchedBundle)
            assertFalse(dmgFile.exists(), "Temporary DMG file should be deleted after update")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `macOS DMG update restores backup when ditto throws exception or relaunch fails`() {
        val tempDir = Files.createTempDirectory("mac-update-rollback-test-").toFile()
        try {
            val dmgFile = File(tempDir, "database-copier-1.3.0.dmg").apply { writeText("fake-dmg") }
            val targetApp = File(tempDir, "Applications/database-copier.app").apply { mkdirs() }
            val backupApp = File(tempDir, "Applications/database-copier.app.old")

            // Случай 1: исключение при вызове ditto
            val executedOnDittoException = mutableListOf<List<String>>()
            assertThrows(IOException::class.java) {
                AppUpdater.installMacDmgAndRestart(
                    dmgFile = dmgFile,
                    targetAppBundle = targetApp,
                    commandRunner = { cmd ->
                        executedOnDittoException.add(cmd)
                        when {
                            cmd.take(2) == listOf("hdiutil", "attach") -> {
                                val mountPointPath = cmd[cmd.indexOf("-mountpoint") + 1]
                                File(mountPointPath, "database-copier.app").mkdirs()
                                AppUpdater.CommandResult(exitCode = 0, output = "")
                            }
                            cmd.firstOrNull() == "ditto" -> throw IOException("Process execution interrupted")
                            else -> AppUpdater.CommandResult(exitCode = 0, output = "")
                        }
                    },
                    relauncher = {},
                )
            }
            assertTrue(executedOnDittoException.contains(listOf("mv", backupApp.absolutePath, targetApp.absolutePath)))

            // Случай 2: ошибка при перезапуске нового .app
            val executedOnRelaunchFailure = mutableListOf<List<String>>()
            assertThrows(IOException::class.java) {
                AppUpdater.installMacDmgAndRestart(
                    dmgFile = dmgFile,
                    targetAppBundle = targetApp,
                    commandRunner = { cmd ->
                        executedOnRelaunchFailure.add(cmd)
                        if (cmd.take(2) == listOf("hdiutil", "attach")) {
                            val mountPointPath = cmd[cmd.indexOf("-mountpoint") + 1]
                            File(mountPointPath, "database-copier.app").mkdirs()
                        }
                        AppUpdater.CommandResult(exitCode = 0, output = "")
                    },
                    relauncher = {
                        throw IOException("open -n failed")
                    },
                )
            }
            assertTrue(executedOnRelaunchFailure.contains(listOf("mv", backupApp.absolutePath, targetApp.absolutePath)))
            assertFalse(executedOnRelaunchFailure.contains(listOf("rm", "-rf", backupApp.absolutePath)))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `macOS DMG update reports explicit error if rollback of backup also fails`() {
        val tempDir = Files.createTempDirectory("mac-update-rollback-fail-test-").toFile()
        try {
            val dmgFile = File(tempDir, "database-copier-1.3.0.dmg").apply { writeText("fake-dmg") }
            val targetApp = File(tempDir, "Applications/database-copier.app").apply { mkdirs() }
            val backupApp = File(tempDir, "Applications/database-copier.app.old")

            val ex = assertThrows(IOException::class.java) {
                AppUpdater.installMacDmgAndRestart(
                    dmgFile = dmgFile,
                    targetAppBundle = targetApp,
                    commandRunner = { cmd ->
                        when {
                            cmd.take(2) == listOf("hdiutil", "attach") -> {
                                val mountPointPath = cmd[cmd.indexOf("-mountpoint") + 1]
                                File(mountPointPath, "database-copier.app").mkdirs()
                                AppUpdater.CommandResult(exitCode = 0, output = "")
                            }
                            cmd.firstOrNull() == "ditto" -> {
                                AppUpdater.CommandResult(exitCode = 1, output = "Disk I/O error")
                            }
                            cmd == listOf("mv", backupApp.absolutePath, targetApp.absolutePath) -> {
                                AppUpdater.CommandResult(exitCode = 1, output = "Permission denied")
                            }
                            else -> AppUpdater.CommandResult(exitCode = 0, output = "")
                        }
                    },
                    relauncher = {},
                )
            }

            assertTrue(ex.message!!.contains(backupApp.absolutePath))
            assertTrue(ex.message!!.contains("Permission denied"))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `Linux AppImage update replaces existing file, keeps backup until relaunch, and rolls back if relaunch fails`() {
        val tempDir = Files.createTempDirectory("linux-update-test-").toFile()
        try {
            val targetAppImage = File(tempDir, "database-copier.AppImage").apply { writeText("old-binary") }
            val downloadedAppImage = File(tempDir, "downloaded.AppImage").apply { writeText("new-binary") }
            val backupAppImage = File(tempDir, "database-copier.AppImage.old")

            // Сначала проверяем откат при сбое перезапуска
            assertThrows(IOException::class.java) {
                AppUpdater.installLinuxAppImageAndRestart(
                    downloadedFile = downloadedAppImage,
                    targetFile = targetAppImage,
                    relauncher = {
                        assertTrue(backupAppImage.exists(), "Backup .old must still exist during relaunch")
                        throw IOException("Failed to start new AppImage")
                    },
                )
            }
            assertEquals("old-binary", targetAppImage.readText(), "Old binary must be restored on relaunch failure")
            assertFalse(backupAppImage.exists())

            // Теперь успешное обновление
            var relaunchedFile: File? = null
            AppUpdater.installLinuxAppImageAndRestart(
                downloadedFile = downloadedAppImage,
                targetFile = targetAppImage,
                relauncher = {
                    assertTrue(backupAppImage.exists(), "Backup .old must still exist until relaunch completes")
                    relaunchedFile = it
                },
            )

            assertEquals("new-binary", targetAppImage.readText())
            assertTrue(targetAppImage.canExecute())
            assertFalse(backupAppImage.exists(), "Backup .old file should be removed after successful relaunch")
            assertFalse(downloadedAppImage.exists(), "Downloaded staging file should be removed")
            assertEquals(targetAppImage, relaunchedFile)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `resolveLinuxAppImageFile accepts renamed AppImage with ELF AI header and rejects plain binaries`() {
        assertThrows(IOException::class.java) {
            AppUpdater.resolveLinuxAppImageFile(appImageEnv = null)
        }

        val tempDir = Files.createTempDirectory("linux-appimage-resolve-test-").toFile()
        try {
            // Обычный ELF-лаунчер (без сигнатуры 'AI' на смещении 8) должен отклоняться
            val plainElfBinary = File(tempDir, "database-copier").apply {
                writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1, 0, 0, 0, 0))
            }
            assertThrows(IOException::class.java) {
                AppUpdater.resolveLinuxAppImageFile(appImageEnv = plainElfBinary.absolutePath)
            }

            // Переименованный пользователем AppImage без расширения .AppImage, но с сигнатурой ELF + 'AI\x02'
            val renamedAppImage = File(tempDir, "my-db-copier").apply {
                writeBytes(
                    byteArrayOf(
                        0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(),
                        2, 1, 1, 0,
                        'A'.code.toByte(), 'I'.code.toByte(), 2,
                    )
                )
            }
            assertEquals(renamedAppImage, AppUpdater.resolveLinuxAppImageFile(appImageEnv = renamedAppImage.absolutePath))

            // Файл внутри смонтированного APPDIR должен отклоняться
            assertThrows(IOException::class.java) {
                AppUpdater.resolveLinuxAppImageFile(
                    appImageEnv = renamedAppImage.absolutePath,
                    appDirEnv = tempDir.absolutePath,
                )
            }
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
