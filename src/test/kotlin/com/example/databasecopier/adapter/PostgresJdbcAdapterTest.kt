package com.example.databasecopier.adapter

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class PostgresJdbcAdapterTest : JdbcAdapterTestBase() {

    companion object {
        @Container
        @JvmStatic
        val container: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:16")
    }

    override fun config() = ConnectionConfig(
        type = DbType.POSTGRESQL,
        host = container.host,
        port = container.getMappedPort(5432),
        database = container.databaseName,
        username = container.username,
        password = container.password,
    )

    override fun rawJdbcUrl(): String = container.jdbcUrl
}
