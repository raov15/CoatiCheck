package com.coati.checador.core.database.di

import android.content.Context
import androidx.room.Room
import com.coati.checador.core.common.Constants
import com.coati.checador.core.database.CoatiDatabase
import com.coati.checador.core.database.dao.AppSettingDao
import com.coati.checador.core.database.dao.AttendanceRecordDao
import com.coati.checador.core.database.dao.DeviceDao
import com.coati.checador.core.database.dao.EmployeeDao
import com.coati.checador.core.database.dao.EmployeeFaceProfileDao
import com.coati.checador.core.database.dao.SyncQueueDao
import com.coati.checador.core.database.util.DatabasePassphraseManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SupportFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideCoatiDatabase(
        @ApplicationContext context: Context,
        passphraseManager: DatabasePassphraseManager
    ): CoatiDatabase {
        val passphrase = passphraseManager.getOrCreatePassphrase()
        val factory = SupportFactory(passphrase)

        return Room.databaseBuilder(
            context,
            CoatiDatabase::class.java,
            Constants.DATABASE_NAME
        )
            .openHelperFactory(factory)
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    fun provideEmployeeDao(db: CoatiDatabase): EmployeeDao = db.employeeDao()

    @Provides
    fun provideEmployeeFaceProfileDao(db: CoatiDatabase): EmployeeFaceProfileDao =
        db.employeeFaceProfileDao()

    @Provides
    fun provideAttendanceRecordDao(db: CoatiDatabase): AttendanceRecordDao =
        db.attendanceRecordDao()

    @Provides
    fun provideSyncQueueDao(db: CoatiDatabase): SyncQueueDao = db.syncQueueDao()

    @Provides
    fun provideAppSettingDao(db: CoatiDatabase): AppSettingDao = db.appSettingDao()

    @Provides
    fun provideDeviceDao(db: CoatiDatabase): DeviceDao = db.deviceDao()
}
