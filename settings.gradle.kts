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

// Phase 2 modules
include(":core:runtime")      // UI schema, markdown, RAG maths, skills, tools, policy, agent loop (pure Kotlin)
include(":core:data")         // Room + DataStore persistence
include(":feature:workflow")  // Skills editor + agent run history UI (Compose)

// Optional developer tool: runs the real JNI bridge + real GGUF on an ARM64 Linux host JVM.
// Enabled only with -PhostHarness (see tools/host-harness/README.md). Not part of the APK.
if (providers.gradleProperty("hostHarness").isPresent) include(":tools:host-harness")
