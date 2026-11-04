package com.coati.checador.shared.security

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Passphrase de la base de datos local (SQLCipher en el futuro).
 *
 * Se genera una sola vez con 256 bits de entropía y se persiste en el
 * [KeyValueStore] seguro; nunca viaja por red ni se logsuea.
 */
@OptIn(ExperimentalEncodingApi::class)
class DatabaseKeyManager(private val store: KeyValueStore) {

    /** Retorna el passphrase existente o genera y persiste uno nuevo. */
    fun getOrCreatePassphrase(): String {
        store.getString(KEY_DB_PASSPHRASE)?.let { return it }
        val fresh = Base64.encode(Hashing.randomBytes(PASSPHRASE_BYTES))
        store.putString(KEY_DB_PASSPHRASE, fresh)
        return fresh
    }

    /** Solo para borrado voluntario de datos: invalida la BD previa. */
    fun resetPassphrase(): String {
        store.remove(KEY_DB_PASSPHRASE)
        return getOrCreatePassphrase()
    }

    companion object {
        const val KEY_DB_PASSPHRASE = "db_passphrase"
        private const val PASSPHRASE_BYTES = 32
    }
}
