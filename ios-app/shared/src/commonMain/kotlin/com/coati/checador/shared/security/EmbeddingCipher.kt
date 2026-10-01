package com.coati.checador.shared.security

/**
 * Cifrado de embeddings faciales antes de persistirlos en la base local.
 *
 * - Android: AES-256-GCM con clave no exportable en AndroidKeyStore.
 * - iOS: AES-256-CBC + HMAC-SHA256 (encrypt-then-MAC) con clave maestra
 *   aleatoria guardada en Keychain.
 *
 * Formato del blob: plataforma-specific; en iOS = IV(16) + ciphertext + HMAC(32).
 */
expect class EmbeddingCipher(store: KeyValueStore) {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}
