package com.example.databasecopier.dump

import com.example.databasecopier.adapter.DbType

enum class DumpDialect {
    MYSQL,
    POSTGRESQL;

    /** Диалект дампа переиспользует ту же таблицу маппинга типов, что и живой JDBC-адаптер той же СУБД. */
    fun toDbType(): DbType = when (this) {
        MYSQL -> DbType.MYSQL
        POSTGRESQL -> DbType.POSTGRESQL
    }
}
