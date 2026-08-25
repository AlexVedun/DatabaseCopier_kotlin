package com.example.databasecopier.adapter

import org.testcontainers.containers.MSSQLServerContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@Testcontainers
class MssqlJdbcAdapterTest : JdbcAdapterTestBase() {

    companion object {
        @Container
        @JvmStatic
        val container: MSSQLServerContainer<*> = MSSQLServerContainer("mcr.microsoft.com/mssql/server:2022-latest")
            .acceptLicense()
    }

    override fun config() = ConnectionConfig(
        type = DbType.SQLSERVER,
        host = container.host,
        port = container.getMappedPort(1433),
        database = "master",
        username = container.username,
        password = container.password,
    )

    override fun rawJdbcUrl(): String = "jdbc:sqlserver://${container.host}:${container.getMappedPort(1433)};" +
        "databaseName=master;encrypt=true;trustServerCertificate=true"
}
