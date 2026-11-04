plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Phase 6 "Screen tools v2": the accessibility screen agent as first-class tools of the Phase 5 task graph.
// Pure Kotlin/JVM: snapshot refs + skeleton/drill-down, diffing, grounding (incl. grounded early exit), extraction,
// cross-app transfer, safety policy, scoped approvals and app cards are all JVM-tested. The app supplies the
// Android ports (AccessibilityService, overlay, clipboard, launch intents).
kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:agents"))
    api(project(":core:runtime"))
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
