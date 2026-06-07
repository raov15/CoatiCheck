package com.coati.checador.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KeystoreHelper @Inject constructor() {

    companion object {
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val DATABASE_KEY_ALIAS = "coati_db_key"
        private const val EMBEDDING_KEY_ALIAS = "coati_embedding_key"
    }

    fun getOrCreateDatabaseKey(): SecretKey {
        return getOrCreateKey(DATABASE_KEY_ALIAS)
    }

    fun getOrCreateEmbeddingKey(): SecretKey {
        return getOrCreateKey(EMBEDDING_KEY_ALIAS)
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        if (keyStore.containsAlias(alias)) {
            return (keyStore.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
        )
        return keyGenerator.generateKey()
    }
}
