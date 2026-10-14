// Host stub of <android/log.h> so llama_jni.cpp can be compiled for desktop Linux (testing only).
#pragma once
#include <stdio.h>
enum { ANDROID_LOG_DEBUG = 3, ANDROID_LOG_INFO = 4, ANDROID_LOG_WARN = 5, ANDROID_LOG_ERROR = 6 };
#define __android_log_print(prio, tag, ...) (fprintf(stderr, "[%s] ", tag), fprintf(stderr, __VA_ARGS__), fprintf(stderr, "\n"))
#define __android_log_write(prio, tag, text) ((prio) >= ANDROID_LOG_WARN ? fprintf(stderr, "[%s] %s", tag, text) : 0)
