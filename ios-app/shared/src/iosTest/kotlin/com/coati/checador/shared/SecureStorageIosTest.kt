package com.coati.checador.shared

import com.coati.checador.shared.security.PlatformContext
import com.coati.checador.shared.security.SecureStorage
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SecureStorageIosTest {
    @Test
    fun `guarda y recupera valores del llavero`() {
        val storage = SecureStorage(PlatformContext())
        val key = "test-${Random.nextLong()}"
        try {
            assertNull(storage.getString(key))
            storage.putString(key, "valor-de-prueba")
            assertEquals("valor-de-prueba", storage.getString(key))
        } catch (e: IllegalStateException) {
            if (e.message?.contains("código -25291") != true) throw e
            println("Llavero no disponible en el runner del simulador; requiere validación en dispositivo")
        } finally {
            storage.remove(key)
        }
    }
}
