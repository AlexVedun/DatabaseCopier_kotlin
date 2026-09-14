package com.example.databasecopier.connection

import com.example.databasecopier.Connections
import com.example.databasecopier.CopySessions
import com.example.databasecopier.adapter.ConnectionConfig
import com.example.databasecopier.adapter.DbType
import com.example.databasecopier.security.CredentialCipher
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.jetbrains.exposed.sql.update

data class ConnectionRecord(val id: Int, val name: String, val config: ConnectionConfig)

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

    fun update(id: Int, name: String, config: ConnectionConfig) = transaction {
        Connections.update({ Connections.id eq id }) {
            it[Connections.name] = name
            it[type] = config.type.name.lowercase()
            it[host] = config.host
            it[port] = config.port
            it[database] = config.database
            it[username] = config.username
            it[passwordEncrypted] = config.password?.let { pw -> CredentialCipher.encrypt(pw) }
        }
    }

    fun delete(id: Int) = transaction {
        Connections.deleteWhere { Connections.id eq id }
    }

    fun list(): List<ConnectionRecord> = transaction {
        Connections.selectAll().orderBy(Connections.name to SortOrder.ASC).map { it.toRecord() }
    }

    fun isNameTaken(name: String, excludingId: Int? = null): Boolean = transaction {
        val condition = if (excludingId != null) {
            (Connections.name eq name) and (Connections.id neq excludingId)
        } else {
            Connections.name eq name
        }
        !Connections.select { condition }.empty()
    }

    // Сколько сохранённых сессий (как источник или как приёмник) ссылаются на это подключение —
    // используется, чтобы предупредить перед удалением ("подключение используется в N сессиях"),
    // а не удалить его молча и оставить эти сессии невозобновляемыми (FK в SQLite не форсится).
    fun sessionsUsingCount(connectionId: Int): Int = transaction {
        CopySessions.select {
            (CopySessions.sourceConnectionId eq connectionId) or (CopySessions.targetConnectionId eq connectionId)
        }.count().toInt()
    }

    fun load(id: Int): ConnectionConfig? = transaction {
        Connections.select { Connections.id eq id }.map { it.toConfig() }.firstOrNull()
    }

    private fun ResultRow.toConfig() = ConnectionConfig(
        type = DbType.valueOf(this[Connections.type].uppercase()),
        host = this[Connections.host],
        port = this[Connections.port],
        database = this[Connections.database] ?: "",
        username = this[Connections.username],
        password = this[Connections.passwordEncrypted]?.let { enc -> CredentialCipher.decrypt(enc) },
    )

    private fun ResultRow.toRecord() = ConnectionRecord(
        id = this[Connections.id],
        name = this[Connections.name],
        config = toConfig(),
    )
}
