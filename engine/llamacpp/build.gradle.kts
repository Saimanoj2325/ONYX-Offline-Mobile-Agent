plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.offlineagent.engine.llamacpp"
    compileSdk = 35
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 28

        ndk {
            // arm64 only: every Android phone sold since 2019 is arm64, and shipping 32-bit
            // would halve the address space available for mmapped weights.
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // Native code is ALWAYS built -O3, even inside the debug APK: a debug-optimised
                // ggml is several times slower and would make the app feel broken on a phone.
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                // Several .so files use C++ (llama, ggml-base, CPU variants, our JNI): one shared C++ runtime
                // avoids duplicate libc++ state across them (NDK guidance for multiple C++ shared libraries).
                arguments += "-DANDROID_STL=c++_shared"

                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=OFF"
                arguments += "-DLLAMA_BUILD_TESTS=OFF"
                arguments += "-DLLAMA_BUILD_EXAMPLES=OFF"
                arguments += "-DLLAMA_BUILD_TOOLS=OFF"
                arguments += "-DLLAMA_BUILD_SERVER=OFF"
                // libmtmd: multimodal (image) input. Standalone library build, no CLI tools, no video.
                arguments += "-DLLAMA_BUILD_MTMD=ON"
                arguments += "-DMTMD_VIDEO=OFF"
                arguments += "-DLLAMA_CURL=OFF"
                arguments += "-DLLAMA_OPENSSL=OFF"

                // Portability + speed: build no CPU feature into the common code (GGML_NATIVE=OFF),
                // and instead ship llama.cpp's Android CPU backend variants as separate .so files
                // that are picked at runtime by /proc/cpuinfo (armv8.0 ... armv9.2).
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                arguments += "-DGGML_OPENMP=OFF"
                arguments += "-DGGML_LLAMAFILE=OFF"
                cppFlags += listOf("-O3", "-fvisibility=hidden", "-ffunction-sections", "-fdata-sections")
                cFlags += listOf("-O3")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        jniLibs {
            // ggml dlopen()s the backend variants, so they must stay real files on disk.
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { jvmToolchain(17) }
}

dependencies {
    api(project(":core:inference"))
    implementation(libs.kotlinx.coroutines.android)
}
