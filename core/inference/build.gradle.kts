plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM module: no Android dependencies, so everything here is unit-testable on the JVM.
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
