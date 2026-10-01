package com.coati.checador.shared.data.network

import com.coati.checador.shared.data.network.dto.AttendanceSyncRequest
import com.coati.checador.shared.data.network.dto.AttendanceSyncResponse
import com.coati.checador.shared.data.network.dto.DeviceBrandingResponse
import com.coati.checador.shared.data.network.dto.DeviceEnrollmentRequest
import com.coati.checador.shared.data.network.dto.DeviceRegisterRequest
import com.coati.checador.shared.data.network.dto.DeviceRegisterResponse
import com.coati.checador.shared.data.network.dto.DeviceTokenRefreshResponse
import com.coati.checador.shared.data.network.dto.DeviceTokenVerifyResponse
import com.coati.checador.shared.data.network.dto.EmployeeSyncRequest
import com.coati.checador.shared.data.network.dto.EmployeeSyncResponse
import com.coati.checador.shared.data.network.dto.EmployeeWorkAssignmentsResponse
import com.coati.checador.shared.data.network.dto.ForeignLocationSyncRequest
import com.coati.checador.shared.data.network.dto.ForeignLocationSyncResponse
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.ServerResponseException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMessageBuilder
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/**
 * Excepción con el detalle del error devuelto por la API.
 */
class CoatiApiException(
    val statusCode: Int,
    message: String
) : Exception("HTTP $statusCode: $message")

/** Indica si el error corresponde a token inválido/expirado (401/403). */
fun Throwable.isAuthError(): Boolean =
    this is CoatiApiException && (statusCode == 401 || statusCode == 403)

/** Aplica el Content-Type JSON estándar a las peticiones con cuerpo. */
private fun HttpMessageBuilder.jsonBody() {
    contentType(ContentType.Application.Json)
}

/**
 * Cliente de la API CoatiCheck construido sobre Ktor.
 *
 * Mismo contrato que CoatiApiService (Retrofit) en android-app,
 * pero multiplataforma: en Android usa el engine OkHttp y en iOS
 * el engine Darwin, seleccionados automáticamente por el classpath.
 *
 * Seguridad: no se registra el cuerpo de las peticiones (sin logging BODY).
 */
class CoatiApi(
    baseUrl: String,
    connectTimeoutMs: Long = 15_000,
    requestTimeoutMs: Long = 30_000,
    httpClientEngine: HttpClientEngine? = null
) {
    private val base = requireNotNull(
        ApiBaseUrl.normalize(baseUrl)
    ) { "URL base inválida: '$baseUrl'. Debe usar https://" }.trimEnd('/')

    val apiBaseUrl: String get() = "$base/"

    val client: HttpClient = buildClient(httpClientEngine, connectTimeoutMs, requestTimeoutMs)

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private fun buildClient(
        engine: HttpClientEngine?,
        connectTimeoutMs: Long,
        requestTimeoutMs: Long
    ): HttpClient {
        val config: HttpClientConfig<*>.() -> Unit = {
            expectSuccess = true
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                        encodeDefaults = true
                        explicitNulls = false
                    }
                )
            }
            install(HttpTimeout) {
                connectTimeoutMillis = connectTimeoutMs
                requestTimeoutMillis = requestTimeoutMs
            }
        }
        return if (engine != null) HttpClient(engine, config) else HttpClient(config)
    }

    // =========================================================
    // HEALTH
    // =========================================================

    suspend fun healthCheck(): Map<String, String> =
        apiCall { client.get("$base/health").body() }

    // =========================================================
    // DISPOSITIVOS
    // =========================================================

    suspend fun registerDevice(request: DeviceRegisterRequest): DeviceRegisterResponse =
        apiCall {
            client.post("$base/devices/register") {
                jsonBody()
                setBody(request)
            }.body()
        }

    suspend fun enrollDevice(request: DeviceEnrollmentRequest): DeviceRegisterResponse =
        apiCall {
            client.post("$base/devices/enroll") {
                jsonBody()
                setBody(request)
            }.body()
        }

    suspend fun verifyToken(bearerToken: String): DeviceTokenVerifyResponse =
        apiCall {
            client.get("$base/devices/verify") {
                header(HttpHeaders.Authorization, bearerToken)
            }.body()
        }

    suspend fun refreshToken(bearerToken: String): DeviceTokenRefreshResponse =
        apiCall {
            client.post("$base/devices/refresh-token") {
                header(HttpHeaders.Authorization, bearerToken)
            }.body()
        }

    suspend fun getDeviceBranding(bearerToken: String): DeviceBrandingResponse =
        apiCall {
            client.get("$base/devices/branding") {
                header(HttpHeaders.Authorization, bearerToken)
            }.body()
        }

    // =========================================================
    // EMPLEADOS
    // =========================================================

    suspend fun syncEmployees(
        bearerToken: String,
        request: EmployeeSyncRequest
    ): EmployeeSyncResponse =
        apiCall {
            client.post("$base/employees/sync") {
                header(HttpHeaders.Authorization, bearerToken)
                jsonBody()
                setBody(request)
            }.body()
        }

    // =========================================================
    // CONFIGURACIÓN SITE / FOREIGN
    // =========================================================

    suspend fun getEmployeeWorkAssignments(
        bearerToken: String
    ): EmployeeWorkAssignmentsResponse =
        apiCall {
            client.get("$base/employees/work-assignments") {
                header(HttpHeaders.Authorization, bearerToken)
            }.body()
        }

    // =========================================================
    // ASISTENCIAS
    // =========================================================

    suspend fun syncAttendance(
        bearerToken: String,
        request: AttendanceSyncRequest
    ): AttendanceSyncResponse =
        apiCall {
            client.post("$base/attendance/sync") {
                header(HttpHeaders.Authorization, bearerToken)
                jsonBody()
                setBody(request)
            }.body()
        }

    // =========================================================
    // RECORRIDO FORÁNEO
    // =========================================================

    suspend fun syncForeignLocations(
        bearerToken: String,
        request: ForeignLocationSyncRequest
    ): ForeignLocationSyncResponse =
        apiCall {
            client.post("$base/attendance/location/sync") {
                header(HttpHeaders.Authorization, bearerToken)
                jsonBody()
                setBody(request)
            }.body()
        }

    fun close() {
        client.close()
    }

    /**
     * Traduce errores HTTP de Ktor a [CoatiApiException] con el cuerpo
     * del error incluido, para que la capa de sync pueda distinguir
     * token expirado (401/403) de errores de datos (4xx) o servidor (5xx).
     */
    private suspend fun <T> apiCall(block: suspend () -> T): T =
        try {
            block()
        } catch (e: ClientRequestException) {
            throw CoatiApiException(
                statusCode = e.response.status.value,
                message = runCatching { e.response.bodyAsText() }.getOrDefault(e.message)
            )
        } catch (e: ServerResponseException) {
            throw CoatiApiException(
                statusCode = e.response.status.value,
                message = runCatching { e.response.bodyAsText() }.getOrDefault(e.message)
            )
        }
}
