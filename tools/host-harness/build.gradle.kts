plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:inference"))
    implementation(project(":core:runtime"))
    // Compiled Kotlin classes of :engine:llamacpp (LlamaCppEngine, LlamaNative). Build it first:
    //   ./gradlew :engine:llamacpp:assembleDebug
    implementation(files("../../engine/llamacpp/build/intermediates/aar_main_jar/debug/syncDebugLibJars/classes.jar"))
}

application {
    mainClass.set("com.offlineagent.tools.HostHarnessKt")
}

tasks.named<JavaExec>("run") {
    // -PnativeDir=<dir with liboffline_agent_llama.so + libggml-cpu-*.so>  -Pmodel=<gguf>
    val nativeDir = providers.gradleProperty("nativeDir").orNull ?: ""
    jvmArgs("-Djava.library.path=$nativeDir")
    args(nativeDir, providers.gradleProperty("model").orNull ?: "")
}

// Phase 2: canvas grammar, agent tool calls, embeddings/RAG, image input. Env: MODEL, EMBED, VLM, MMPROJ, ONLY, THREADS.
tasks.register<JavaExec>("runPhase2") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.offlineagent.tools.Phase2HarnessKt")
    val nativeDir = providers.gradleProperty("nativeDir").orNull ?: ""
    jvmArgs("-Djava.library.path=$nativeDir", "-Xmx1g")
    args(nativeDir)
}
