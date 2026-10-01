package com.coati.checador.shared.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

actual object Hashing {

    actual fun pbkdf2Sha256(
        password: String,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int
    ): ByteArray {
        require(iterations >= 1) { "iterations debe ser >= 1" }
        require(keyLengthBytes > 0) { "keyLengthBytes debe ser > 0" }
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(
                PBEKeySpec(password.toCharArray(), salt, iterations, keyLengthBytes * 8)
            )
            .encoded
    }

    actual fun randomBytes(count: Int): ByteArray {
        require(count > 0) { "count debe ser > 0" }
        return ByteArray(count).also { SecureRandom().nextBytes(it) }
    }
}
