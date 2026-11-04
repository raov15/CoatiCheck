package com.coati.checador.shared.data.repository

import app.cash.sqldelight.coroutines.asFlow
import com.coati.checador.shared.data.db.CoatiCheckDatabase
import com.coati.checador.shared.data.db.insertEmployee
import com.coati.checador.shared.domain.facerecognition.FaceMatcher
import com.coati.checador.shared.domain.facerecognition.FaceRecognitionEngine
import com.coati.checador.shared.domain.model.Employee
import com.coati.checador.shared.domain.model.IdentityVerificationResult
import com.coati.checador.shared.domain.repository.EmployeeRepository
import com.coati.checador.shared.security.EmbeddingCrypto
import kotlin.random.Random
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Implementación del repositorio de empleados sobre SQLDelight.
 *
 * El embedding facial se genera con el motor de plataforma y se guarda
 * CIFRADO (AES-GCM en Android / AES-CBC+HMAC en iOS) en
 * employee_face_profiles.embedding_blob.
 */
class EmployeeRepositoryImpl(
    private val db: CoatiCheckDatabase,
    private val engine: FaceRecognitionEngine,
    private val crypto: EmbeddingCrypto
) : EmployeeRepository {

    override suspend fun registerEmployee(employee: Employee, faceImage: ByteArray): Boolean {
        // 1. Embedding del rostro capturado (L2-normalizado).
        val embedding = engine.generarEmbedding(faceImage)

        // 2. Cifrado del embedding antes de tocar la BD.
        val encryptedBlob = crypto.encrypt(FaceMatcher.floatsToBytes(embedding))

        // 3. Persistencia atómica: empleado + perfil facial.
        return try {
            db.transaction {
                db.insertEmployee(employee)
                db.faceProfiles.insertOrReplace(
                    id = "fp-" + employee.idLocal,
                    employee_id = employee.idLocal,
                    embedding_blob = encryptedBlob,
                    model_version = engine.versionModelo,
                    quality_score = 1.0,
                    created_at = employee.createdAt,
                    is_active = true
                )
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun employeeCodeExists(code: String): Boolean =
        db.employees.findByCode(code).executeAsOneOrNull() != null

    override suspend fun verifyIdentity(faceImage: ByteArray): IdentityVerificationResult {
        val embedding = try {
            engine.generarEmbedding(faceImage)
        } catch (e: Exception) {
            return IdentityVerificationResult.Failure(
                e.message ?: "No se pudo procesar el rostro capturado."
            )
        }
        val profiles = try {
            db.faceProfiles.getAllActiveProfilesWithEmployee().executeAsList()
        } catch (e: Exception) {
            return IdentityVerificationResult.Failure("No se pudieron leer los perfiles: ${e.message}")
        }

        var bestDistance = Float.MAX_VALUE
        var bestEmployeeId: String? = null
        var bestName: String? = null
        for (profile in profiles) {
            val stored = try {
                FaceMatcher.bytesToFloats(crypto.decrypt(profile.embedding_blob))
            } catch (_: Exception) {
                continue
            }
            if (stored.size != embedding.size) continue
            val distance = FaceMatcher.distanciaCoseno(embedding, stored)
            if (distance < bestDistance) {
                bestDistance = distance
                bestEmployeeId = profile.employee_id
                bestName = profile.employee_full_name
            }
        }
        return if (bestEmployeeId != null && bestName != null &&
            bestDistance <= FaceMatcher.UMBRAL_SIMILITUD_DEFAULT
        ) {
            IdentityVerificationResult.Match(bestEmployeeId, bestName, bestDistance)
        } else {
            IdentityVerificationResult.NotFound
        }
    }

    override fun observeActiveEmployees(): Flow<List<Employee>> =
        db.employees.observeAllActive().asFlow().map { query ->
            query.executeAsList().map { it.toDomain() }
        }

    override suspend fun countActiveEmployees(): Int =
        db.employees.countActive().executeAsOne().toInt()

    private fun com.coati.checador.shared.data.db.Employees.toDomain(): Employee =
        Employee(
            idLocal = id_local,
            idRemote = id_remote,
            employeeCode = employee_code,
            firstName = first_name,
            lastNamePaternal = last_name_paternal,
            lastNameMaternal = last_name_maternal,
            fullName = full_name,
            rfc = rfc,
            curp = curp,
            nss = nss,
            department = department,
            workStartTime = work_start_time,
            workEndTime = work_end_time,
            lateToleranceMinutes = late_tolerance_minutes.toInt(),
            lateCount = late_count.toInt(),
            absenceCount = absence_count.toInt(),
            isActive = is_active,
            createdAt = created_at,
            updatedAt = updated_at,
            syncStatus = sync_status
        )
}

/** Genera un id local para empleados enrolados en el dispositivo. */
fun generateEmployeeLocalId(): String =
    "emp-" + Random.nextLong(100_000L, 999_999L).toString() + "-" +
        Random.nextInt(1000, 9999).toString()
