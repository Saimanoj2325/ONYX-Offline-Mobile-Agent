plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Streamable HTTP / SSE transport for LAN or remote MCP servers. ONLY the "connected" app flavor depends on this
// module; the default "offline" flavor never contains it (and has no INTERNET permission).
kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:runtime"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
