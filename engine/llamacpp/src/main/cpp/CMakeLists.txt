cmake_minimum_required(VERSION 3.22)
project("offline_agent_llama" LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR "Only arm64-v8a is supported in Phase 1 (got ${ANDROID_ABI})")
endif()
set(GGML_SYSTEM_ARCH "ARM")

# Vendored llama.cpp (pinned tag in third_party/LLAMA_CPP_VERSION)
set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../../third_party/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

add_library(offline_agent_llama SHARED llama_jni.cpp)
target_include_directories(offline_agent_llama PRIVATE ${LLAMA_SRC}/include ${LLAMA_SRC}/ggml/include)
target_link_libraries(offline_agent_llama PRIVATE llama ggml ggml-base android log)
target_link_options(offline_agent_llama PRIVATE "-Wl,--gc-sections" "-Wl,-z,max-page-size=16384")
