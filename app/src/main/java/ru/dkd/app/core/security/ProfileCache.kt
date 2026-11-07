package ru.dkd.app.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal data class CachedProfile(
    val name: String,
    val role: String,
    val unreadCount: Int
)

/** Small encrypted last-known profile snapshot for offline app startup. */
internal class ProfileCache(context: Context) {
    private val preferences = context.getSharedPreferences("dkd_profile_cache", Context.MODE_PRIVATE)
    private val keyAlias = "dkd_profile_cache_key"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun read(): CachedProfile? = runCatching {
        val iv = preferences.getString("iv", null) ?: return null
        val encrypted = preferences.getString("payload", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            key(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))
        )
        val plain = cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP))
        val json = JSONObject(String(plain, Charsets.UTF_8))
        CachedProfile(
            name = json.optString("name", "Гражданин"),
            role = json.optString("role", "Участник ДКД"),
            unreadCount = json.optInt("unread_count", 0).coerceAtLeast(0)
        )
    }.getOrNull()

    fun write(profile: CachedProfile) {
        runCatching {
            val json = JSONObject()
                .put("name", profile.name)
                .put("role", profile.role)
                .put("unread_count", profile.unreadCount)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            preferences.edit()
                .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .putString("payload", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .apply()
        }
    }

    fun clear() {
        preferences.edit().clear().apply()
    }
}
