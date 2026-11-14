#include "conversation_core.h"
#ifndef CONVERSATION_BRIDGE_TEST
#include <android/log.h>
#include <jni.h>
#endif
#include <algorithm>
#include <exception>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace {
constexpr int kPageSize = 80;
std::mutex mutex;
bool core_ready = false;
std::string storage_path;
std::string error;

#ifndef CONVERSATION_BRIDGE_TEST
jstring java_string(JNIEnv* env, const std::string& value) {
    if (env->ExceptionCheck()) return nullptr;
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(value.size()));
    if (!bytes) return nullptr;
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(value.size()),
                           reinterpret_cast<const jbyte*>(value.data()));
    if (env->ExceptionCheck()) { env->DeleteLocalRef(bytes); return nullptr; }
    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) { env->DeleteLocalRef(bytes); return nullptr; }
    jmethodID constructor = env->GetMethodID(string_class, "<init>", "([BLjava/lang/String;)V");
    if (!constructor) { env->DeleteLocalRef(string_class); env->DeleteLocalRef(bytes); return nullptr; }
    jstring charset = env->NewStringUTF("UTF-8");
    jstring result = charset ? static_cast<jstring>(
        env->NewObject(string_class, constructor, bytes, charset)) : nullptr;
    env->DeleteLocalRef(charset);
    env->DeleteLocalRef(string_class);
    env->DeleteLocalRef(bytes);
    return result;
}

bool read_bytes(JNIEnv* env, jbyteArray input, std::vector<unsigned char>& output) {
    if (!input) return false;
    output.resize(static_cast<size_t>(env->GetArrayLength(input)));
    if (!output.empty()) env->GetByteArrayRegion(input, 0, static_cast<jsize>(output.size()),
                                                reinterpret_cast<jbyte*>(output.data()));
    return !env->ExceptionCheck();
}

bool read_text(JNIEnv* env, jbyteArray input, std::string& output) {
    std::vector<unsigned char> bytes;
    if (!read_bytes(env, input, bytes)) return false;
    if (std::find(bytes.begin(), bytes.end(), 0) != bytes.end()) return false;
    output.assign(bytes.begin(), bytes.end());
    return true;
}
#endif

void core_error() {
    const char* message = conversation_error();
    error = message && message[0] ? message : "保存できませんでした。";
#ifndef CONVERSATION_BRIDGE_TEST
    __android_log_print(ANDROID_LOG_ERROR, "Conversation", "Core operation failed");
#endif
}

bool switch_person(std::string requested_path, const std::string& payload) {
    if ((requested_path.empty() && !payload.empty()) || requested_path.find('\0') != std::string::npos || payload.find('\0') != std::string::npos) {
        error = "人物の保存先・資料が無効です。";
        return false;
    }
    if (core_ready && requested_path == storage_path && payload.empty()) { error.clear(); return true; }
    const bool had_previous = core_ready;
    auto restore_previous = [&] {
        conversation_close();
        core_ready = had_previous && conversation_init(storage_path.c_str());
    };
    conversation_close();
    core_ready = false;
    try {
        if (requested_path.empty() || (conversation_init(requested_path.c_str()) &&
                (payload.empty() || conversation_import_person(payload.c_str())))) {
            storage_path.swap(requested_path);
            core_ready = !storage_path.empty();
            error.clear();
            return true;
        }
        core_error();
    } catch (...) {
        restore_previous();
        throw;
    }
    std::string failure = std::move(error);
    restore_previous();
    error = std::move(failure);
    if (had_previous && !core_ready) error += "\n元の会話も開けませんでした。保存先を確認してください。";
    return false;
}

std::string archive_delta(int64_t after_cursor) {
    if (!core_ready) { error = "保存先を初期化できていません。"; return {}; }
    const char* result = conversation_archive_delta(after_cursor);
    if (!result) { core_error(); return {}; }
    error.clear();
    return result;
}

