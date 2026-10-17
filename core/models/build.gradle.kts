plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM: curated model catalogue, Hugging Face URL handling, resumable HTTP-Range downloader with GGUF/size/sha256
// verification. The Android side (:app, ModelDownloadWorker) only adds WorkManager + notification + the model DB.
// This downloader is the ONLY code in the offline flavor that opens network connections.
kotlin { jvmToolchain(17) }

dependencies {
    testImplementation(libs.junit)
}
