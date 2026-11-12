package com.coati.checador.shared.security

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSData
import platform.Foundation.create
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.errSecItemNotFound
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
import platform.darwin.OSStatus
import platform.posix.memcpy

/**
 * SecureStorage iOS: Keychain con kSecClassGenericPassword y accesibilidad
 * restringida a este dispositivo (sin respaldo a otros equipos).
 */
@OptIn(ExperimentalForeignApi::class)
actual class SecureStorage actual constructor(
    @Suppress("UNUSED_PARAMETER") platformContext: PlatformContext
) : KeyValueStore {

    private val service = CFBridgingRetain(SERVICE)

    private fun baseQuery(key: CFTypeRef?): Map<CFStringRef?, CFTypeRef?> = mapOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to service,
        kSecAttrAccount to key
    )

    actual override fun getString(key: String): String? = cfRetain(key) { cfKey ->
        val out = alloc<CFTypeRefVar>()
        val status = keychainOperation(
            baseQuery(cfKey) + mapOf(
                kSecReturnData to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne
            )
        ) { SecItemCopyMatching(it, out.ptr) }
        when (status) {
            errSecItemNotFound -> null
            errSecSuccess -> (CFBridgingRelease(out.value) as? NSData)?.toByteArray()?.decodeToString()
            else -> throw IllegalStateException("No se pudo leer el llavero (código $status)")
        }
    }

    actual override fun putString(key: String, value: String) {
        remove(key)
        cfRetain(key, value.encodeToByteArray().toNSData()) { cfKey, cfValue ->
            val status = keychainOperation(
                baseQuery(cfKey) + mapOf(
                    kSecValueData to cfValue,
                    kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
                )
            ) { SecItemAdd(it, null) }
            if (status != errSecSuccess) {
                throw IllegalStateException("No se pudo guardar en el llavero (código $status)")
            }
        }
    }

    actual override fun remove(key: String) {
        cfRetain(key) { cfKey ->
            keychainOperation(baseQuery(cfKey)) { SecItemDelete(it) }
        }
    }

    private fun keychainOperation(
        attributes: Map<CFStringRef?, CFTypeRef?>,
        operation: (CFDictionaryRef?) -> OSStatus
    ): OSStatus = memScoped {
        val query = toCFDictionaryRef(attributes)
        try {
            operation(query)
        } finally {
            CFBridgingRelease(query)
        }
    }

    private companion object {
        const val SERVICE = "com.coati.checador.shared"
    }
}

// =========================================================
// Helpers de interop Foundation/CoreFoundation
// =========================================================

@OptIn(ExperimentalForeignApi::class)
private fun MemScope.toCFDictionaryRef(
    attributes: Map<CFStringRef?, CFTypeRef?>
): CFDictionaryRef {
    val keys = allocArrayOf(*attributes.keys.toTypedArray())
    val values = allocArrayOf(*attributes.values.toTypedArray())
    return requireNotNull(
        CFDictionaryCreate(
            kCFAllocatorDefault,
            keys.reinterpret(),
            values.reinterpret(),
            attributes.size.convert(),
            null,
            null
        )
    )
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> cfRetain(value: Any?, block: MemScope.(CFTypeRef?) -> T): T = memScoped {
    val retained = CFBridgingRetain(value)
    try {
        block(retained)
    } finally {
        CFBridgingRelease(retained)
    }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> cfRetain(
    first: Any?,
    second: Any?,
    block: MemScope.(CFTypeRef?, CFTypeRef?) -> T
): T = memScoped {
    val retainedFirst = CFBridgingRetain(first)
    val retainedSecond = CFBridgingRetain(second)
    try {
        block(retainedFirst, retainedSecond)
    } finally {
        CFBridgingRelease(retainedFirst)
        CFBridgingRelease(retainedSecond)
    }
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
