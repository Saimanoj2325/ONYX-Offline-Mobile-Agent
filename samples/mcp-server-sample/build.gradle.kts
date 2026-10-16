plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Sample third-party on-device MCP server (offline): a tiny "Study tools" app that exposes tools, a resource and a
// prompt to the Offline Agent over binder. Build: ./gradlew :samples:mcp-server-sample:assembleDebug
android {
    namespace = "com.offlineagent.sample.mcp"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.offlineagent.sample.mcp"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

dependencies {
    implementation(project(":mcp-aidl"))
}
