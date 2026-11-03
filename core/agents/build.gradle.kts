plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Phase 5: multi-agent orchestration on ONE shared local model. Pure Kotlin/JVM (no Android): the orchestrator, planner DAG,
// blackboard, serialized inference queue, verification, outbox logic and device-aware runtime selection are all
// covered by fast JVM unit tests. The app supplies Android implementations of the ports.
kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:runtime"))
    api(project(":core:models")) // Phase 6: catalogue + ModelFit for device-adaptive tiers
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
