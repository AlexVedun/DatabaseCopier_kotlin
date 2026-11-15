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
    fun `macOS DMG update renames old bundle until copy succeeds, then removes backup and relaunches`() {
        val tempDir = Files.createTempDirectory("mac-update-test-").toFile()
        try {
            val dmgFile = File(tempDir, "database-copier-1.3.0.dmg").apply { writeText("fake-dmg") }
            val targetApp = File(tempDir, "Applications/database-copier.app").apply { mkdirs() }
            val backupApp = File(tempDir, "Applications/database-copier.app.old")

            val executedCommands = mutableListOf<List<String>>()
            var relaunchedBundle: File? = null

            AppUpdater.installMacDmgAndRestart(
                dmgFile = dmgFile,
                targetAppBundle = targetApp,
                commandRunner = { cmd ->
                    executedCommands.add(cmd)
                    if (cmd.take(2) == listOf("hdiutil", "attach")) {
                        val mountPointPath = cmd[cmd.indexOf("-mountpoint") + 1]
                        File(mountPointPath, "database-copier.app").mkdirs()
                    }
                    AppUpdater.CommandResult(exitCode = 0, output = "")
                },
                relauncher = { app ->
                    relaunchedBundle = app
                },
            )

            assertEquals(6, executedCommands.size)
            assertEquals(listOf("hdiutil", "attach", "-nobrowse", "-readonly", "-mountpoint"), executedCommands[0].take(5))
            assertEquals(dmgFile.absolutePath, executedCommands[0].last())

            // 1. Старый бандл переименовывается в .old
            assertEquals(listOf("mv", targetApp.absolutePath, backupApp.absolutePath), executedCommands[1])

            // 2. Новый бандл копируется в целевой путь (пока старый хранится в .old)
            assertEquals("ditto", executedCommands[2][0])
            assertTrue(executedCommands[2][1].endsWith("/database-copier.app"))
            assertEquals(targetApp.absolutePath, executedCommands[2][2])

            // 3. Только после успешного копирования удаляется .old
            assertEquals(listOf("rm", "-rf", backupApp.absolutePath), executedCommands[3])

            assertEquals(listOf("xattr", "-cr", targetApp.absolutePath), executedCommands[4])
            assertEquals(listOf("hdiutil", "detach"), executedCommands[5].take(2))
            assertEquals("-quiet", executedCommands[5].last())

            assertEquals(targetApp, relaunchedBundle)
            assertFalse(dmgFile.exists(), "Temporary DMG file should be deleted after update")
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `macOS DMG update restores renamed backup when copying new bundle fails`() {
        val tempDir = Files.createTempDirectory("mac-update-rollback-test-").toFile()
        try {
            val dmgFile = File(tempDir, "database-copier-1.3.0.dmg").apply { writeText("fake-dmg") }
            val targetApp = File(tempDir, "Applications/database-copier.app").apply { mkdirs() }
            val backupApp = File(tempDir, "Applications/database-copier.app.old")

            val executedCommands = mutableListOf<List<String>>()
            var relaunchedBundle: File? = null

            assertThrows(IOException::class.java) {
                AppUpdater.installMacDmgAndRestart(
                    dmgFile = dmgFile,
                    targetAppBundle = targetApp,
                    commandRunner = { cmd ->
                        executedCommands.add(cmd)
                        when {
                            cmd.take(2) == listOf("hdiutil", "attach") -> {
                                val mountPointPath = cmd[cmd.indexOf("-mountpoint") + 1]
                                File(mountPointPath, "database-copier.app").mkdirs()
                                AppUpdater.CommandResult(exitCode = 0, output = "")
                            }
                            cmd.firstOrNull() == "ditto" -> {
                                AppUpdater.CommandResult(exitCode = 1, output = "No space left on device")
                            }
                            else -> AppUpdater.CommandResult(exitCode = 0, output = "")
                        }
                    },
                    relauncher = { app ->
                        relaunchedBundle = app
                    },
                )
            }

            // При ошибке ditto частично скопированная целевая папка очищается, а старая версия возвращается из .old
            assertTrue(executedCommands.contains(listOf("mv", targetApp.absolutePath, backupApp.absolutePath)))
            assertTrue(executedCommands.contains(listOf("rm", "-rf", targetApp.absolutePath)))
            assertTrue(executedCommands.contains(listOf("mv", backupApp.absolutePath, targetApp.absolutePath)))
            assertNull(relaunchedBundle)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun `Linux AppImage update replaces existing file via backup and deletes backup on success`() {
        val tempDir = Files.createTempDirectory("linux-update-test-").toFile()
        try {
            val targetAppImage = File(tempDir, "database-copier.AppImage").apply { writeText("old-binary") }
            val downloadedAppImage = File(tempDir, "downloaded.AppImage").apply { writeText("new-binary") }
            val backupAppImage = File(tempDir, "database-copier.AppImage.old")

            var relaunchedFile: File? = null
            AppUpdater.installLinuxAppImageAndRestart(
                downloadedFile = downloadedAppImage,
                targetFile = targetAppImage,
                relauncher = { relaunchedFile = it },
            )

            assertEquals("new-binary", targetAppImage.readText())
            assertTrue(targetAppImage.canExecute())
            assertFalse(backupAppImage.exists(), "Backup .old file should be removed after successful replacement")
            assertFalse(downloadedAppImage.exists(), "Downloaded staging file should be removed")
            assertEquals(targetAppImage, relaunchedFile)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
