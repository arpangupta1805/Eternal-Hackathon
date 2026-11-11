package com.eternallite.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.eternallite.app.data.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Device keys, generated on first use.
 * The secret keys are stored in the settings table, encrypted with an AES-256-GCM key
 * that lives in the Android Keystore and cannot be exported.
 */
class DeviceKeyStore(private val settings: Settings, private val sodium: Sodium) {
    private val mutex = Mutex()
    private var cached: DeviceKeys? = null

    @Serializable
    private data class Stored(val signPk: String, val signSk: String, val boxPk: String, val boxSk: String)

    suspend fun getOrCreate(): DeviceKeys = mutex.withLock {
        cached?.let { return it }
        val existing = settings.get(Settings.DEVICE_KEYS)?.let(::decrypt)
        val keys = existing ?: generate().also { settings.set(Settings.DEVICE_KEYS, encrypt(it)) }
        cached = keys
        keys
    }

    private fun generate(): DeviceKeys {
        val sign = sodium.signKeypair()
        val box = sodium.boxKeypair()
        return DeviceKeys(sign.pk, sign.sk, box.pk, box.sk)
    }

    private fun encrypt(keys: DeviceKeys): String {
        val plain = Json.encodeToString(
            Stored.serializer(),
            Stored(B64.encode(keys.signPk), B64.encode(keys.signSk), B64.encode(keys.boxPk), B64.encode(keys.boxSk)),
        ).toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        return B64.encode(cipher.iv) + ":" + B64.encode(cipher.doFinal(plain))
    }

    /** Returns null if the blob cannot be read (for example the Keystore key was lost); new keys are then made. */
    private fun decrypt(blob: String): DeviceKeys? = try {
        val (ivText, dataText) = blob.split(":", limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, B64.decode(ivText)))
        val stored = Json.decodeFromString(Stored.serializer(), cipher.doFinal(B64.decode(dataText)).toString(Charsets.UTF_8))
        DeviceKeys(B64.decode(stored.signPk)!!, B64.decode(stored.signSk)!!, B64.decode(stored.boxPk)!!, B64.decode(stored.boxSk)!!)
    } catch (e: Exception) {
        null
    }

    private fun wrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "eternal_lite_device_keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
