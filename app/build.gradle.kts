plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.offlineagent.app"
    compileSdk = 35
    ndkVersion = "30.0.16248370" // same NDK as :engine:llamacpp (also used to strip .so files)

    defaultConfig {
        applicationId = "com.offlineagent.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0-phase6"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    // Two products from one code base:
    //  - offline   (default): INTERNET is used ONLY by the in-app model downloader (HTTPS to huggingface.co + its CDN).
    //               No other network code; MCP over binder (other on-device apps) + built-in servers. Inference is local.
    //  - connected: additionally the Streamable HTTP/SSE MCP transport for LAN/remote MCP servers. Inference stays local.
    flavorDimensions += "network"
    productFlavors {
        create("offline") {
            dimension = "network"
            isDefault = true
            buildConfigField("boolean", "NETWORK_MCP", "false")
        }
        create("connected") {
            dimension = "network"
            applicationIdSuffix = ".connected"
            versionNameSuffix = "-connected"
            buildConfigField("boolean", "NETWORK_MCP", "true")
        }
    }

    buildTypes {
        debug {
            // Kotlin side stays debuggable; native libs come from :engine:llamacpp built -O3 (Release).
            isMinifyEnabled = false
        }
        release {
            // R8 full mode + resource shrinking: smaller dex, faster cold start (with the baseline profile).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // local sideload builds; use your own key for distribution
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs { useLegacyPackaging = true } // extract .so files so ggml can dlopen CPU variants
        resources { excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

dependencies {
    implementation(project(":core:inference"))
    implementation(project(":engine:llamacpp"))
    implementation(project(":core:runtime"))
    implementation(project(":core:data"))
    implementation(project(":feature:workflow"))
    implementation(project(":mcp-aidl"))
    implementation(project(":core:openui"))
    implementation(project(":core:models"))
    implementation(project(":core:agents"))
    "connectedImplementation"(project(":core:mcp-http"))
    implementation(libs.pdfbox.android)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.work.runtime)
    implementation(libs.biometric)
    implementation(libs.fragment.ktx)
    implementation(libs.profileinstaller)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

    testImplementation(libs.junit)
}
