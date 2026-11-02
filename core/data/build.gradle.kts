plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.offlineagent.core.data"
    compileSdk = 35
    defaultConfig { minSdk = 28 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    api(project(":core:runtime"))
    api(project(":core:agents"))
    api(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    api(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
