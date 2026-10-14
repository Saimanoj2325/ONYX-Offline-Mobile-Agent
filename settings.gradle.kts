pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "OfflineAgent"

// Phase 1 modules
include(":app")
include(":core:inference")
include(":engine:llamacpp")

// Reserved for later phases (see docs/ARCHITECTURE.md):
// include(":core:runtime")    // agent loop, tool registry, workflow executor (pure Kotlin)
// include(":core:data")       // Room persistence (chats, workflows, memory)
// include(":feature:workflow")// visual node workflow builder UI

// Optional developer tool: runs the real JNI bridge + real GGUF on an ARM64 Linux host JVM.
// Enabled only with -PhostHarness (see tools/host-harness/README.md). Not part of the APK.
if (providers.gradleProperty("hostHarness").isPresent) include(":tools:host-harness")
