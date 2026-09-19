package com.coati.checador.feature.employeeenrollment.data.repository

import android.content.Context
import android.graphics.Bitmap
import com.coati.checador.core.common.facerecognition.FaceRecognitionEngine
import com.coati.checador.core.database.dao.EmployeeDao
import com.coati.checador.core.database.dao.EmployeeFaceProfileDao
import com.coati.checador.core.database.entity.EmployeeEntity
import com.coati.checador.core.database.entity.EmployeeFaceProfileEntity
import com.coati.checador.core.database.model.SyncStatus
import com.coati.checador.feature.employeeenrollment.domain.model.Empleado
import com.coati.checador.feature.employeeenrollment.domain.model.ResultadoVerificacion
import com.coati.checador.feature.employeeenrollment.domain.repository.RepositorioRegistroEmpleado
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Implementación del repositorio de registro de empleados.
 *
 * Estrategia OFFLINE FIRST:
 * - Genera el embedding facial antes de insertar el empleado.
 * - Guarda el empleado inmediatamente en Room.
 * - Guarda inmediatamente el perfil facial en Room.
 * - Guarda la fotografía local del empleado.
 * - El empleado queda con syncStatus = PENDING.
 * - La sincronización inmediata con el servidor se solicita desde
 *   RegistrarEmpleadoUseCase mediante SyncManager.
 *
 * IMPORTANTE:
 * El idLocal del empleado se conserva sin generar un UUID nuevo.
 * Ese mismo ID será utilizado por el perfil facial y por las asistencias.
 */
