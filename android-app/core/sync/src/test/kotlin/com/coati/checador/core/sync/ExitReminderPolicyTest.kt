package com.coati.checador.core.sync
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
class ExitReminderPolicyTest {
 private val zone=ZoneId.of("America/Mexico_City")
 @Test fun disabledOvertimeUsesScheduledExit(){val day=LocalDate.of(2026,11,12);assertEquals(day.atTime(18,0).atZone(zone).toInstant().toEpochMilli(),ExitReminderScheduler.deadline(day,"09:00|18:00|false"))}
 @Test fun allowedOvertimeDoesNotClose(){assertNull(ExitReminderScheduler.deadline(LocalDate.of(2026,11,12),"09:00|18:00|true"))}
 @Test fun overnightExitIsNextDay(){val day=LocalDate.of(2026,11,12);assertEquals(day.plusDays(1).atTime(6,0).atZone(zone).toInstant().toEpochMilli(),ExitReminderScheduler.deadline(day,"22:00|06:00|false"))}
 @Test fun missingOrInvalidScheduleDoesNotClose(){val day=LocalDate.of(2026,11,12);assertNull(ExitReminderScheduler.deadline(day,null));assertNull(ExitReminderScheduler.deadline(day,"bad|18:00|false"))}
}
