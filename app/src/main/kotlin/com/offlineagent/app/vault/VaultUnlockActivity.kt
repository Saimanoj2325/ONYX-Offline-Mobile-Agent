package com.offlineagent.app.vault

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.security.keystore.UserNotAuthenticatedException
import android.view.autofill.AutofillManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.offlineagent.app.OfflineAgentApp
import com.offlineagent.app.autofill.OfflineAutofillService

/**
 * Biometric / screen-lock prompt that unlocks the profile vault. Used from Settings and as the autofill
 * "authentication" activity: after unlocking it returns the filled dataset to the requesting app.
 */
class VaultUnlockActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as OfflineAgentApp
        val vault = app.vault
        if (!vault.deviceSecure) { finishUnlocked(); return }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = finishUnlocked()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { setResult(Activity.RESULT_CANCELED); finish() }
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock your profile")
            .setSubtitle("Offline Agent keeps your details encrypted on this phone")
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build())
    }

    private fun finishUnlocked() {
        val app = application as OfflineAgentApp
        try { app.vault.unlock() } catch (e: UserNotAuthenticatedException) { setResult(Activity.RESULT_CANCELED); finish(); return }
        catch (e: Exception) { setResult(Activity.RESULT_CANCELED); finish(); return }
        if (intent.getBooleanExtra(EXTRA_AUTOFILL, false)) {
            val ds = OfflineAutofillService.datasetFor(this, intent)
            if (ds != null) { setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, ds)); finish(); return }
        }
        setResult(Activity.RESULT_OK)
        finish()
    }

    companion object { const val EXTRA_AUTOFILL = "autofill" }
}
