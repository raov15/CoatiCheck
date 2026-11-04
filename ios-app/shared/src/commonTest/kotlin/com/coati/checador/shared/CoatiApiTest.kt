package com.coati.checador.shared

import com.coati.checador.shared.data.network.CoatiApi
import com.coati.checador.shared.data.network.CoatiApiException
import com.coati.checador.shared.data.network.dto.AttendanceRecordDto
import com.coati.checador.shared.data.network.dto.AttendanceSyncRequest
import com.coati.checador.shared.data.network.dto.DeviceRegisterRequest
import com.coati.checador.shared.data.network.dto.EmployeeDto
import com.coati.checador.shared.data.network.dto.EmployeeSyncRequest
import com.coati.checador.shared.data.network.isAuthError
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class CoatiApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun mockApi(handler: suspend (url: String, body: String, auth: String?) -> MockReply): CoatiApi {
        val engine = MockEngine { request ->
            val url = request.url.toString()
            val body = (request.body as? OutgoingContent.ByteArrayContent)
                ?.bytes()?.decodeToString() ?: ""
            val auth = request.headers[HttpHeaders.Authorization]
            when (val reply = handler(url, body, auth)) {
                is MockReply.Data -> respond(
                    content = ByteReadChannel(reply.json),
                    status = reply.status,
                    headers = jsonHeaders
                )
                is MockReply.Error -> respondError(reply.status, reply.json, jsonHeaders)
            }
        }
        return CoatiApi("https://api.coati.mx", httpClientEngine = engine)
    }

    private sealed class MockReply {
        data class Data(val json: String, val status: HttpStatusCode = HttpStatusCode.OK) : MockReply()
        data class Error(val json: String, val status: HttpStatusCode) : MockReply()
    }

    @Test
    fun `registerDevice envia payload snake_case y parsea respuesta`() = runTest {
        val api = mockApi { url, body, _ ->
            assertEquals("https://api.coati.mx/api/devices/register", url)
            assertTrue(body.contains("\"device_name\":\"Tablet-01\""))
            assertTrue(body.contains("\"device_fingerprint\":\"fp-abc\""))
            assertTrue(body.contains("\"local_id\":\"local-1\""))
            MockReply.Data(
                """{"device_id":"D-1","auth_token":"tok","site_id":"S-1","branding":null}"""
            )
        }

        val response = api.registerDevice(
            DeviceRegisterRequest(
                deviceName = "Tablet-01",
                deviceFingerprint = "fp-abc",
                localId = "local-1"
            )
        )

        assertEquals("D-1", response.deviceId)
        assertEquals("tok", response.authToken)
        assertEquals("S-1", response.siteId)
        api.close()
    }

    @Test
    fun `syncAttendance manda Authorization y parsea synced y errors`() = runTest {
        val api = mockApi { url, _, auth ->
            assertEquals("https://api.coati.mx/api/attendance/sync", url)
            assertEquals("Bearer tok-123", auth)
            MockReply.Data(
                """{"synced":[{"id_local":"a1","id_remote":"R-1"}],""" +
                    """"errors":[{"id_local":"a2","error":"empleado no existe"}]}"""
            )
        }

        val response = api.syncAttendance(
            bearerToken = "Bearer tok-123",
            request = AttendanceSyncRequest(
                records = listOf(
                    AttendanceRecordDto(
                        idLocal = "a1",
                        employeeId = "e1",
                        eventType = "CLOCK_IN",
                        occurredAt = 1_700_000_000_000
                    )
                )
            )
        )

        assertEquals(1, response.synced.size)
        assertEquals("R-1", response.synced.first().idRemote)
        assertEquals(1, response.errors.size)
        assertEquals("empleado no existe", response.errors.first().error)
        api.close()
    }

    @Test
    fun `error 401 se traduce a CoatiApiException con isAuthError`() = runTest {
        val api = mockApi { _, _, _ ->
            MockReply.Error(
                json = """{"error":"token inválido"}""",
                status = HttpStatusCode.Unauthorized
            )
        }

        try {
            api.verifyToken("Bearer bad")
            fail("Debía lanzar CoatiApiException")
        } catch (e: CoatiApiException) {
            assertEquals(401, e.statusCode)
            assertTrue(e.isAuthError())
            assertTrue(e.message!!.contains("token inválido"))
        }
        api.close()
    }

    @Test
    fun `error 500 no es authError`() = runTest {
        val api = mockApi { _, _, _ ->
            MockReply.Error(
                json = """{"error":"fallo interno"}""",
                status = HttpStatusCode.InternalServerError
            )
        }

        try {
            api.getEmployeeWorkAssignments("Bearer tok")
            fail("Debía lanzar CoatiApiException")
        } catch (e: CoatiApiException) {
            assertEquals(500, e.statusCode)
            assertTrue(!e.isAuthError())
        }
        api.close()
    }

    @Test
    fun `syncEmployees serializa lista de empleados`() = runTest {
        val api = mockApi { url, body, _ ->
            assertEquals("https://api.coati.mx/api/employees/sync", url)
            assertTrue(body.contains("\"employee_code\":\"AUTO\""))
            assertTrue(body.contains("\"is_active\":true"))
            MockReply.Data("""{"synced":[{"id_local":"e1","id_remote":"RE-1"}],"errors":[]}""")
        }

        val response = api.syncEmployees(
            bearerToken = "Bearer tok",
            request = EmployeeSyncRequest(
                employees = listOf(
                    EmployeeDto(
                        idLocal = "e1",
                        employeeCode = "AUTO",
                        firstName = "Juan",
                        lastNamePaternal = "Pérez",
                        lastNameMaternal = "López",
                        fullName = "Juan Pérez López",
                        rfc = "PELJ800101XXX",
                        curp = "PELJ800101HDFXXX01",
                        nss = "12345678901",
                        department = "Programador",
                        isActive = true
                    )
                )
            )
        )

        assertEquals("RE-1", response.synced.first().idRemote)
        api.close()
    }

    @Test
    fun `work assignments parsea configuracion SITE y FOREIGN`() = runTest {
        val api = mockApi { url, _, _ ->
            assertEquals("https://api.coati.mx/api/employees/work-assignments", url)
            MockReply.Data(
                """{"assignments":[{"employee_id_remote":"RE-1","employee_id_local":"e1",""" +
                    """"employee_code":"EMP001","weekday":1,"work_mode":"FOREIGN","site_id":null,"site_name":null}]}"""
            )
        }

        val response = api.getEmployeeWorkAssignments("Bearer tok")

        assertEquals(1, response.assignments.size)
        assertEquals("FOREIGN", response.assignments.first().workMode)
        assertEquals(1, response.assignments.first().weekday)
        api.close()
    }

    @Test
    fun `rechaza baseUrl sin https`() {
        try {
            CoatiApi("http://148.230.222.13:3000/api/")
            fail("Debía rechazar baseUrl http sin allowInsecure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("https"))
        }
    }
}
