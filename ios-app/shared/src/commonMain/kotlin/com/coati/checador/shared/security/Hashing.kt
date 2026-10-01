package com.coati.checador.shared.security

/**
 * Primitivas criptográficas por plataforma.
 * - Android: javax.crypto (PBKDF2WithHmacSHA256) + SecureRandom.
 * - iOS: CommonCrypto (CCHmac / CCKeyDerivationPBKDF) + SecRandomCopyBytes.
 */
expect object Hashing {
    fun pbkdf2Sha256(password: String, salt: ByteArray, iterations: Int, keyLengthBytes: Int): ByteArray
    fun randomBytes(count: Int): ByteArray
}
