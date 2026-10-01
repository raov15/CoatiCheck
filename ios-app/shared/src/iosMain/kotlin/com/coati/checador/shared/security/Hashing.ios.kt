package com.coati.checador.shared.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecRandomDefault

/**
 * Hashing iOS: PBKDF2-HMAC-SHA256 construido sobre CCHmac de CommonCrypto
 * (algoritmo RFC 8018, validado contra vectores oficiales en commonTest)
 * y bytes aleatorios de SecRandomCopyBytes.
 */
@OptIn(ExperimentalForeignApi::class)
actual object Hashing {

    actual fun pbkdf2Sha256(
        password: String,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int
    ): ByteArray {
        require(iterations >= 1) { "iterations debe ser >= 1" }
        require(keyLengthBytes > 0) { "keyLengthBytes debe ser > 0" }

        val hashLen = 32
        val blocks = (keyLengthBytes + hashLen - 1) / hashLen
        val pw = password.encodeToByteArray()
        val derived = ByteArray(blocks * hashLen)

        for (block in 1..blocks) {
            val counter = byteArrayOf(
                ((block ushr 24) and 0xff).toByte(),
                ((block ushr 16) and 0xff).toByte(),
                ((block ushr 8) and 0xff).toByte(),
                (block and 0xff).toByte()
            )
            var u = hmacSha256(pw, salt + counter)
            val acc = u.copyOf()
            repeat(iterations - 1) {
                u = hmacSha256(pw, u)
                for (i in acc.indices) {
                    acc[i] = (acc[i].toInt() xor u[i].toInt()).toByte()
                }
            }
            acc.copyInto(derived, (block - 1) * hashLen)
        }
        return derived.copyOf(keyLengthBytes)
    }

    actual fun randomBytes(count: Int): ByteArray {
        require(count > 0) { "count debe ser > 0" }
        val out = ByteArray(count)
        val status = out.usePinned { pinned ->
            SecRandomCopyBytes(kSecRandomDefault, count.toULong(), pinned.addressOf(0))
        }
        check(status == errSecSuccess) { "SecRandomCopyBytes falló: $status" }
        return out
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = ByteArray(HMAC_SHA256_LEN)
        key.usePinned { keyPin ->
            data.usePinned { dataPin ->
                mac.usePinned { macPin ->
                    CCHmac(
                        kCCHmacAlgSHA256,
                        keyPin.addressOf(0),
                        key.size.toULong(),
                        dataPin.addressOf(0),
                        data.size.toULong(),
                        macPin.addressOf(0)
                    )
                }
            }
        }
        return mac
    }

    private const val HMAC_SHA256_LEN = 32
}
