package com.example.sentry.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature

/**
 * Android KeyStore-backed Hardware Security Module helper for Sentry Agent.
 * Ensures private keys are never stored in plaintext and never leave secure hardware.
 */
object CryptoManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "sentry_device_identity_key"
    private const val PREFS_NAME = "sentry_device_prefs"
    private const val PREF_DEVICE_ID = "device_id"

    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
    }

    /**
     * Get or generate a persistent friendly device identifier in format SN-XXXX-XXXX
     */
    fun getOrCreateDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(PREF_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }

        val generated = generateDeviceId("SN")
        prefs.edit().putString(PREF_DEVICE_ID, generated).apply()
        return generated
    }

    /**
     * Generate random Base32 device ID with prefix
     */
    private fun generateDeviceId(prefix: String): String {
        val chars = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        val random = SecureRandom()
        val chunk1 = (1..4).map { chars[random.nextInt(chars.length)] }.joinToString("")
        val chunk2 = (1..4).map { chars[random.nextInt(chars.length)] }.joinToString("")
        return "$prefix-$chunk1-$chunk2"
    }

    /**
     * Ensures an ECDSA P-256 key pair is initialized in Android KeyStore
     */
    fun getOrCreateKeyPair(): PublicKey {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            if (entry != null) {
                return entry.certificate.publicKey
            }
        }

        val kpg = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE
        )
        val parameterSpec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).run {
            setDigests(KeyProperties.DIGEST_SHA256)
            build()
        }

        kpg.initialize(parameterSpec)
        val keyPair = kpg.generateKeyPair()
        return keyPair.public
    }

    /**
     * Get the public key formatted as PEM or Base64 string for backend registration
     */
    fun getPublicKeyPem(): String {
        val publicKey = getOrCreateKeyPair()
        val base64 = Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)
        return "-----BEGIN PUBLIC KEY-----\n$base64\n-----END PUBLIC KEY-----"
    }

    /**
     * Sign a message payload using the hardware-backed private key
     */
    fun signPayload(payload: String): String {
        getOrCreateKeyPair()
        val privateKey = keyStore.getKey(KEY_ALIAS, null) as PrivateKey
        val signature = Signature.getInstance("SHA256withECDSA").apply {
            initSign(privateKey)
            update(payload.toByteArray(Charsets.UTF_8))
        }
        val signedBytes = signature.sign()
        return Base64.encodeToString(signedBytes, Base64.NO_WRAP)
    }
}
