plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.offlineagent.app"
    compileSdk = 35
    ndkVersion = "30.0.16248370" // same NDK as :engine:llamacpp (also used to strip .so files)

    defaultConfig {
        applicationId = "com.offlineagent.app"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-phase1"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        debug {
            // Kotlin side stays debuggable; native libs come from :engine:llamacpp built -O3 (Release).
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures { compose = true }

    packaging {
        jniLibs { useLegacyPackaging = true } // extract .so files so ggml can dlopen CPU variants
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
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

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)

    testImplementation(libs.junit)
}
