package com.coati.checador.shared.app

import kotlinx.datetime.DatePeriod
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

internal object AttendanceSequence {
    fun allowedAfter(events: List<String>): List<String> {
        if ("CLOCK_OUT" in events) return emptyList()
        return when (events.lastOrNull()) {
            null -> listOf("CLOCK_IN")
            "CLOCK_IN", "MEAL_END" -> if (events.count { it == "MEAL_START" } < 2) {
                listOf("MEAL_START", "CLOCK_OUT")
            } else {
                listOf("CLOCK_OUT")
            }
            "MEAL_START" -> listOf("MEAL_END")
            else -> emptyList()
        }
    }

    fun dayBounds(timestamp: Long): LongRange {
        val zone = TimeZone.of("America/Mexico_City")
        val day = Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(zone).date
        val start = day.atStartOfDayIn(zone).toEpochMilliseconds()
        val end = day.plus(DatePeriod(days = 1)).atStartOfDayIn(zone).toEpochMilliseconds()
        return start until end
    }
}
