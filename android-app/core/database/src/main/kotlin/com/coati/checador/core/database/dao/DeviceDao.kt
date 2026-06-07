package com.coati.checador.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.coati.checador.core.database.entity.DeviceEntity

@Dao
interface DeviceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplace(device: DeviceEntity)

    // El dispositivo kiosk tiene solo un registro
    @Query("SELECT * FROM devices LIMIT 1")
    suspend fun getCurrent(): DeviceEntity?

    @Query("SELECT * FROM devices WHERE id_local = :idLocal")
    suspend fun findById(idLocal: String): DeviceEntity?

    @Query("""
        UPDATE devices
        SET id_remote = :idRemote, auth_token = :authToken
        WHERE id_local = :idLocal
    """)
    suspend fun updateRegistration(idLocal: String, idRemote: String, authToken: String)

    @Query("UPDATE devices SET auth_token = :token WHERE id_local = :idLocal")
    suspend fun updateAuthToken(idLocal: String, token: String)

    @Query("UPDATE devices SET site_id = :siteId WHERE id_local = :idLocal")
    suspend fun updateSiteId(idLocal: String, siteId: String)
}
