package com.example.databasecopier.connection

import com.example.databasecopier.Connections
import com.example.databasecopier.adapter.ConnectionConfig
import com.example.databasecopier.adapter.DbType
import com.example.databasecopier.security.CredentialCipher
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction

object ConnectionRepository {

    fun save(name: String, config: ConnectionConfig): Int = transaction {
        (Connections.insert {
            it[Connections.name] = name
            it[type] = config.type.name.lowercase()
            it[host] = config.host
            it[port] = config.port
            it[database] = config.database
            it[username] = config.username
            it[passwordEncrypted] = config.password?.let { pw -> CredentialCipher.encrypt(pw) }
        } get Connections.id)
    }

    // Значения для автодополнения полей формы подключения (Шаг: "запоминание вводимых
    // параметров") — переиспользуем уже существующую таблицу Connections (в неё пишется запись
    // при каждой успешной проверке подключения), отдельного хранилища истории не заводим.
    fun distinctHosts(): List<String> = transaction {
        Connections.selectAll().mapNotNull { it[Connections.host] }.filter { it.isNotBlank() }.distinct()
    }

    fun distinctPorts(): List<String> = transaction {
        Connections.selectAll().mapNotNull { it[Connections.port] }.map { it.toString() }.distinct()
    }

    fun distinctDatabases(): List<String> = transaction {
        Connections.selectAll().mapNotNull { it[Connections.database] }.filter { it.isNotBlank() }.distinct()
    }

    fun distinctUsernames(): List<String> = transaction {
        Connections.selectAll().mapNotNull { it[Connections.username] }.filter { it.isNotBlank() }.distinct()
    }

    fun load(id: Int): ConnectionConfig? = transaction {
        Connections.select { Connections.id eq id }.map {
            ConnectionConfig(
                type = DbType.valueOf(it[Connections.type].uppercase()),
                host = it[Connections.host],
                port = it[Connections.port],
                database = it[Connections.database] ?: "",
                username = it[Connections.username],
                password = it[Connections.passwordEncrypted]?.let { enc -> CredentialCipher.decrypt(enc) },
            )
        }.firstOrNull()
    }
}
