// JNI surface for the OpenJTalk G2P frontend.
//
// Deliberately thin: it only marshals strings. All tracing/attribution logic
// lives in Kotlin so it can be unit-tested against golden vectors.
#include <jni.h>

#include <string>
#include <vector>

#include "ja_g2p.h"

namespace {

std::string to_string(JNIEnv *env, jstring value) {
    if (value == nullptr) return std::string();
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring from_string(JNIEnv *env, const char *value) {
    if (value == nullptr) return nullptr;
    return env->NewStringUTF(value);
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_lladlam_melox_core_autosync_JaOpenJtalkJniKt_nativeOpen(JNIEnv *env, jclass, jstring dicDir) {
    const std::string dir = to_string(env, dicDir);
    if (dir.empty()) return 0;
    return reinterpret_cast<jlong>(ja_g2p_open(dir.c_str()));
}

extern "C" JNIEXPORT void JNICALL
Java_com_lladlam_melox_core_autosync_JaOpenJtalkJniKt_nativeClose(JNIEnv *, jclass, jlong handle) {
    if (handle != 0) ja_g2p_close(reinterpret_cast<void *>(handle));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lladlam_melox_core_autosync_JaOpenJtalkJniKt_nativePhonemes(
        JNIEnv *env, jclass, jlong handle, jstring text) {
    if (handle == 0) return nullptr;
    const std::string input = to_string(env, text);
    char *value = ja_g2p_phonemes(reinterpret_cast<void *>(handle), input.c_str());
    jstring out = from_string(env, value);
    ja_g2p_free(value);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lladlam_melox_core_autosync_JaOpenJtalkJniKt_nativeKana(
        JNIEnv *env, jclass, jlong handle, jstring text) {
    if (handle == 0) return nullptr;
    const std::string input = to_string(env, text);
    char *value = ja_g2p_kana(reinterpret_cast<void *>(handle), input.c_str());
    jstring out = from_string(env, value);
    ja_g2p_free(value);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_lladlam_melox_core_autosync_JaOpenJtalkJniKt_nativeTokens(
        JNIEnv *env, jclass, jlong handle, jstring text) {
    if (handle == 0) return nullptr;
    const std::string input = to_string(env, text);
    char *value = ja_g2p_tokens(reinterpret_cast<void *>(handle), input.c_str());
    jstring out = from_string(env, value);
    ja_g2p_free(value);
    return out;
}
