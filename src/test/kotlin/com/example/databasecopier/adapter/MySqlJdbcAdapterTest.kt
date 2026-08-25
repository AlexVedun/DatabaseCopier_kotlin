package com.example.databasecopier.adapter

import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class MySqlJdbcAdapterTest : JdbcAdapterTestBase() {

    companion object {
        @Container
        @JvmStatic
        val container: MySQLContainer<*> = MySQLContainer("mysql:8.0")
    }

    override fun config() = ConnectionConfig(
        type = DbType.MYSQL,
        host = container.host,
        port = container.getMappedPort(3306),
        database = container.databaseName,
        username = container.username,
        password = container.password,
    )

    override fun rawJdbcUrl(): String = container.jdbcUrl
}