void append_json_string(std::string& output, const char* text) {
    constexpr char hex[] = "0123456789abcdef";
    output += '"';
    for (const unsigned char* at = reinterpret_cast<const unsigned char*>(text); *at; ++at) {
        if (*at == '"' || *at == '\\') { output += '\\'; output += static_cast<char>(*at); }
        else if (*at < 0x20) { output += "\\u00"; output += hex[*at >> 4]; output += hex[*at & 15]; }
        else output += static_cast<char>(*at);
    }
    output += '"';
}

std::string message_snapshot(int requested_first) {
    const int total = core_ready ? conversation_message_count() : 0;
    const int first = requested_first == -1 ? std::max(0, total - kPageSize) :
        std::clamp(requested_first, 0, std::max(0, total - 1));
    const int count = std::min(kPageSize, total - first);
    if (count > 0 && !conversation_load_window(first, count)) { core_error(); return {}; }
    std::string result = "{\"first\":" + std::to_string(first) + ",\"total\":" + std::to_string(total) + ",\"messages\":[";
    for (int i = first; i < first + count; ++i) {
        const char* text = conversation_message_text(i);
        const char* role = conversation_message_role(i);
        if (!text || !role) { core_error(); return {}; }
        if (i > first) result += ',';
        result += "{\"id\":" + std::to_string(conversation_message_id(i)) + ",\"role\":";
        append_json_string(result, role);
        result += ",\"text\":";
        append_json_string(result, text);
        result += '}';
    }
    result += "]}";
    error.clear();
    return result;
}

std::string recent_transcript() {
    std::string transcript;
    const int count = core_ready ? conversation_message_count() : 0;
    if (!core_ready || count == 0) return transcript;
    if (!conversation_load_window(std::max(0, count - 20), std::min(20, count))) { core_error(); return {}; }
    for (int i = std::max(0, count - 20); i < count; ++i) {
        const char* text = conversation_message_text(i);
        if (!text) continue;
        if (!transcript.empty()) transcript += "\n\n";
        transcript += text;
        if (transcript.size() > 16384) {
            size_t start = transcript.size() - 16384;
            while (start < transcript.size() && (static_cast<unsigned char>(transcript[start]) & 0xc0) == 0x80) ++start;
            transcript.erase(0, start);
        }
    }
    error.clear();
    return transcript;
}
} // namespace

