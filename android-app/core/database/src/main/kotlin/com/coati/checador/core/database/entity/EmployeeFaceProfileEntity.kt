package com.coati.checador.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "employee_face_profiles",
    foreignKeys = [
        ForeignKey(
            entity = EmployeeEntity::class,
            parentColumns = ["id_local"],
            childColumns = ["employee_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["employee_id", "is_active"])
    ]
)
data class EmployeeFaceProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,                         // UUID

    @ColumnInfo(name = "employee_id")
    val employeeId: String,                 // FK → employees.id_local

    @ColumnInfo(name = "embedding_blob", typeAffinity = ColumnInfo.BLOB)
    val embeddingBlob: ByteArray,           // float32 vector cifrado con AES-256-GCM

    @ColumnInfo(name = "model_version")
    val modelVersion: String,               // ej: "mobilefacenet_v1"

    @ColumnInfo(name = "quality_score")
    val qualityScore: Float? = null,        // 0.0 – 1.0

    @ColumnInfo(name = "created_at")
    val createdAt: Long,                    // epoch ms UTC

    @ColumnInfo(name = "is_active")
    val isActive: Boolean = true
) {
    // ByteArray necesita equals/hashCode manual para data class
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmployeeFaceProfileEntity) return false
        return id == other.id &&
            employeeId == other.employeeId &&
            embeddingBlob.contentEquals(other.embeddingBlob) &&
            modelVersion == other.modelVersion &&
            qualityScore == other.qualityScore &&
            createdAt == other.createdAt &&
            isActive == other.isActive
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + employeeId.hashCode()
        result = 31 * result + embeddingBlob.contentHashCode()
        result = 31 * result + modelVersion.hashCode()
        result = 31 * result + (qualityScore?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + isActive.hashCode()
        return result
    }
}
