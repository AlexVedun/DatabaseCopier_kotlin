package com.example.databasecopier

import java.io.File

fun getAppDataDir(): File {
    val isWindows = System.getProperty("os.name").lowercase().contains("win")
    val dir = if (isWindows) {
        File(System.getenv("APPDATA") ?: (System.getProperty("user.home") + "/AppData/Roaming"), "database-copier")
    } else {
        File(System.getProperty("user.home"), ".database-copier")
    }
    if (!dir.exists()) {
        dir.mkdirs()
    }
    return dir
}
