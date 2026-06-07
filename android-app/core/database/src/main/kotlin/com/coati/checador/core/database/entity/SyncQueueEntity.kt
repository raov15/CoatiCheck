package com.coati.checador.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "sync_queue",
    indices = [
        Index(value = ["next_retry_at", "attempts"])
    ]
)
data class SyncQueueEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,                         // UUID

    @ColumnInfo(name = "entity_type")
    val entityType: String,                 // attendance_record | employee | face_profile_meta

    @ColumnInfo(name = "entity_id")
    val entityId: String,

    @ColumnInfo(name = "operation")
    val operation: String,                  // INSERT | UPDATE | DELETE

    @ColumnInfo(name = "payload_hash")
    val payloadHash: String? = null,        // SHA-256 del payload para detectar duplicados

    @ColumnInfo(name = "attempts")
    val attempts: Int = 0,

    @ColumnInfo(name = "next_retry_at")
    val nextRetryAt: Long? = null,          // epoch ms de próximo intento

    @ColumnInfo(name = "last_error")
    val lastError: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long                     // epoch ms UTC
) {
    companion object {
        const val ENTITY_ATTENDANCE = "attendance_record"
        const val ENTITY_EMPLOYEE = "employee"
        const val ENTITY_FACE_PROFILE_META = "face_profile_meta"

        const val OP_INSERT = "INSERT"
        const val OP_UPDATE = "UPDATE"
        const val OP_DELETE = "DELETE"
    }
}
