plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Pure Kotlin/JVM: UI schema, markdown, RAG maths, skills, tools, policy and the agent loop.
// No Android dependencies, so all of it is covered by fast JVM unit tests.
kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:inference"))
    api(project(":core:openui"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
