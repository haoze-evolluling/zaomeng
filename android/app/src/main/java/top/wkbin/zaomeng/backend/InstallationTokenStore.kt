package top.wkbin.zaomeng.backend

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class InstallationTokenStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getOrCreate(): String {
        val stored = preferences.getString(PREFERENCE_TOKEN, null)
        if (!stored.isNullOrBlank()) {
            runCatching { decrypt(stored) }
                .getOrNull()
                ?.takeIf { it.length >= 24 }
                ?.let { return it }
            preferences.edit { remove(PREFERENCE_TOKEN) }
        }

        val tokenBytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val token = Base64.encodeToString(
            tokenBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
        preferences.edit { putString(PREFERENCE_TOKEN, encrypt(token)) }
        return token
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val payload = Base64.encodeToString(encrypted, Base64.NO_WRAP)
        return "$iv:$payload"
    }

    private fun decrypt(value: String): String {
        val parts = value.split(':', limit = 2)
        require(parts.size == 2) { "Invalid encrypted local API token." }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        val decrypted = cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP))
        return decrypted.toString(Charsets.UTF_8)
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "zaomeng_local_api_token_key"
        const val PREFERENCES_NAME = "zaomeng_installation"
        const val PREFERENCE_TOKEN = "local_api_token"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
