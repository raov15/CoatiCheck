package com.coati.checador.core.sync

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.coati.checador.core.database.dao.AppSettingDao
import com.coati.checador.core.database.dao.AttendanceRecordDao
import com.coati.checador.core.database.dao.EmployeeDao
import com.coati.checador.core.database.entity.AppSettingEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.time.*
import java.util.concurrent.TimeUnit

object ExitReminderScheduler {
    private val zone = ZoneId.of("America/Mexico_City")
    fun deadline(day: LocalDate, value: String?): Long? {
        val parts=value?.split('|')?:return null
        if(parts.size!=3||parts[2]!="false")return null
        return runCatching { val start=LocalTime.parse(parts[0]);val end=LocalTime.parse(parts[1]);
            day.plusDays(if(end<=start)1 else 0).atTime(end).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }
    suspend fun refresh(context: Context, records: AttendanceRecordDao, settings: AppSettingDao, employeeId: String): Boolean {
        val today=Instant.now().atZone(zone).toLocalDate();var closedToday=false
        for(day in listOf(today.minusDays(1),today)) {
            val prefix="nexora_exit_${employeeId}_$day"
            val policy=settings.getValue("nexora.exit.policy.$employeeId.${day.dayOfWeek.value}")
            val end=deadline(day,policy)
            val marks=records.getByEmployeeAndDateRange(employeeId,day.atStartOfDay(zone).toInstant().toEpochMilli(),day.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()-1)
            val entry=marks.firstOrNull { it.eventType=="CLOCK_IN" && Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate()==day }
            val exited=entry!=null&&marks.any { it.eventType=="CLOCK_OUT"&&it.occurredAt>=entry.occurredAt }
            if(end==null||entry==null||exited) { WorkManager.getInstance(context).cancelAllWorkByTag(prefix);if(exited)(context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel((employeeId+day).hashCode());continue }
            if(System.currentTimeMillis()>=end+3600000L) {
                settings.upsert(AppSettingEntity("nexora.exit.closed.$employeeId.$day","true",System.currentTimeMillis()))
                if(day==today)closedToday=true
            }
            for(slot in 1..4) {
                val request=OneTimeWorkRequestBuilder<ExitReminderWorker>().setInputData(workDataOf("employee" to employeeId,"day" to day.toString(),"end" to end,"slot" to slot))
                    .setInitialDelay(maxOf(0L,end+slot*900000L-System.currentTimeMillis()),TimeUnit.MILLISECONDS).addTag(prefix).build()
                WorkManager.getInstance(context).enqueueUniqueWork("${prefix}_$slot",ExistingWorkPolicy.KEEP,request)
            }
        }
        return closedToday
    }
}

@HiltWorker
class ExitReminderWorker @AssistedInject constructor(
    @Assisted context: Context,@Assisted params: WorkerParameters,
    private val records: AttendanceRecordDao,private val settings: AppSettingDao,private val employees: EmployeeDao
): CoroutineWorker(context,params) {
    override suspend fun doWork():Result {
        val id=inputData.getString("employee")?:return Result.success()
        val day=runCatching { LocalDate.parse(inputData.getString("day")) }.getOrNull()?:return Result.success()
        val end=inputData.getLong("end",0L);val slot=inputData.getInt("slot",0)
        val current=ExitReminderScheduler.deadline(day,settings.getValue("nexora.exit.policy.$id.${day.dayOfWeek.value}"))
        if(current!=end)return Result.success()
        val zone=ZoneId.of("America/Mexico_City")
        val marks=records.getByEmployeeAndDateRange(id,day.atStartOfDay(zone).toInstant().toEpochMilli(),day.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()-1)
        val entry=marks.firstOrNull { it.eventType=="CLOCK_IN"&&Instant.ofEpochMilli(it.occurredAt).atZone(zone).toLocalDate()==day }?:return Result.success()
        if(marks.any { it.eventType=="CLOCK_OUT"&&it.occurredAt>=entry.occurredAt })return Result.success()
        val now=System.currentTimeMillis();val closing=now>=end+3600000L
        if(!closing && slot<((now-end)/900000L).toInt())return Result.success()
        val key="nexora.exit.notified.$id.$day.${if(closing)4 else slot}"
        if(settings.getValue(key)=="true")return Result.success()
        if(closing){settings.upsert(AppSettingEntity("nexora.exit.closed.$id.$day","true",now));applicationContext.stopService(android.content.Intent().setClassName(applicationContext,"com.coati.checador.feature.attendance.ForeignLocationService"))}
        val manager=applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if(Build.VERSION.SDK_INT>=26)manager.createNotificationChannel(NotificationChannel("nexora_exit","Recordatorios de salida",NotificationManager.IMPORTANCE_HIGH))
        if(Build.VERSION.SDK_INT<33||ContextCompat.checkSelfPermission(applicationContext,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED) {
            val name=employees.findById(id)?.fullName?:"Trabajador"
            val text=if(closing)"No marcó salida. Jornada cerrada automáticamente; queda pendiente de revisión." else "No olvides marcar tu salida. Han pasado ${(now-end)/60000} minutos desde tu horario de salida."
            manager.notify((id+day).hashCode(),NotificationCompat.Builder(applicationContext,"nexora_exit").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("NEXORA · $name").setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text)).setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build())
            settings.upsert(AppSettingEntity(key,"true",now))
        }
        return Result.success()
    }
}
