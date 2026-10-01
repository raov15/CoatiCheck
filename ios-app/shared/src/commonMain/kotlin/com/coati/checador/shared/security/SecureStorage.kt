package com.coati.checador.shared.security

/**
 * Almacenamiento seguro gestionado por el sistema operativo:
 * - iOS: Keychain (kSecClassGenericPassword, solo este dispositivo).
 * - Android: EncryptedSharedPreferences con clave maestra en Keystore.
 *
 * Corrección respecto a Android: el token JWT y el passphrase de la base
 * se guardan aquí cifrados, no en columnas de texto plano de la BD.
 */
expect class SecureStorage(platformContext: PlatformContext) : KeyValueStore {
    override fun getString(key: String): String?
    override fun putString(key: String, value: String)
    override fun remove(key: String)
}
