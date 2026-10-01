package com.coati.checador.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "employee_work_sites",
    primaryKeys = ["employee_id", "weekday"],
    foreignKeys = [
        ForeignKey(
            entity = EmployeeEntity::class,
            parentColumns = ["id_local"],
            childColumns = ["employee_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["employee_id"]),
        Index(value = ["work_mode"])
    ]
)
data class EmployeeWorkSiteEntity(

    @ColumnInfo(name = "employee_id")
    val employeeId: String,

    @ColumnInfo(name = "weekday")
    val weekday: Int,

    @ColumnInfo(name = "work_mode")
    val workMode: String,

    @ColumnInfo(name = "site_id")
    val siteId: String?,

    @ColumnInfo(name = "site_name")
    val siteName: String? = null,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)