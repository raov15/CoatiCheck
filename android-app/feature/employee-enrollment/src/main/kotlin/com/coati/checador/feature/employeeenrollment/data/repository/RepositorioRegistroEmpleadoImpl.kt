package com.coati.checador.feature.employeeenrollment.data.repository

import android.graphics.Bitmap
import com.coati.checador.core.database.dao.EmployeeDao
import com.coati.checador.core.database.dao.EmployeeFaceProfileDao
import com.coati.checador.core.database.entity.EmployeeEntity
import com.coati.checador.core.database.entity.EmployeeFaceProfileEntity
import com.coati.checador.core.database.model.SyncStatus
import com.coati.checador.feature.employeeenrollment.data.service.EmbeddingService
import com.coati.checador.feature.employeeenrollment.domain.model.Empleado
import com.coati.checador.feature.employeeenrollment.domain.model.ResultadoVerificacion
import com.coati.checador.feature.employeeenrollment.domain.repository.RepositorioRegistroEmpleado
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementación del repositorio de registro de empleados.
 *
 * Estrategia OFFLINE FIRST:
 * - Los datos se persisten inmediatamente en Room con sync_status = PENDING.
 * - WorkManager (configurado en core:sync) se encarga de enviar los datos
 *   al backend cuando haya conectividad disponible.
 */
