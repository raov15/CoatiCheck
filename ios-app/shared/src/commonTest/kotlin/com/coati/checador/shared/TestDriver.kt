package com.coati.checador.shared

import app.cash.sqldelight.db.SqlDriver

/** Crea un driver SQLite en memoria para tests, con foreign keys activadas. */
expect fun createTestDriver(): SqlDriver
