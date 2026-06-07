package com.coati.checador.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coati.checador.core.database.entity.AppSettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppSettingDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(setting: AppSettingEntity)

    @Query("SELECT * FROM app_settings WHERE `key` = :key")
    suspend fun findByKey(key: String): AppSettingEntity?

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun getValue(key: String): String?

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    fun observeValue(key: String): Flow<String?>

    @Query("UPDATE app_settings SET value = :value, updated_at = :updatedAt WHERE `key` = :key")
    suspend fun updateValue(key: String, value: String?, updatedAt: Long)

    @Query("SELECT * FROM app_settings")
    suspend fun getAll(): List<AppSettingEntity>
}
