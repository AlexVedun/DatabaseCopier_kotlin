package com.example.databasecopier

import java.util.Properties

object AppInfo {
    const val NAME = "Database Copier"

    val version: String by lazy {
        val properties = Properties()
        val stream = requireNotNull(AppInfo::class.java.getResourceAsStream("/database-copier.properties")) {
            "Application metadata resource is missing"
        }
        stream.use(properties::load)
        requireNotNull(properties.getProperty("version")?.takeIf(String::isNotBlank)) {
            "Application version is missing from metadata"
        }
    }

    val windowTitle: String
        get() = "$NAME - $version"
}