@Singleton
class RepositorioRegistroEmpleadoImpl @Inject constructor(
    private val employeeDao: EmployeeDao,
    private val faceProfileDao: EmployeeFaceProfileDao,
    private val embeddingService: EmbeddingService
) : RepositorioRegistroEmpleado {

    override suspend fun registrarEmpleado(empleado: Empleado, imagenRostro: Bitmap): Boolean {
        return try {
            Timber.d("RepositorioRegistroEmpleado: guardando empleado ${empleado.idLocal}")

            // 1. Insertar entidad de empleado en Room
            val entidadEmpleado = empleado.aEntidad()
            employeeDao.insertOrReplace(entidadEmpleado)
            Timber.d("RepositorioRegistroEmpleado: empleado insertado en tabla employees")

            // 2. Generar embedding y cifrarlo
            val embedding = embeddingService.generarEmbedding(imagenRostro)
            val embeddingCifrado = embeddingService.cifrarEmbedding(embedding)
            Timber.d("RepositorioRegistroEmpleado: embedding generado y cifrado (${embeddingCifrado.size} bytes)")

            // 3. Calcular puntuación de calidad básica
            val puntuacionCalidad = calcularCalidadImagen(imagenRostro)

            // 4. Insertar perfil facial en Room
            val perfilFacial = EmployeeFaceProfileEntity(
                id = UUID.randomUUID().toString(),
                employeeId = empleado.idLocal,
                embeddingBlob = embeddingCifrado,
                modelVersion = EmbeddingService.VERSION_MODELO,
                qualityScore = puntuacionCalidad,
                createdAt = System.currentTimeMillis(),
                isActive = true
            )
            faceProfileDao.insert(perfilFacial)
            Timber.i("RepositorioRegistroEmpleado: perfil facial guardado para ${empleado.idLocal}")

            true
        } catch (e: Exception) {
            Timber.e(e, "RepositorioRegistroEmpleado: error al registrar empleado ${empleado.idLocal}")
            false
        }
    }

    override suspend fun existeCodigoEmpleado(codigo: String): Boolean {
        val empleado = employeeDao.findByCode(codigo)
        return empleado != null
    }

    override suspend fun verificarIdentidad(imagenRostro: Bitmap): ResultadoVerificacion {
        Timber.d("RepositorioRegistroEmpleado: verificando identidad 1:N")

        val embeddingNuevo = embeddingService.generarEmbedding(imagenRostro)
        val perfilesActivos = faceProfileDao.getAllActiveForRecognition()

        if (perfilesActivos.isEmpty()) {
            Timber.d("RepositorioRegistroEmpleado: sin perfiles registrados para comparar")
            return ResultadoVerificacion.NoEncontrado
        }

        var mejorDistancia = Float.MAX_VALUE
        var mejorEmpleadoId: String? = null

        for (perfil in perfilesActivos) {
            try {
                val embeddingAlmacenado = embeddingService.descifrarEmbedding(perfil.embeddingBlob)
                val distancia = embeddingService.distanciaCoseno(embeddingNuevo, embeddingAlmacenado)

                Timber.v("RepositorioRegistroEmpleado: distancia con ${perfil.employeeId} = $distancia")

                if (distancia < mejorDistancia) {
                    mejorDistancia = distancia
                    mejorEmpleadoId = perfil.employeeId
                }
            } catch (e: Exception) {
                Timber.w(e, "RepositorioRegistroEmpleado: error al descifrar perfil ${perfil.id}")
            }
        }

        // Si la mejor coincidencia supera el umbral de similitud, es un duplicado
        return if (mejorDistancia <= 0.4f && mejorEmpleadoId != null) {
            val empleado = employeeDao.findById(mejorEmpleadoId)
            Timber.w("RepositorioRegistroEmpleado: posible duplicado encontrado, distancia=$mejorDistancia")
            ResultadoVerificacion.Coincidencia(
                empleadoId = mejorEmpleadoId,
                nombreEmpleado = empleado?.fullName ?: "Empleado existente",
                distancia = mejorDistancia
            )
        } else {
            Timber.d("RepositorioRegistroEmpleado: sin coincidencias (mejor distancia=$mejorDistancia)")
            ResultadoVerificacion.NoEncontrado
        }
    }

    override fun observarEmpleadosActivos(): Flow<List<Empleado>> {
        return employeeDao.observeAllActive().map { entidades ->
            entidades.map { it.aDominio() }
        }
    }

    override suspend fun contarEmpleadosActivos(): Int {
        return employeeDao.countActive()
    }

    // ─── Mappers ────────────────────────────────────────────────────────────────

    /**
     * Convierte el modelo de dominio [Empleado] a la entidad Room [EmployeeEntity].
     *
     * Nota: los campos departamento, horarioEntrada y horarioSalida no existen
     * en la entidad Room base, por lo que se codifican en el campo employee_code
     * con el formato "CODIGO|DEPARTAMENTO|ENTRADA|SALIDA" para esta versión.
     * TODO: Agregar columnas department, schedule_in, schedule_out a EmployeeEntity
     *       en la siguiente migración de base de datos.
     */
    private fun Empleado.aEntidad(): EmployeeEntity {
        // Codificación temporal mientras se agrega la migración
        val codigoCombinado = "${codigoEmpleado}|${departamento}|${horarioEntrada}|${horarioSalida}"
        return EmployeeEntity(
            idLocal = idLocal,
            idRemote = null,
            employeeCode = codigoCombinado,
            fullName = nombreCompleto,
            isActive = activo,
            createdAt = creadoEn,
            updatedAt = System.currentTimeMillis(),
            syncStatus = SyncStatus.PENDING
        )
    }

    private fun EmployeeEntity.aDominio(): Empleado {
        // Decodificar el código combinado
        val partes = employeeCode.split("|")
        return Empleado(
            idLocal = idLocal,
            codigoEmpleado = partes.getOrElse(0) { employeeCode },
            nombreCompleto = fullName,
            departamento = partes.getOrElse(1) { "" },
            horarioEntrada = partes.getOrElse(2) { "" },
            horarioSalida = partes.getOrElse(3) { "" },
            activo = isActive,
            creadoEn = createdAt,
            estadoSync = syncStatus
        )
    }

    /**
     * Calcula una puntuación básica de calidad de imagen (0.0 – 1.0)
     * basada en el contraste del bitmap del rostro.
     */
    private fun calcularCalidadImagen(bitmap: Bitmap): Float {
        val ancho = bitmap.width
        val alto = bitmap.height
        if (ancho == 0 || alto == 0) return 0f

        var sumaVarianza = 0.0
        val pixeles = IntArray(ancho * alto)
        bitmap.getPixels(pixeles, 0, ancho, 0, 0, ancho, alto)

        var suma = 0.0
        for (p in pixeles) {
            val gris = (0.299 * ((p shr 16) and 0xFF) +
                        0.587 * ((p shr 8) and 0xFF) +
                        0.114 * (p and 0xFF))
            suma += gris
        }
        val media = suma / pixeles.size

        for (p in pixeles) {
            val gris = (0.299 * ((p shr 16) and 0xFF) +
                        0.587 * ((p shr 8) and 0xFF) +
                        0.114 * (p and 0xFF))
            val diff = gris - media
            sumaVarianza += diff * diff
        }

        val varianza = (sumaVarianza / pixeles.size).toFloat()
        // Normalizar: varianza max teórica ~16256 (contraste perfecto)
        return (varianza / 16256f).coerceIn(0f, 1f)
    }
}