@Singleton
class RepositorioRegistroEmpleadoImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val employeeDao: EmployeeDao,
    private val faceProfileDao: EmployeeFaceProfileDao,
    private val embeddingService: FaceRecognitionEngine
) : RepositorioRegistroEmpleado {

    override suspend fun registrarEmpleado(
        empleado: Empleado,
        imagenRostro: Bitmap
    ): Boolean {

        return try {

            Timber.d(
                "RepositorioRegistroEmpleado: iniciando registro local idLocal=${empleado.idLocal}"
            )

            /*
             * 1. GENERAR EMBEDDING ANTES DE INSERTAR
             *
             * Esto evita guardar un empleado sin perfil facial si la
             * generación del embedding falla.
             */
            val embedding =
                embeddingService.generarEmbedding(
                    imagenRostro
                )

            val embeddingCifrado =
                embeddingService.cifrarEmbedding(
                    embedding
                )

            Timber.d(
                "RepositorioRegistroEmpleado: embedding generado y cifrado " +
                    "(${embeddingCifrado.size} bytes)"
            )

            /*
             * 2. CALCULAR CALIDAD DE LA IMAGEN
             */
            val puntuacionCalidad =
                calcularCalidadImagen(
                    imagenRostro
                )

            /*
             * 3. PREPARAR EMPLEADO LOCAL
             *
             * NO se genera otro UUID.
             */
            val entidadEmpleado =
                empleado.aEntidad()

            /*
             * 4. PREPARAR PERFIL FACIAL
             *
             * employeeId apunta al mismo idLocal del empleado.
             */
            val perfilFacial =
                EmployeeFaceProfileEntity(
                    id =
                        UUID.randomUUID().toString(),

                    employeeId =
                        empleado.idLocal,

                    embeddingBlob =
                        embeddingCifrado,

                    modelVersion =
                        embeddingService.versionModelo,

                    qualityScore =
                        puntuacionCalidad,

                    createdAt =
                        System.currentTimeMillis(),

                    isActive =
                        true
                )

            /*
             * 5. GUARDAR EMPLEADO EN ROOM
             */
            employeeDao.insertOrReplace(
                entidadEmpleado
            )

            Timber.i(
                "RepositorioRegistroEmpleado: empleado guardado localmente " +
                    "idLocal=${entidadEmpleado.idLocal}, " +
                    "nombre=${entidadEmpleado.fullName}, " +
                    "codigo=${entidadEmpleado.employeeCode}, " +
                    "syncStatus=${entidadEmpleado.syncStatus}"
            )

            /*
             * 6. GUARDAR PERFIL FACIAL EN ROOM
             *
             * Este paso ocurre ANTES de devolver true.
             * Por eso AttendanceViewModel puede consultar el rostro
             * inmediatamente desde la base local, sin esperar al servidor.
             */
            faceProfileDao.insert(
                perfilFacial
            )

            Timber.i(
                "RepositorioRegistroEmpleado: perfil facial guardado " +
                    "employeeId=${perfilFacial.employeeId}, " +
                    "profileId=${perfilFacial.id}"
            )

            /*
             * 7. GUARDAR FOTOGRAFÍA LOCAL
             *
             * La fotografía es auxiliar. El reconocimiento utiliza
             * principalmente el embedding guardado en Room.
             */
            guardarFotoEmpleado(
                empleado.idLocal,
                imagenRostro
            )

            Timber.i(
                "RepositorioRegistroEmpleado: registro local completo. " +
                    "Empleado ${empleado.idLocal} disponible para reconocimiento inmediato."
            )

            /*
             * El empleado queda PENDING.
             * RegistrarEmpleadoUseCase solicitará syncManager.syncNow()
             * después de recibir este true.
             */
            true

        } catch (e: Exception) {

            Timber.e(
                e,
                "RepositorioRegistroEmpleado: error registrando empleado ${empleado.idLocal}"
            )

            false
        }
    }

    override suspend fun existeCodigoEmpleado(
        codigo: String
    ): Boolean {

        val empleado =
            employeeDao.findByCode(
                codigo
            )

        return empleado != null
    }

    override suspend fun verificarIdentidad(
        imagenRostro: Bitmap
    ): ResultadoVerificacion {

        Timber.d(
            "RepositorioRegistroEmpleado: verificando identidad 1:N"
        )

        /*
         * Generar embedding de la fotografía nueva.
         */
        val embeddingNuevo =
            embeddingService.generarEmbedding(
                imagenRostro
            )

        /*
         * Obtener perfiles faciales activos directamente de Room.
         */
        val perfilesActivos =
            faceProfileDao
                .getAllActiveForRecognition()

        if (perfilesActivos.isEmpty()) {

            Timber.d(
                "RepositorioRegistroEmpleado: sin perfiles registrados para comparar"
            )

            return ResultadoVerificacion.NoEncontrado
        }

        var mejorDistancia =
            Float.MAX_VALUE

        var mejorEmpleadoId:
            String? =
            null

        /*
         * Comparar 1:N.
         */
        for (perfil in perfilesActivos) {

            try {

                val embeddingAlmacenado =
                    embeddingService
                        .descifrarEmbedding(
                            perfil.embeddingBlob
                        )

                val distancia =
                    embeddingService
                        .distanciaCoseno(
                            embeddingNuevo,
                            embeddingAlmacenado
                        )

                Timber.v(
                    "RepositorioRegistroEmpleado: distancia con " +
                        "${perfil.employeeId} = $distancia"
                )

                if (
                    distancia <
                    mejorDistancia
                ) {

                    mejorDistancia =
                        distancia

                    mejorEmpleadoId =
                        perfil.employeeId
                }

            } catch (e: Exception) {

                Timber.w(
                    e,
                    "RepositorioRegistroEmpleado: error leyendo perfil ${perfil.id}"
                )
            }
        }

        /*
         * Umbral usado solamente para detectar posibles duplicados
         * durante el enrolamiento.
         *
         * El control de acceso de asistencia usa su propia validación
         * más estricta en AttendanceViewModel.
         */
        val umbralDuplicado =
            0.4f

        return if (
            mejorEmpleadoId != null &&
            mejorDistancia <=
            umbralDuplicado
        ) {

            val empleado =
                employeeDao.findById(
                    mejorEmpleadoId
                )

            Timber.w(
                "RepositorioRegistroEmpleado: posible duplicado encontrado. " +
                    "empleado=$mejorEmpleadoId, distancia=$mejorDistancia"
            )

            ResultadoVerificacion.Coincidencia(
                empleadoId =
                    mejorEmpleadoId,

                nombreEmpleado =
                    empleado?.fullName
                        ?: "Empleado existente",

                distancia =
                    mejorDistancia
            )

        } else {

            Timber.d(
                "RepositorioRegistroEmpleado: sin coincidencias. " +
                    "Mejor distancia=$mejorDistancia"
            )

            ResultadoVerificacion.NoEncontrado
        }
    }

    override fun observarEmpleadosActivos():
        Flow<List<Empleado>> {

        return employeeDao
            .observeAllActive()
            .map { entidades ->

                entidades.map { entidad ->
                    entidad.aDominio()
                }
            }
    }

    override suspend fun contarEmpleadosActivos():
        Int {

        return employeeDao
            .countActive()
    }

    // =========================================================
    // MAPPERS
    // =========================================================

    /**
     * Convierte Empleado a EmployeeEntity.
     *
     * IMPORTANTE:
     * - employeeCode contiene únicamente el código del empleado.
     * - El departamento se guarda en su propia columna.
     * - El horario ya no se "esconde" dentro de employeeCode.
     * - El horario/tolerancia deben venir de la configuración laboral.
     */
    private fun Empleado.aEntidad():
        EmployeeEntity {

        return EmployeeEntity(
            idLocal =
                idLocal,

            idRemote =
                null,

            employeeCode =
                codigoEmpleado,

            firstName =
                nombre,

            lastNamePaternal =
                apellidoPaterno,

            lastNameMaternal =
                apellidoMaterno,

            fullName =
                nombreCompleto,

            rfc =
                rfc,

            curp =
                curp,

            nss =
                nss,

            department =
                departamento,

            workStartTime =
                horarioEntrada,

            workEndTime =
                horarioSalida,

            lateToleranceMinutes =
                toleranciaRetardo,

            lateCount =
                totalRetardos,

            absenceCount =
                totalAusentismos,

            isActive =
                activo,

            createdAt =
                creadoEn,

            updatedAt =
                System.currentTimeMillis(),

            syncStatus =
                SyncStatus.PENDING
        )
    }

    /**
     * Convierte EmployeeEntity a Empleado.
    
     */
    private fun EmployeeEntity.aDominio():
        Empleado {

        return Empleado(
            idLocal =
                idLocal,

            codigoEmpleado =
                employeeCode,

            nombre =
                firstName,

            apellidoPaterno =
                lastNamePaternal,

            apellidoMaterno =
                lastNameMaternal,

            nombreCompleto =
                fullName,

            rfc =
                rfc,

            curp =
                curp,

            nss =
                nss,

            departamento =
                department,

            horarioEntrada =
                workStartTime,

            horarioSalida =
                workEndTime,

            toleranciaRetardo =
                lateToleranceMinutes,

            totalRetardos =
                lateCount,

            totalAusentismos =
                absenceCount,

            activo =
                isActive,

            creadoEn =
                createdAt,

            estadoSync =
                syncStatus
        )
    }

    // =========================================================
    // CALIDAD DE IMAGEN
    // =========================================================

    /**
     * Calcula una puntuación aproximada de calidad
     * utilizando el contraste de la fotografía.
     */
    private fun calcularCalidadImagen(
        bitmap: Bitmap
    ): Float {

        val ancho =
            bitmap.width

        val alto =
            bitmap.height

        if (
            ancho == 0 ||
            alto == 0
        ) {
            return 0f
        }

        val pixeles =
            IntArray(
                ancho * alto
            )

        bitmap.getPixels(
            pixeles,
            0,
            ancho,
            0,
            0,
            ancho,
            alto
        )

        var suma =
            0.0

        for (p in pixeles) {

            val gris =
                (
                    0.299 *
                        ((p shr 16) and 0xFF)
                    ) +
                    (
                        0.587 *
                            ((p shr 8) and 0xFF)
                        ) +
                    (
                        0.114 *
                            (p and 0xFF)
                        )

            suma +=
                gris
        }

        val media =
            suma /
                pixeles.size

        var sumaVarianza =
            0.0

        for (p in pixeles) {

            val gris =
                (
                    0.299 *
                        ((p shr 16) and 0xFF)
                    ) +
                    (
                        0.587 *
                            ((p shr 8) and 0xFF)
                        ) +
                    (
                        0.114 *
                            (p and 0xFF)
                        )

            val diferencia =
                gris -
                    media

            sumaVarianza +=
                diferencia *
                    diferencia
        }

        val varianza =
            (
                sumaVarianza /
                    pixeles.size
                )
                .toFloat()

        return (
            varianza /
                16256f
            )
            .coerceIn(
                0f,
                1f
            )
    }

    // =========================================================
    // FOTOGRAFÍA
    // =========================================================

    /**
     * Guarda la fotografía en:
     *
     * files/employee_photos/{idLocal}.jpg
     *
     * Si falla esta parte se registra el error, pero el empleado
     * y su perfil facial ya permanecen disponibles en Room.
     */
    private fun guardarFotoEmpleado(
        id: String,
        bitmap: Bitmap
    ) {

        try {

            val folder =
                File(
                    context.filesDir,
                    "employee_photos"
                )

            if (!folder.exists()) {

                val creada =
                    folder.mkdirs()

                Timber.d(
                    "RepositorioRegistroEmpleado: carpeta employee_photos creada=$creada"
                )
            }

            val file =
                File(
                    folder,
                    "$id.jpg"
                )

            FileOutputStream(
                file
            ).use { output ->

                bitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    90,
                    output
                )
            }

            Timber.d(
                "RepositorioRegistroEmpleado: foto guardada en ${file.absolutePath}"
            )

        } catch (e: Exception) {

            Timber.e(
                e,
                "RepositorioRegistroEmpleado: error guardando foto del empleado $id"
            )
        }
    }
}