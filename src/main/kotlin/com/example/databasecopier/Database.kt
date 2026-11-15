package com.example.databasecopier

import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import java.io.File

fun initAppDatabase(): Database {
    val dbFile = File(getAppDataDir(), "app.sqlite")
    val database = Database.connect("jdbc:sqlite:${dbFile.absolutePath}", driver = "org.sqlite.JDBC")

    transaction(database) {
        SchemaUtils.createMissingTablesAndColumns(Connections, CopySessions, CopySessionTables, CopySessionViews, CopySessionRoutines)
    }

    return database
}
