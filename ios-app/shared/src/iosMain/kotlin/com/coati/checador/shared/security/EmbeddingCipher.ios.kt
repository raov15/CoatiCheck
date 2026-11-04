package com.coati.checador.shared.security

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.ULongVarOf
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCBlockSizeAES128
import platform.CoreCrypto.kCCDecrypt
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCKeySizeAES256
import platform.CoreCrypto.kCCOptionPKCS7Padding
import platform.CoreCrypto.kCCSuccess

/**
 * EmbeddingCipher iOS: AES-256-CBC con PKCS7 + HMAC-SHA256
 * (encrypt-then-MAC) usando CommonCrypto, ya que CryptoKit es
 * exclusivo de Swift y no importa desde Kotlin/Native.
 *
 * Clave maestra de 64 bytes (32 AES + 32 HMAC) generada una vez con
 * SecRandomCopyBytes y persistida en el KeyValueStore (Keychain).
 *
 * Formato del blob: IV(16) + ciphertext + HMAC(32).
 * El tamaño del ciphertext es computable con PKCS7, por lo que no se
 * necesita el parámetro dataOutMoved de CCCrypt.
 */
@OptIn(ExperimentalEncodingApi::class, ExperimentalForeignApi::class)
actual class EmbeddingCipher actual constructor(
    private val store: KeyValueStore
) : EmbeddingCrypto {

    private fun masterKey(): ByteArray {
        val existing = store.getString(KEY_MASTER)
            ?.let { runCatching { Base64.decode(it) }.getOrNull() }
        if (existing != null && existing.size == MASTER_KEY_BYTES) return existing

        val fresh = Hashing.randomBytes(MASTER_KEY_BYTES)
        store.putString(KEY_MASTER, Base64.encode(fresh))
        return fresh
    }

    actual override fun encrypt(plain: ByteArray): ByteArray = memScoped {
        val master = masterKey()
        val encKey = master.copyOfRange(0, AES_KEY_BYTES)
        val macKey = master.copyOfRange(AES_KEY_BYTES, MASTER_KEY_BYTES)
        val iv = Hashing.randomBytes(BLOCK_SIZE)

        // PKCS7 siempre agrega un bloque completo si plain.size % 16 == 0.
        val ciphertextLen = (plain.size / BLOCK_SIZE + 1) * BLOCK_SIZE
        val out = ByteArray(ciphertextLen)
        val written = alloc<ULongVarOf<kotlin.ULong>>()
        val status = out.usePinned { outPin ->
            iv.usePinned { ivPin ->
                encKey.usePinned { keyPin ->
                    plain.usePinned { dataPin ->
                        CCCrypt(
                            kCCEncrypt,
                            kCCAlgorithmAES,
                            kCCOptionPKCS7Padding,
                            keyPin.addressOf(0),
                            encKey.size.toULong(),
                            ivPin.addressOf(0),
                            dataPin.addressOf(0),
                            plain.size.toULong(),
                            outPin.addressOf(0),
                            out.size.toULong(),
                            written.ptr
                        )
                    }
                }
            }
        }
        check(status == kCCSuccess) { "AES encrypt falló: $status" }
        check(written.value.toInt() == ciphertextLen) {
            "AES encrypt escribió ${written.value.toInt()} bytes, se esperaban $ciphertextLen"
        }

        val mac = hmac(macKey, iv + out)
        iv + out + mac
    }

    actual override fun decrypt(blob: ByteArray): ByteArray = memScoped {
        require(blob.size > BLOCK_SIZE + HMAC_SIZE) { "Blob de embedding inválido" }

        val master = masterKey()
        val encKey = master.copyOfRange(0, AES_KEY_BYTES)
        val macKey = master.copyOfRange(AES_KEY_BYTES, MASTER_KEY_BYTES)

        val iv = blob.copyOfRange(0, BLOCK_SIZE)
        val ciphertext = blob.copyOfRange(BLOCK_SIZE, blob.size - HMAC_SIZE)
        val mac = blob.copyOfRange(blob.size - HMAC_SIZE, blob.size)

        check(constantTimeEquals(mac, hmac(macKey, iv + ciphertext))) {
            "Blob de embedding alterado"
        }
        check(ciphertext.size % BLOCK_SIZE == 0) { "Tamaño de ciphertext inválido" }

        val padded = ByteArray(ciphertext.size)
        val written = alloc<ULongVarOf<kotlin.ULong>>()
        val status = padded.usePinned { outPin ->
            iv.usePinned { ivPin ->
                encKey.usePinned { keyPin ->
                    ciphertext.usePinned { dataPin ->
                        CCCrypt(
                            kCCDecrypt,
                            kCCAlgorithmAES,
                            kCCOptionPKCS7Padding,
                            keyPin.addressOf(0),
                            encKey.size.toULong(),
                            ivPin.addressOf(0),
                            dataPin.addressOf(0),
                            ciphertext.size.toULong(),
                            outPin.addressOf(0),
                            padded.size.toULong(),
                            written.ptr
                        )
                    }
                }
            }
        }
        check(status == kCCSuccess) { "AES decrypt falló: $status" }
        // CommonCrypto con kCCOptionPKCS7Padding ya elimina el padding al
        // descifrar; dataOutMoved indica la longitud del texto plano real.
        val plainLen = written.value.toInt()
        check(plainLen in 1..padded.size) { "Tamaño de salida decrypt inválido: $plainLen" }
        padded.copyOf(plainLen)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val mac = ByteArray(HMAC_SIZE)
        key.usePinned { keyPin ->
            data.usePinned { dataPin ->
                mac.usePinned { macPin ->
                    platform.CoreCrypto.CCHmac(
                        platform.CoreCrypto.kCCHmacAlgSHA256,
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

    private companion object {
        const val KEY_MASTER = "embedding_master_key"
        const val MASTER_KEY_BYTES = 64
        val AES_KEY_BYTES = kCCKeySizeAES256.toInt()
        const val BLOCK_SIZE = 16
        const val HMAC_SIZE = 32
    }
}
