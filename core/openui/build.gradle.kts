plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM implementation of OpenUI Lang (https://www.openui.com, MIT, thesysdev/openui):
// streaming parser, component library + resolver, system-prompt generator, GBNF grammar, form round-trip.
// The Compose renderer lives in :app (ui/openui). No Android dependencies here, so everything is JVM-unit-tested.
kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit)
}
