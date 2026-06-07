package com.coati.checador.feature.employeeenrollment.domain.repository

import android.graphics.Bitmap
import com.coati.checador.feature.employeeenrollment.domain.model.Empleado
import com.coati.checador.feature.employeeenrollment.domain.model.ResultadoVerificacion
import kotlinx.coroutines.flow.Flow

/**
 * Contrato del repositorio para el registro de empleados.
 * Sigue el principio de inversión de dependencias: la capa de dominio
 * define la interfaz; la capa de datos provee la implementación.
 */
interface RepositorioRegistroEmpleado {

    /**
     * Registra un nuevo empleado con su perfil facial en Room.
     * El sync_status se establece como PENDING para sincronización posterior.
     *
     * @param empleado     Datos del empleado a registrar
     * @param imagenRostro Bitmap del rostro capturado (se normalizará y cifrará)
     * @return true si el registro fue exitoso
     */
    suspend fun registrarEmpleado(empleado: Empleado, imagenRostro: Bitmap): Boolean

    /**
     * Verifica si ya existe un empleado con el mismo código.
     */
    suspend fun existeCodigoEmpleado(codigo: String): Boolean

    /**
     * Compara el rostro capturado contra todos los perfiles registrados (1:N).
     * Útil para detectar duplicados antes de completar el registro.
     *
     * @param imagenRostro Bitmap del rostro a comparar
     * @return Resultado de la comparación
     */
    suspend fun verificarIdentidad(imagenRostro: Bitmap): ResultadoVerificacion

    /**
     * Flujo reactivo con la lista de empleados activos.
     */
    fun observarEmpleadosActivos(): Flow<List<Empleado>>

    /**
     * Total de empleados activos registrados en el dispositivo.
     */
    suspend fun contarEmpleadosActivos(): Int
}
