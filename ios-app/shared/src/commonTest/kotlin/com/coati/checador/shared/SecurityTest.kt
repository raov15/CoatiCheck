package com.coati.checador.shared

import com.coati.checador.shared.security.AdminPinManager
import com.coati.checador.shared.security.DatabaseKeyManager
import com.coati.checador.shared.security.Hashing
import com.coati.checador.shared.security.KeyValueStore
import com.coati.checador.shared.security.constantTimeEquals
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Almacenamiento en memoria para tests. */
class FakeKeyValueStore : KeyValueStore {
    private val map = mutableMapOf<String, String>()
    override fun getString(key: String): String? = map[key]
    override fun putString(key: String, value: String) {
        map[key] = value
    }

    override fun remove(key: String) {
        map.remove(key)
    }
}

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

class HashingTest {

    @Test
    fun `pbkdf2 coincide con vector oficial RFC 7914`() {
        // PBKDF2-HMAC-SHA-256, P="passwd", S="salt", c=1, dkLen=64
        val dk = Hashing.pbkdf2Sha256(
            password = "passwd",
            salt = "salt".encodeToByteArray(),
            iterations = 1,
            keyLengthBytes = 64
        )
        assertEquals(
            "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc" +
                "49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783",
            dk.toHex()
        )
    }

    @Test
    fun `pbkdf2 con 2 iteraciones difiere de 1`() {
        val one = Hashing.pbkdf2Sha256("pin1234", byteArrayOf(1, 2), 1, 32)
        val two = Hashing.pbkdf2Sha256("pin1234", byteArrayOf(1, 2), 2, 32)
        assertFalse(one.contentEquals(two))
    }

    @Test
    fun `randomBytes genera longitud y variabilidad`() {
        val a = Hashing.randomBytes(32)
        val b = Hashing.randomBytes(32)
        assertEquals(32, a.size)
        assertEquals(32, b.size)
        assertFalse(a.contentEquals(b), "Dos llamadas no deben producir el mismo contenido")
    }
}

class AdminPinManagerTest {

    private val store = FakeKeyValueStore()
    private val manager = AdminPinManager(store, iterations = 1_000)

    @Test
    fun `setPin y verifyPin correcto`() {
        manager.setPin("1234")
        assertTrue(manager.hasPin())
        assertTrue(manager.verifyPin("1234"))
    }

    @Test
    fun `verifyPin con pin incorrecto falla`() {
        manager.setPin("1234")
        assertFalse(manager.verifyPin("9999"))
        assertFalse(manager.verifyPin("12345"))
    }

    @Test
    fun `no guarda el pin en texto plano`() {
        manager.setPin("9876")
        val values = listOf(
            store.getString(AdminPinManager.KEY_PIN_SALT),
            store.getString(AdminPinManager.KEY_PIN_HASH)
        )
        assertTrue(values.none { it != null && it.contains("9876") })
    }

    @Test
    fun `verifyPin sin pin configurado retorna false`() {
        assertFalse(manager.verifyPin("1234"))
    }

    @Test
    fun `rechaza pin corto o no numerico`() {
        assertFailsWith<IllegalArgumentException> { manager.setPin("123") }
        assertFailsWith<IllegalArgumentException> { manager.setPin("12a4") }
        assertFailsWith<IllegalArgumentException> { manager.setPin("123456789") }
        assertFalse(manager.hasPin())
    }

    @Test
    fun `clearPin elimina estado`() {
        manager.setPin("1234")
        manager.clearPin()
        assertFalse(manager.hasPin())
        assertFalse(manager.verifyPin("1234"))
    }

    @Test
    fun `constantTimeEquals comportamientos`() {
        assertTrue(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1)))
    }
}

@OptIn(ExperimentalEncodingApi::class)
class DatabaseKeyManagerTest {

    @Test
    fun `passphrase estable entre llamadas`() {
        val store = FakeKeyValueStore()
        val manager = DatabaseKeyManager(store)
        assertEquals(manager.getOrCreatePassphrase(), manager.getOrCreatePassphrase())
    }

    @Test
    fun `passphrase estable entre instancias con el mismo store`() {
        val store = FakeKeyValueStore()
        val first = DatabaseKeyManager(store).getOrCreatePassphrase()
        val second = DatabaseKeyManager(store).getOrCreatePassphrase()
        assertEquals(first, second)
    }

    @Test
    fun `passphrase tiene 256 bits de entropia`() {
        val passphrase = DatabaseKeyManager(FakeKeyValueStore()).getOrCreatePassphrase()
        assertEquals(32, Base64.decode(passphrase).size)
    }

    @Test
    fun `resetPassphrase genera uno nuevo`() {
        val store = FakeKeyValueStore()
        val manager = DatabaseKeyManager(store)
        val original = manager.getOrCreatePassphrase()
        val reset = manager.resetPassphrase()
        assertFalse(original == reset)
        assertEquals(reset, manager.getOrCreatePassphrase())
    }
}
