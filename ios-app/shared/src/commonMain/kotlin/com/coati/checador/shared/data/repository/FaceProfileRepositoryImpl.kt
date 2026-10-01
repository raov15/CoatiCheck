package com.coati.checador.shared.data.repository

import com.coati.checador.shared.data.db.CoatiCheckDatabase
import com.coati.checador.shared.domain.model.EnrolledFaceProfile
import com.coati.checador.shared.domain.repository.FaceProfileRepository

/**
 * Implementación sobre SQLDelight del acceso a perfiles faciales.
 * Solo devuelve perfiles activos de empleados activos (JOIN).
 */
class FaceProfileRepositoryImpl(
    private val db: CoatiCheckDatabase
) : FaceProfileRepository {

    override suspend fun getActiveProfilesWithEmployee(): List<EnrolledFaceProfile> =
        db.faceProfiles.getAllActiveProfilesWithEmployee().executeAsList().map { row ->
            EnrolledFaceProfile(
                id = row.id,
                employeeId = row.employee_id,
                employeeName = row.employee_full_name,
                embeddingBlob = row.embedding_blob,
                modelVersion = row.model_version,
                qualityScore = row.quality_score?.toFloat()
            )
        }
}
