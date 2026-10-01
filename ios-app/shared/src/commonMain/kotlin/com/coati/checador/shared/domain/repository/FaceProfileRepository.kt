package com.coati.checador.shared.domain.repository

import com.coati.checador.shared.domain.model.EnrolledFaceProfile

/**
 * Acceso a los perfiles faciales almacenados localmente.
 * Base del reconocimiento 1:N en el registro de asistencia.
 */
interface FaceProfileRepository {

    /**
     * Perfiles faciales activos de todos los empleados activos,
     * con el embedding cifrado y el nombre del empleado.
     */
    suspend fun getActiveProfilesWithEmployee(): List<EnrolledFaceProfile>
}
