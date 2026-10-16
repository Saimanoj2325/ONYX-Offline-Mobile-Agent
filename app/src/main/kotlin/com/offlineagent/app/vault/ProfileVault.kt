package com.offlineagent.app.vault

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import com.offlineagent.core.runtime.phone.Profile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypted on-device profile (name, phone, email, address, college, roll number, education, resume fields).
 *
 * - AES-256-GCM with a non-exportable Android Keystore key. When the phone has a screen lock the key requires user
 *   authentication (biometric or device credential) within the last 5 minutes, so the file cannot be decrypted without
 *   the user. Without a screen lock the key works without auth and the UI says so.
 * - The decrypted [Profile] lives in memory only while unlocked and is wiped by [lock] / after [UNLOCK_WINDOW_MS].
 * - Cards and passwords are not profile fields; the user can add custom fields and mark them "sensitive" (never
 *   auto-mapped, only offered for an exactly matching label).
 */
class ProfileVault(private val ctx: Context) {
    private val file = File(ctx.filesDir, "profile.vault")
    private val _profile = MutableStateFlow<Profile?>(null)
    val profile: StateFlow<Profile?> = _profile.asStateFlow()
    @Volatile private var unlockedAt = 0L

    val exists: Boolean get() = file.isFile
    val deviceSecure: Boolean get() = (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    /** Unlocked profile, or null when locked / expired. */
    fun current(): Profile? {
        if (System.currentTimeMillis() - unlockedAt > UNLOCK_WINDOW_MS) { lock(); return null }
        return _profile.value
    }

    fun lock() { _profile.value = null; unlockedAt = 0 }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256)
            .apply {
                if (deviceSecure) {
                    setUserAuthenticationRequired(true)
                    if (android.os.Build.VERSION.SDK_INT >= 30) setUserAuthenticationParameters(300, KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL)
                    else @Suppress("DEPRECATION") setUserAuthenticationValidityDurationSeconds(300)
                }
            }.build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
    }

    /** Requires a recent authentication (VaultUnlockActivity). Throws [UserNotAuthenticatedException] otherwise. */
    fun unlock(): Profile {
        val p = if (!file.isFile) Profile() else {
            val bytes = file.readBytes()
            val iv = bytes.copyOfRange(1, 13)
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            Profile.fromJson(String(c.doFinal(bytes, 13, bytes.size - 13), Charsets.UTF_8))
        }
        _profile.value = p
        unlockedAt = System.currentTimeMillis()
        return p
    }

    fun save(p: Profile) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val enc = c.doFinal(p.toJson().toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, "profile.vault.tmp")
        tmp.writeBytes(byteArrayOf(1) + c.iv + enc)
        tmp.renameTo(file)
        _profile.value = p
        unlockedAt = System.currentTimeMillis()
    }

    fun wipe() {
        file.delete(); lock()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }

    companion object {
        const val ALIAS = "offline_agent_profile_vault"
        const val UNLOCK_WINDOW_MS = 5 * 60_000L
    }
}
