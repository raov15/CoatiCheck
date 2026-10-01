package com.coati.checador.shared.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSCopyingProtocol
import platform.Foundation.NSData
import platform.Foundation.NSMutableDictionary
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * SecureStorage iOS: Keychain con kSecClassGenericPassword y accesibilidad
 * restringida a este dispositivo (sin respaldo a otros equipos).
 */
@OptIn(ExperimentalForeignApi::class)
actual class SecureStorage actual constructor(
    @Suppress("UNUSED_PARAMETER") platformContext: PlatformContext
) : KeyValueStore {

    private fun baseQuery(key: String): Map<Any?, Any?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to SERVICE,
        kSecAttrAccount to key
    )

    actual override fun getString(key: String): String? = memScoped {
        val query = baseQuery(key) + mapOf(
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne
        )
        val out = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query.toCFDictionaryRef(), out.ptr)
        if (status != errSecSuccess) {
            null
        } else {
            (CFBridgingRelease(out.value) as? NSData)?.toByteArray()?.decodeToString()
        }
    }

    actual override fun putString(key: String, value: String) {
        remove(key)
        val attrs = baseQuery(key) + mapOf(
            kSecValueData to value.encodeToByteArray().toNSData(),
            kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        )
        SecItemAdd(attrs.toCFDictionaryRef(), null)
    }

    actual override fun remove(key: String) {
        SecItemDelete(baseQuery(key).toCFDictionaryRef())
    }

    private companion object {
        const val SERVICE = "com.coati.checador.shared"
    }
}

// =========================================================
// Helpers de interop Foundation/CoreFoundation
// =========================================================

/** Mapa Kotlin -> CFDictionary (NSDictionary toll-free bridge). */
@OptIn(ExperimentalForeignApi::class)
internal fun Map<Any?, Any?>.toCFDictionaryRef(): CFDictionaryRef {
    val dict = NSMutableDictionary()
    forEach { (key, value) ->
        if (key != null && value != null) {
            dict.setObject(value as NSObject, forKey = key as NSCopyingProtocol)
        }
    }
    return CFBridgingRetain(dict) as CFDictionaryRef
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size <= 0) return ByteArray(0)
    val out = ByteArray(size)
    out.usePinned { pinned ->
        memcpy(pinned.addressOf(0), bytes, size.toULong())
    }
    return out
}
