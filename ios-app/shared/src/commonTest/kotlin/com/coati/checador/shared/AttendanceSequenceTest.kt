package com.coati.checador.shared

import com.coati.checador.shared.app.AttendanceSequence
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttendanceSequenceTest {
    @Test
    fun allowedEventsFollowTheWorkdaySequence() {
        assertEquals(listOf("CLOCK_IN"), AttendanceSequence.allowedAfter(emptyList()))
        assertEquals(listOf("MEAL_START", "CLOCK_OUT"), AttendanceSequence.allowedAfter(listOf("CLOCK_IN")))
        assertEquals(listOf("MEAL_END"), AttendanceSequence.allowedAfter(listOf("CLOCK_IN", "MEAL_START")))
        assertEquals(
            listOf("MEAL_START", "CLOCK_OUT"),
            AttendanceSequence.allowedAfter(listOf("CLOCK_IN", "MEAL_START", "MEAL_END"))
        )
        assertEquals(
            listOf("MEAL_START", "CLOCK_OUT"),
            AttendanceSequence.allowedAfter(listOf("CLOCK_IN", "MEAL_START", "MEAL_END", "MEAL_START", "MEAL_END"))
        )
        assertTrue(AttendanceSequence.allowedAfter(listOf("CLOCK_IN", "CLOCK_OUT")).isEmpty())
        assertTrue(AttendanceSequence.allowedAfter(listOf("CLOCK_OUT", "MEAL_END")).isEmpty())
        assertTrue(AttendanceSequence.allowedAfter(listOf("UNKNOWN")).isEmpty())
    }

    @Test
    fun dayBoundsUseMexicoCityRatherThanUtc() {
        val beforeMidnight = Instant.parse("2026-11-04T05:59:59Z").toEpochMilliseconds()
        val afterMidnight = Instant.parse("2026-11-04T06:00:00Z").toEpochMilliseconds()
        val previousDay = AttendanceSequence.dayBounds(beforeMidnight)
        val currentDay = AttendanceSequence.dayBounds(afterMidnight)
        assertTrue(beforeMidnight in previousDay)
        assertFalse(afterMidnight in previousDay)
        assertTrue(afterMidnight in currentDay)
        assertEquals(previousDay.last + 1, currentDay.first)
    }
}
