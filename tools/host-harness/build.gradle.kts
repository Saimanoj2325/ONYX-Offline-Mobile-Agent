plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core:inference"))
    implementation(project(":core:runtime"))
    implementation(project(":core:agents"))
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

// Phase 3: speed, KV types, long context, prefix cache, context shift, speculative decoding, summarisation,
// phone tools via built-in MCP servers, screen agent, report -> PDF. Env: MODEL, DRAFT, THREADS, CTX, ONLY, OUT.
tasks.register<JavaExec>("runPhase3") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.offlineagent.tools.Phase3HarnessKt")
    val nativeDir = providers.gradleProperty("nativeDir").orNull ?: ""
    jvmArgs("-Djava.library.path=$nativeDir", "-Xmx1g")
    args(nativeDir)
}

// Phase 4b: GGUF header speed, prompt sizes, time to first token, streaming + thinking, prefill batch sizes.
// Env: MODEL, THREADS, CTX, BATCHES, ONLY.
tasks.register<JavaExec>("runPhase4b") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.offlineagent.tools.Phase4bHarnessKt")
    val nativeDir = providers.gradleProperty("nativeDir").orNull ?: ""
    jvmArgs("-Djava.library.path=$nativeDir", "-Xmx1g")
    args(nativeDir)
}

// Phase 5b: end-to-end multi-agent orchestration on a real GGUF. Env: MODEL, THREADS, CTX, ONLY.
tasks.register<JavaExec>("runPhase5b") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.offlineagent.tools.Phase5bHarnessKt")
    val nativeDir = providers.gradleProperty("nativeDir").orNull ?: ""
    jvmArgs("-Djava.library.path=$nativeDir", "-Xmx1g")
    args(nativeDir)
}

// Phase 6: n-gram self-speculation identical-output + speed, benchmark numbers, memory/routine e2e.
// Env: MODEL, THREADS, CTX, DRAFT, ONLY (spec|bench|memory), OUT.
tasks.register<JavaExec>("runPhase6") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.offlineagent.tools.Phase6HarnessKt")
    val nativeDir = providers.gradleProperty("nativeDir").orNull ?: ""
    jvmArgs("-Djava.library.path=$nativeDir", "-Xmx1g")
    args(nativeDir)
}

// 0.6.2.2: Canvas prompt sizes (real tokenizer) and cold/warm prefill, before vs after. Env: MODEL, THREADS, CTX.
tasks.register<JavaExec>("runPhase622") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.offlineagent.tools.Phase622HarnessKt")
    val nativeDir = providers.gradleProperty("nativeDir").orNull ?: ""
    jvmArgs("-Djava.library.path=$nativeDir", "-Xmx1g")
    args(nativeDir)
}

// 0.6.2.3: wait after Enter with / without typing-time prefill (real-time typing). Env: MODEL, THREADS, CTX, CPS, REPS.
tasks.register<JavaExec>("runPhase623") {
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.offlineagent.tools.Phase623HarnessKt")
    val nativeDir = providers.gradleProperty("nativeDir").orNull ?: ""
    jvmArgs("-Djava.library.path=$nativeDir", "-Xmx1g")
    args(nativeDir)
}
