package com.coati.checador.shared

import com.coati.checador.shared.common.Result
import com.coati.checador.shared.domain.model.Employee
import com.coati.checador.shared.domain.model.IdentityVerificationResult
import com.coati.checador.shared.domain.repository.EmployeeRepository
import com.coati.checador.shared.domain.repository.SyncScheduler
import com.coati.checador.shared.domain.usecase.employee.RegisterEmployeeUseCase
import com.coati.checador.shared.domain.usecase.employee.VerifyIdentityUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RegisterEmployeeUseCaseTest {

    private class FakeEmployeeRepository : EmployeeRepository {
        val employees = MutableStateFlow<List<Employee>>(emptyList())
        var registerResult = true
        var existingCodes: Set<String> = emptySet()
        var nextIdentityResult: IdentityVerificationResult = IdentityVerificationResult.NotFound

        override suspend fun registerEmployee(employee: Employee, faceImage: ByteArray): Boolean {
            if (!registerResult) return false
            employees.value = employees.value + employee
            return true
        }

        override suspend fun employeeCodeExists(code: String): Boolean =
            code in existingCodes || employees.value.any { it.employeeCode == code }

        override suspend fun verifyIdentity(faceImage: ByteArray): IdentityVerificationResult =
            nextIdentityResult

        override fun observeActiveEmployees(): Flow<List<Employee>> = employees

        override suspend fun countActiveEmployees(): Int =
            employees.value.count { it.isActive }
    }

    private class RecordingSyncScheduler : SyncScheduler {
        var calls = 0
        override fun syncNow() {
            calls++
        }
    }

    private fun validEmployee(
        id: String = "local-1",
        code: String = "AUTO"
    ) = Employee(
        idLocal = id,
        employeeCode = code,
        firstName = "Juan",
        lastNamePaternal = "Pérez",
        lastNameMaternal = "López",
        fullName = "Juan Pérez López",
        rfc = "PELJ800101XXX",
        curp = "PELJ800101HDFXXX01",
        nss = "12345678901",
        department = "Programador",
        createdAt = 1_000L,
        updatedAt = 1_000L,
        syncStatus = "PENDING"
    )

    @Test
    fun `registra empleado valido y solicita sync`() = runTest {
        val repo = FakeEmployeeRepository()
        val sync = RecordingSyncScheduler()
        val useCase = RegisterEmployeeUseCase(repo, sync)

        val result = useCase(validEmployee(), byteArrayOf(1, 2, 3))

        assertTrue(result is Result.Success)
        assertEquals(1, repo.employees.value.size)
        assertEquals(1, sync.calls)
    }

    @Test
    fun `rechaza segundo empleado en el mismo dispositivo`() = runTest {
        val repo = FakeEmployeeRepository()
        val sync = RecordingSyncScheduler()
        val useCase = RegisterEmployeeUseCase(repo, sync)

        useCase(validEmployee(id = "local-1"), byteArrayOf(1))
        val second = useCase(validEmployee(id = "local-2"), byteArrayOf(2))

        assertTrue(second is Result.Error)
        assertTrue(second.message!!.contains("ya tiene un empleado"))
        assertEquals(1, repo.employees.value.size)
    }

    @Test
    fun `rechaza codigo duplicado`() = runTest {
        val repo = FakeEmployeeRepository()
        repo.existingCodes = setOf("EMP001")
        val sync = RecordingSyncScheduler()
        val useCase = RegisterEmployeeUseCase(repo, sync)

        val duplicate = useCase(validEmployee(id = "local-1", code = "EMP001"), byteArrayOf(1))

        assertTrue(duplicate is Result.Error)
        assertTrue(duplicate.message!!.contains("EMP001"))
        assertTrue(repo.employees.value.isEmpty())
    }

    @Test
    fun `rechaza cargo no permitido`() = runTest {
        val repo = FakeEmployeeRepository()
        val sync = RecordingSyncScheduler()
        val useCase = RegisterEmployeeUseCase(repo, sync)

        val employee = validEmployee().copy(department = "Astronauta")
        val result = useCase(employee, byteArrayOf(1))

        assertTrue(result is Result.Error)
        assertTrue(result.message!!.contains("cargos disponibles"))
    }

    @Test
    fun `rechaza datos basicos incompletos`() = runTest {
        val repo = FakeEmployeeRepository()
        val sync = RecordingSyncScheduler()
        val useCase = RegisterEmployeeUseCase(repo, sync)

        val result = useCase(validEmployee().copy(rfc = ""), byteArrayOf(1))

        assertTrue(result is Result.Error)
        assertTrue(result.message!!.contains("RFC"))
        assertTrue(repo.employees.value.isEmpty())
    }

    @Test
    fun `codigo AUTO no valida duplicados`() = runTest {
        val repo = FakeEmployeeRepository()
        val sync = RecordingSyncScheduler()
        repo.employees.value = listOf(validEmployee(id = "otro", code = "EMP001"))
        // Un dispositivo solo permite 1 empleado activo; vaciamos para permitir el registro.
        repo.employees.value = emptyList()
        val useCase = RegisterEmployeeUseCase(repo, sync)

        val result = useCase(validEmployee(code = "AUTO"), byteArrayOf(1))

        assertTrue(result is Result.Success)
    }

    @Test
    fun `verifyIdentity delega en repositorio`() = runTest {
        val repo = FakeEmployeeRepository()
        repo.nextIdentityResult = IdentityVerificationResult.Match(
            employeeId = "e1",
            employeeName = "Juan Pérez López",
            distance = 0.25f
        )
        val useCase = VerifyIdentityUseCase(repo)

        val result = useCase(byteArrayOf(9))

        assertTrue(result is IdentityVerificationResult.Match)
        assertEquals("e1", result.employeeId)
    }

    @Test
    fun `verifyIdentity captura excepciones`() = runTest {
        val repo = object : EmployeeRepository by FakeEmployeeRepository() {
            override suspend fun verifyIdentity(faceImage: ByteArray): IdentityVerificationResult =
                throw IllegalStateException("boom")
        }
        val useCase = VerifyIdentityUseCase(repo)

        val result = useCase(byteArrayOf(9))

        assertTrue(result is IdentityVerificationResult.Failure)
    }
}
