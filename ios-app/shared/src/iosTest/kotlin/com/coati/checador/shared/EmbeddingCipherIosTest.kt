package com.coati.checador.shared

import com.coati.checador.shared.security.EmbeddingCipher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tests de EmbeddingCipher solo en iOS (la implementación Android usa
 * AndroidKeyStore, que no está disponible en tests JVM).
 */
class EmbeddingCipherIosTest {

    private fun newCipher(store: FakeKeyValueStore = FakeKeyValueStore()) =
        EmbeddingCipher(store)

    @Test
    fun `roundtrip encrypt-decrypt restaura el embedding`() {
        val cipher = newCipher()
        val embedding = ByteArray(128) { (it * 7 % 255 - 127).toByte() }

        val blob = cipher.encrypt(embedding)

        assertTrue(blob.contentEquals(embedding).not(), "El blob cifrado no debe ser el texto plano")
        assertTrue(embedding.contentEquals(cipher.decrypt(blob)))
    }

    @Test
    fun `cifrado con IV aleatorio produce blobs distintos`() {
        val cipher = newCipher()
        val embedding = ByteArray(128) { 42 }

        val blob1 = cipher.encrypt(embedding)
        val blob2 = cipher.encrypt(embedding)

        assertTrue(!blob1.contentEquals(blob2), "IV/HMAC aleatorios deben diferenciar blobs")
        assertEquals(embedding.toList(), cipher.decrypt(blob1).toList())
        assertEquals(embedding.toList(), cipher.decrypt(blob2).toList())
    }

    @Test
    fun `detecta blob alterado en el ciphertext`() {
        val cipher = newCipher()
        val blob = cipher.encrypt(ByteArray(128) { 1 })
        val tampered = blob.copyOf().also { it[20] = (it[20].toInt() xor 0x01).toByte() }

        assertFailsWith<IllegalStateException> { cipher.decrypt(tampered) }
    }

    @Test
    fun `detecta blob alterado en el HMAC`() {
        val cipher = newCipher()
        val blob = cipher.encrypt(ByteArray(128) { 2 })
        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }

        assertFailsWith<IllegalStateException> { cipher.decrypt(tampered) }
    }

    @Test
    fun `la clave se reutiliza entre instancias con el mismo store`() {
        val store = FakeKeyValueStore()
        val cipher1 = EmbeddingCipher(store)
        val blob = cipher1.encrypt(ByteArray(128) { 3 })
        val cipher2 = EmbeddingCipher(store)

        assertTrue(ByteArray(128) { 3 }.contentEquals(cipher2.decrypt(blob)))
    }
}
