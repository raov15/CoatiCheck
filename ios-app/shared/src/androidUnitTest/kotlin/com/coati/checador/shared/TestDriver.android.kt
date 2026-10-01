package com.coati.checador.shared

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.coati.checador.shared.data.db.CoatiDatabase

actual fun createTestDriver(): SqlDriver {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    CoatiDatabase.Schema.create(driver)
    driver.execute(null, "PRAGMA foreign_keys=ON", 0)
    return driver
}
