package com.offlineagent.core.apps

import com.offlineagent.core.apps.AppSafetyPolicy.Category
import com.offlineagent.core.apps.AppSafetyPolicy.SafetyVerdict
import com.offlineagent.core.apps.AppSafetyPolicy.Use
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppSafetyPolicyTest {
    private val p = testPolicy(userBlocked = setOf("com.example.secret"))
    private fun cat(v: SafetyVerdict) = (v as? SafetyVerdict.Blocked)?.category

    @Test fun blocklistFileParsesAllCategories() {
        val rules = AppSafetyPolicy.defaultRules
        assertTrue(rules.size > 100)
        val cats = rules.map { it.category }.toSet()
        listOf(Category.PAYMENT, Category.PASSWORD_MANAGER, Category.AUTHENTICATOR, Category.SELF, Category.SYSTEM_ADMIN, Category.STORE_PURCHASE).forEach { assertTrue("$it", it in cats) }
        // malformed lines are ignored, not fatal
        assertEquals(1, AppSafetyPolicy.parse("# c\npayment package a.b\nnonsense\nbogus package x\npayment wat y").size)
    }

    @Test fun paymentAndBankingAppsBlocked() {
        assertEquals(Category.PAYMENT, cat(p.checkPackage("com.phonepe.app")))
        assertEquals(Category.PAYMENT, cat(p.checkPackage("com.google.android.apps.nbu.paisa.user", "Google Pay")))
        assertEquals(Category.PAYMENT, cat(p.checkPackage("in.org.npci.upiapp")))
        assertEquals(Category.PAYMENT, cat(p.checkPackage("com.example.bank.mobile")))
        assertEquals(Category.PAYMENT, cat(p.checkPackage("com.kotak.anything")))       // prefix
        assertEquals(Category.PAYMENT, cat(p.checkPackage("com.foo.upi_app")))           // word
        assertEquals(Category.PAYMENT, cat(p.checkPackage("com.example.x", "Amazon Pay"))) // label word
        assertTrue(p.checkPackage("com.whatsapp", "WhatsApp").allowed)
        assertTrue(p.checkPackage("com.example.display", "Display Tools").allowed)   // "pay" inside a word is not a match
        assertTrue(p.checkPackage("com.google.android.youtube", "YouTube").allowed)
    }

    @Test fun passwordManagersAuthenticatorsSelfAndSettingsBlocked() {
        assertEquals(Category.PASSWORD_MANAGER, cat(p.checkPackage("com.x8bit.bitwarden")))
        assertEquals(Category.PASSWORD_MANAGER, cat(p.checkPackage("com.example.vaultapp", "My Passwords")))
        assertEquals(Category.AUTHENTICATOR, cat(p.checkPackage("com.google.android.apps.authenticator2")))
        assertEquals(Category.AUTHENTICATOR, cat(p.checkPackage("com.azure.authenticator")))
        assertEquals(Category.SELF, cat(p.checkPackage("com.offlineagent.app")))
        assertEquals(Category.SELF, cat(p.checkPackage("com.offlineagent.app.debug")))
        assertEquals(Category.SYSTEM_ADMIN, cat(p.checkPackage("com.android.settings")))
        assertEquals(Category.SYSTEM_ADMIN, cat(p.checkPackage("com.google.android.permissioncontroller")))
        assertEquals(Category.USER, cat(p.checkPackage("com.example.secret")))
    }

    @Test fun storeIsScreenOnly() {
        assertTrue(p.checkPackage("com.android.vending", "Play Store", Use.INTENT).allowed)
        assertEquals(Category.STORE_PURCHASE, cat(p.checkPackage("com.android.vending", "Play Store", Use.SCREEN)))
    }

    @Test fun upiAndPayLinksBlocked() {
        val bad = listOf(
            "upi://pay?pa=merchant@okaxis&pn=Shop&am=500&cu=INR",
            "UPI://PAY?pa=a@b",
            "upi:pay?pa=a@ybl",
            "phonepe://pay?pa=x@ybl",
            "tez://upi/pay?pa=x@okicici",
            "paytmmp://pay?pa=x@paytm",
            "gpay://upi/pay?pa=x@oksbi",
            "https://example.com/redirect?to=upi%3A%2F%2Fpay%3Fpa%3Dx%40ybl",
            "https://example.com/r?u=upi%253A%252F%252Fpay%253Fpa%253Dx%2540ybl", // double encoded
            "https://pay.google.com/gp/v/save/abc",
            "https://paytm.me/abc",
            "https://rzp.io/l/xyz",
            "https://shop.example.com/checkout?pa=shop@okhdfc&pn=Shop&am=10",
            "tel:*99%23", "tel:*99#", "tel:%2A123%23",
            "intent://pay#Intent;scheme=upi;package=com.phonepe.app;end",
            "otpauth://totp/x?secret=ABC",
            "file:///sdcard/x", "content://com.x/y", "javascript:alert(1)", "data:text/html,hi",
            "android-app://com.phonepe.app/https/phonepe.com",
            "package:com.offlineagent.app",
        )
        bad.forEach { assertFalse(it, p.checkUri(it).allowed) }
        val good = listOf("https://www.youtube.com/watch?v=x", "geo:0,0?q=charminar", "mailto:a@b.com", "tel:+919876543210", "market://details?id=com.whatsapp",
            "https://wa.me/919876543210?text=hi", "spotify:search:lofi", "https://example.com/?pa=nothing")
        good.forEach { assertTrue(it, p.checkUri(it).allowed) }
    }

    @Test fun adminActionsBlocked() {
        assertFalse(p.checkAction("android.settings.ACCESSIBILITY_SETTINGS").allowed)
        assertFalse(p.checkAction("android.app.action.ADD_DEVICE_ADMIN").allowed)
        assertFalse(p.checkAction("android.settings.MANAGE_UNKNOWN_APP_SOURCES").allowed)
        assertTrue(p.checkAction(AppIntent.ACTION_VIEW).allowed)
    }

    @Test fun sensitiveNotificationSources() {
        assertTrue(p.isSensitiveNotificationSource("com.phonepe.app"))
        assertTrue(p.isSensitiveNotificationSource("com.google.android.apps.authenticator2"))
        assertTrue(p.isSensitiveNotificationSource("com.x8bit.bitwarden"))
        assertFalse(p.isSensitiveNotificationSource("com.whatsapp", "WhatsApp"))
        assertFalse(p.isSensitiveNotificationSource("com.android.settings"))
    }
}
