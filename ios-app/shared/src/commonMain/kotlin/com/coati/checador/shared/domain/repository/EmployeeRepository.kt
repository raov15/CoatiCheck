package com.coati.checador.shared.domain.repository

import com.coati.checador.shared.domain.model.Employee
import com.coati.checador.shared.domain.model.IdentityVerificationResult
import kotlinx.coroutines.flow.Flow

/**
 * Contrato del repositorio para el registro de empleados.
 *
 * La imagen del rostro se representa como [ByteArray] (bytes de imagen
 * codificados, p. ej. JPEG) para mantener el dominio independiente de la
 * plataforma: cada plataforma convierte su tipo nativo (Bitmap / UIImage)
 * a bytes antes de llamar a estos métodos.
 */
interface EmployeeRepository {

    /**
     * Registra un nuevo empleado con su perfil facial en la BD local.
     * El sync_status se establece como PENDING para sincronización posterior.
     *
     * @param employee   Datos del empleado a registrar
     * @param faceImage  Bytes del rostro capturado (se cifrará el embedding)
     * @return true si el registro fue exitoso
     */
    suspend fun registerEmployee(employee: Employee, faceImage: ByteArray): Boolean

    /** Verifica si ya existe un empleado con el mismo código. */
    suspend fun employeeCodeExists(code: String): Boolean

    /**
     * Compara el rostro capturado contra todos los perfiles registrados (1:N).
     * Útil para detectar duplicados antes de completar el registro.
     */
    suspend fun verifyIdentity(faceImage: ByteArray): IdentityVerificationResult

    /** Flujo reactivo con la lista de empleados activos. */
    fun observeActiveEmployees(): Flow<List<Employee>>

    /** Total de empleados activos registrados en el dispositivo. */
    suspend fun countActiveEmployees(): Int
}
