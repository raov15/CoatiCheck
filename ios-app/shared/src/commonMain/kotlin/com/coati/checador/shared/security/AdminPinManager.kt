package com.coati.checador.shared.security

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Gestión del PIN de administrador.
 *
 * Corrección del bug crítico de Android: el PIN nunca se guarda en texto
 * plano; se persiste únicamente salt + hash PBKDF2-SHA256.
 */
@OptIn(ExperimentalEncodingApi::class)
class AdminPinManager(
    private val store: KeyValueStore,
    private val iterations: Int = DEFAULT_ITERATIONS
) {

    fun hasPin(): Boolean = store.getString(KEY_PIN_HASH) != null

    fun setPin(pin: String) {
        require(pin.length in MIN_LENGTH..MAX_LENGTH) {
            "El PIN debe tener entre $MIN_LENGTH y $MAX_LENGTH dígitos"
        }
        require(pin.all { it.isDigit() }) {
            "El PIN solo puede contener dígitos"
        }
        val salt = Hashing.randomBytes(SALT_BYTES)
        val hash = Hashing.pbkdf2Sha256(pin, salt, iterations, HASH_BYTES)
        store.putString(KEY_PIN_SALT, Base64.encode(salt))
        store.putString(KEY_PIN_HASH, Base64.encode(hash))
    }

    fun verifyPin(pin: String): Boolean {
        val salt = store.getString(KEY_PIN_SALT)?.let(::decodeBase64) ?: return false
        val stored = store.getString(KEY_PIN_HASH)?.let(::decodeBase64) ?: return false
        val candidate = Hashing.pbkdf2Sha256(pin, salt, iterations, stored.size)
        return constantTimeEquals(candidate, stored)
    }

    fun clearPin() {
        store.remove(KEY_PIN_SALT)
        store.remove(KEY_PIN_HASH)
    }

    private fun decodeBase64(value: String): ByteArray? =
        runCatching { Base64.decode(value) }.getOrNull()

    companion object {
        const val KEY_PIN_SALT = "admin_pin_salt"
        const val KEY_PIN_HASH = "admin_pin_hash"
        const val DEFAULT_ITERATIONS = 120_000
        const val MIN_LENGTH = 4
        const val MAX_LENGTH = 8
        private const val SALT_BYTES = 16
        private const val HASH_BYTES = 32
    }
}

/** Comparación en tiempo constante para evitar timing attacks. */
fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
    return diff == 0
}
