package dev.openstream.tv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class CredentialVault(context: Context) {
    private val prefs = context.getSharedPreferences("openstream_secrets", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun saveXtreamPassword(password: String) {
        if (password.isBlank()) {
            prefs.edit().remove(KEY_PASSWORD).apply()
            return
        }
        val payload = encrypt(password.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY_PASSWORD, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
    }

    fun loadXtreamPassword(): String {
        val encoded = prefs.getString(KEY_PASSWORD, null) ?: return ""
        return runCatching {
            decrypt(Base64.decode(encoded, Base64.NO_WRAP)).toString(Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun encrypt(bytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, getOrCreateKey()) }
        return cipher.iv + cipher.doFinal(bytes)
    }

    fun decrypt(payload: ByteArray): ByteArray {
        require(payload.size > IV_SIZE) { "Datos cifrados no válidos" }
        val iv = payload.copyOfRange(0, IV_SIZE)
        val encrypted = payload.copyOfRange(IV_SIZE, payload.size)
        val key = keyStore.getKey(KEY_ALIAS, null) as SecretKey
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        }
        return cipher.doFinal(encrypted)
    }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEY_ALIAS = "openstream_xtream_credentials"
        private const val KEY_PASSWORD = "xtream_password"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
    }
}
