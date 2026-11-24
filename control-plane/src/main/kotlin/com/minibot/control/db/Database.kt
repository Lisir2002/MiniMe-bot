package com.minibot.control.db

import com.minibot.control.config.AppConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway

class Database(config: AppConfig) : AutoCloseable {
    private val hikariConfig = HikariConfig().apply {
        jdbcUrl = config.databaseUrl
        username = config.databaseUser
        password = config.databasePassword
        driverClassName = "org.postgresql.Driver"
        maximumPoolSize = 10
        minimumIdle = 1
        connectionTimeout = 5_000
        validationTimeout = 3_000
        isAutoCommit = true
        poolName = "minibot-control"
    }

    val dataSource: HikariDataSource = HikariDataSource(hikariConfig)

    fun migrate() {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
            .migrate()
    }

    override fun close() {
        dataSource.close()
    }
}