#ifndef CONVERSATION_BRIDGE_TEST
extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeInit(JNIEnv* env, jclass, jbyteArray path,
                                                   jbyteArray, jbyteArray) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        std::string requested_path;
        if (!read_text(env, path, requested_path)) return java_string(env, "保存先が無効です。");
        return java_string(env, switch_person(std::move(requested_path), "") ? "" : error);
    } catch (const std::exception&) {
        return java_string(env, "初期化に必要なメモリを確保できませんでした。");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeMessages(JNIEnv* env, jclass, jint first) {
    std::lock_guard<std::mutex> lock(mutex);
    try { return java_string(env, message_snapshot(first)); }
    catch (const std::exception&) { error = "会話を読み込めませんでした。"; return java_string(env, ""); }
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeEmotion(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        if (!core_ready) return java_string(env, "");
        const char* emotion = conversation_assistant_emotion();
        if (!emotion) { core_error(); return java_string(env, ""); }
        return java_string(env, emotion);
    } catch (const std::exception&) { error = "気持ちを読み込めませんでした。"; return java_string(env, ""); }
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeSwitchPerson(JNIEnv* env, jclass, jbyteArray path, jbyteArray payload) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        std::string requested_path, text;
        if (!read_text(env, path, requested_path) || (payload && !read_text(env, payload, text))) {
            error = "人物の保存先・資料が無効です。";
            return java_string(env, error);
        }
        return java_string(env, switch_person(std::move(requested_path), text) ? "" : error);
    } catch (const std::exception&) {
        error = "人物を切り替えるためのメモリを確保できませんでした。";
        return java_string(env, error);
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeArchiveDelta(JNIEnv* env, jclass, jlong after_cursor) {
    std::lock_guard<std::mutex> lock(mutex);
    try { return java_string(env, archive_delta(after_cursor)); }
    catch (const std::exception&) { error = "バックアップ内容を読み込めませんでした。"; return java_string(env, ""); }
}

extern "C" JNIEXPORT jlong JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeAddMessage(JNIEnv* env, jclass, jbyteArray role, jbyteArray text) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        std::string message, message_role;
        if (!core_ready) { error = "保存先を初期化できていません。"; return 0; }
        if (!read_text(env, role, message_role) || !read_text(env, text, message)) {
            error = "入力に無効な文字が含まれています。"; return 0;
        }
        const int64_t id = conversation_add_message(message_role.c_str(), message.c_str());
        if (!id) { core_error(); return 0; }
        error.clear();
        return id;
    } catch (const std::exception&) {
        error = "保存に必要なメモリを確保できませんでした。";
        return 0;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeLastError(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(mutex);
    return java_string(env, error);
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeContext(JNIEnv* env, jclass, jbyteArray query) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        std::string text;
        if (!core_ready || !read_text(env, query, text)) { error = "入力が無効です。"; return java_string(env, ""); }
        const char* result = conversation_context(text.c_str());
        if (!result) { core_error(); return java_string(env, ""); }
        error.clear();
        return java_string(env, result);
    } catch (const std::exception&) {
        error = "文脈を読み込めませんでした。"; return java_string(env, "");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativePendingJob(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        if (!core_ready) return java_string(env, "");
        const char* result = conversation_pending_job();
        if (!result) { core_error(); return java_string(env, ""); }
        error.clear();
        return java_string(env, result);
    } catch (const std::exception&) {
        error = "記憶の更新準備に失敗しました。"; return java_string(env, "");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeApplyJob(JNIEnv* env, jclass, jlong id, jbyteArray payload) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        std::string text;
        if (!core_ready || !read_text(env, payload, text)) return java_string(env, "記憶の更新内容が無効です。");
        if (!conversation_apply_job(id, text.c_str())) { core_error(); return java_string(env, error); }
        error.clear();
        return java_string(env, "");
    } catch (const std::exception&) { return java_string(env, "記憶を更新できませんでした。"); }
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativePendingPersona(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        if (!core_ready) return java_string(env, "");
        const char* result = conversation_pending_persona();
        if (!result) { core_error(); return java_string(env, ""); }
        error.clear();
        return java_string(env, result);
    } catch (const std::exception&) {
        error = "人格の見直し準備に失敗しました。"; return java_string(env, "");
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeApplyPersona(JNIEnv* env, jclass, jlong through_id, jbyteArray payload) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        std::string text;
        if (!core_ready || !read_text(env, payload, text)) return java_string(env, "人格の更新内容が無効です。");
        if (!conversation_apply_persona(through_id, text.c_str())) { core_error(); return java_string(env, error); }
        error.clear();
        return java_string(env, "");
    } catch (const std::exception&) { return java_string(env, "人格を更新できませんでした。"); }
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeMemories(JNIEnv* env, jclass, jbyteArray query) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        std::string text;
        if (!core_ready || !read_text(env, query, text)) { error = "検索内容が無効です。"; return java_string(env, ""); }
        const char* result = conversation_memories(text.c_str());
        if (!result) { core_error(); return java_string(env, ""); }
        error.clear();
        return java_string(env, result);
    } catch (const std::exception&) { error = "記憶を読み込めませんでした。"; return java_string(env, ""); }
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeExport(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        if (!core_ready) return java_string(env, "保存先を初期化できていません。");
        if (!conversation_export((storage_path + "/export").c_str())) { core_error(); return java_string(env, error); }
        error.clear();
        return java_string(env, "");
    } catch (const std::exception&) { return java_string(env, "書き出しに失敗しました。"); }
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeRecentTranscript(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(mutex);
    try { return java_string(env, recent_transcript()); }
    catch (const std::exception&) { return java_string(env, "表示に必要なメモリを確保できませんでした。"); }
}
#endif
