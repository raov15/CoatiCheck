package com.coati.checador.shared.security

/**
 * Contrato de cifrado de embeddings, desacoplado de la implementación
 * nativa (AndroidKeyStore / Keychain) para permitir su sustitución en tests.
 */
interface EmbeddingCrypto {
    fun encrypt(plain: ByteArray): ByteArray
    fun decrypt(blob: ByteArray): ByteArray
}
