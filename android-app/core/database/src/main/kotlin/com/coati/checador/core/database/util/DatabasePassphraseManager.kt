package com.coati.checador.core.database.util

import android.content.Context
import android.util.Base64
import com.coati.checador.core.security.KeystoreHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Genera y gestiona la passphrase de SQLCipher usando Android Keystore.
 *
 * Flujo:
 * 1. Primera ejecución: genera 32 bytes aleatorios, los cifra con AES-256-GCM
 *    usando una clave del Keystore, y guarda el resultado en SharedPreferences.
 * 2. Ejecuciones posteriores: lee la passphrase cifrada, la descifra con la
 *    misma clave del Keystore y la devuelve.
 *
 * La clave del Keystore nunca sale del chip de seguridad del dispositivo.
 */
@Singleton
class DatabasePassphraseManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keystoreHelper: KeystoreHelper
) {
    companion object {
        private const val PREFS_NAME = "coati_db_security"
        private const val KEY_ENCRYPTED_PASSPHRASE = "enc_passphrase"
        private const val KEY_IV = "enc_passphrase_iv"
        private const val PASSPHRASE_BYTES = 32
        private const val GCM_TAG_LENGTH = 128
    }

    /**
     * Devuelve la passphrase de la base de datos, creándola si es la primera vez.
     * Llamar en un contexto non-UI (coroutine IO o proveedor Hilt).
     */
    fun getOrCreatePassphrase(): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val encryptedB64 = prefs.getString(KEY_ENCRYPTED_PASSPHRASE, null)
        val ivB64 = prefs.getString(KEY_IV, null)

        return if (encryptedB64 != null && ivB64 != null) {
            decryptPassphrase(
                encrypted = Base64.decode(encryptedB64, Base64.DEFAULT),
                iv = Base64.decode(ivB64, Base64.DEFAULT)
            )
        } else {
            val newPassphrase = ByteArray(PASSPHRASE_BYTES).also { SecureRandom().nextBytes(it) }
            val (encrypted, iv) = encryptPassphrase(newPassphrase)
            prefs.edit()
                .putString(KEY_ENCRYPTED_PASSPHRASE, Base64.encodeToString(encrypted, Base64.DEFAULT))
                .putString(KEY_IV, Base64.encodeToString(iv, Base64.DEFAULT))
                .apply()
            newPassphrase
        }
    }

    private fun encryptPassphrase(passphrase: ByteArray): Pair<ByteArray, ByteArray> {
        val key = keystoreHelper.getOrCreateDatabaseKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(passphrase)
        return Pair(encrypted, iv)
    }

    private fun decryptPassphrase(encrypted: ByteArray, iv: ByteArray): ByteArray {
        val key = keystoreHelper.getOrCreateDatabaseKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, iv))
        return cipher.doFinal(encrypted)
    }
}
