package com.coati.checador.shared.security

/**
 * Almacenamiento clave-valor de texto simple.
 *
 * Permite que los gestores de seguridad trabajen sobre una interfaz
 * testeable, mientras la implementación real (Keychain en iOS,
 * EncryptedSharedPreferences en Android) cifra los datos en reposo.
 */
interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}
