package com.coati.checador.shared

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.coati.checador.shared.data.db.CoatiDatabase
import kotlin.random.Random

actual fun createTestDriver(): SqlDriver {
    val driver = NativeSqliteDriver(
        CoatiDatabase.Schema,
        "coati-test-${Random.nextLong()}.db"
    )
    driver.execute(null, "PRAGMA foreign_keys=ON", 0)
    return driver
}
