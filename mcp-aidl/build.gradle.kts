plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// On-device MCP over Android binder: the AIDL contract + a base Service that hosts an McpServer.
// Shared by the app (client side) and by any third-party app that wants to expose MCP tools on the phone.
android {
    namespace = "com.offlineagent.mcp"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    buildFeatures { aidl = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

dependencies {
    api(project(":core:runtime"))
    implementation(libs.kotlinx.coroutines.android)
}
