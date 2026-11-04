plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Phase 6 AppBridge: app catalogue, intent tools, notification bridge, inbound requests, app safety policy and
// scoped approvals. Pure Kotlin/JVM: Android (PackageManager, Intents, NotificationListenerService, ClipboardManager)
// stays behind the small ports in Ports.kt, implemented in :app (app/onyx/apps/*).
kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:agents"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
