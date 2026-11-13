#include "conversation_core.h"
#include "imgui.h"

#ifndef CONVERSATION_UI_TEST
#include "imgui_impl_opengl3.h"

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <jni.h>
#endif

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <exception>
#include <mutex>
#include <string>
#include <utility>
#include <vector>

namespace {
constexpr char kLogTag[] = "Conversation";
std::mutex mutex;
ImGuiContext* context = nullptr;
#ifndef CONVERSATION_UI_TEST
EGLContext gl_context = EGL_NO_CONTEXT;
#endif
bool core_ready = false;
bool renderer_ready = false;
bool scroll_to_bottom = true;
int width = 1;
int height = 1;
int tab = 0;
float density = 1.0f;
float pending_scroll = 0.0f;
ImVec2 list_min;
ImVec2 list_max;
std::vector<unsigned char> japanese_font;
std::vector<unsigned char> emoji_font;
std::string storage_path;
std::string error;
std::string notice;
std::chrono::steady_clock::time_point last_frame;

struct Touch {
    bool down = false;
    bool dragging = false;
    bool in_list = false;
    float start_x = 0;
    float start_y = 0;
    float last_y = 0;

    void begin(float x, float y, bool list) {
        *this = {true, false, list, x, y, y};
    }

    float move(float x, float y, float threshold) {
        if (!down) return 0;
        if (std::hypot(x - start_x, y - start_y) > threshold) dragging = true;
        const float delta = dragging && in_list ? y - last_y : 0;
        last_y = y;
        return delta;
    }

    bool finish() {
        const bool tap = down && !dragging;
        down = false;
        return tap;
    }
} touch;

#ifndef CONVERSATION_UI_TEST
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
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", error.c_str());
}

bool remember_message(int index) {
    if (!conversation_remember(index)) { core_error(); return false; }
    error.clear();
    notice = "大切な会話として固定しました";
    return true;
}

void apply_style() {
    ImGui::GetStyle() = ImGuiStyle();
    ImGui::StyleColorsDark();
    ImGuiStyle& style = ImGui::GetStyle();
    style.WindowPadding = ImVec2(16, 16);
    style.FramePadding = ImVec2(12, 10);
    style.ItemSpacing = ImVec2(10, 12);
    style.ChildRounding = 12;
    style.FrameRounding = 8;
    style.ScrollbarSize = 12;
    style.WindowBorderSize = 0;
    style.Colors[ImGuiCol_Text] = ImVec4(0.90f, 0.93f, 0.95f, 1);
    style.Colors[ImGuiCol_TextDisabled] = ImVec4(0.61f, 0.67f, 0.71f, 1);
    style.Colors[ImGuiCol_WindowBg] = ImVec4(0.075f, 0.09f, 0.11f, 1);
    style.Colors[ImGuiCol_ChildBg] = ImVec4(0.10f, 0.12f, 0.145f, 1);
    style.Colors[ImGuiCol_Border] = ImVec4(0.23f, 0.28f, 0.32f, 1);
    style.Colors[ImGuiCol_Button] = ImVec4(0.16f, 0.20f, 0.23f, 1);
    style.Colors[ImGuiCol_ButtonHovered] = ImVec4(0.22f, 0.29f, 0.32f, 1);
    style.Colors[ImGuiCol_ButtonActive] = ImVec4(0.25f, 0.36f, 0.34f, 1);
    style.ScaleAllSizes(density);
    style.FontSizeBase = 17.0f * density;
}

#ifndef CONVERSATION_UI_TEST
void destroy_ui() {
    if (!context) return;
    ImGui::SetCurrentContext(context);
    if (renderer_ready) ImGui_ImplOpenGL3_Shutdown();
    ImGui::DestroyContext(context);
    context = nullptr;
    renderer_ready = false;
    gl_context = EGL_NO_CONTEXT;
}
#endif

void draw_ui() {
    const float d = density;
    ImGui::SetNextWindowPos(ImVec2(0, 0));
    ImGui::SetNextWindowSize(ImVec2(static_cast<float>(width), static_cast<float>(height)));
    ImGui::Begin("Conversation", nullptr, ImGuiWindowFlags_NoDecoration |
                 ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoSavedSettings);
    ImGui::PushFont(nullptr, 23.0f * d);
    ImGui::TextUnformatted("Conversation");
    ImGui::PopFont();
    ImGui::TextDisabled("AI未接続・会話は自動保存");
    if (!error.empty()) {
        ImGui::PushStyleColor(ImGuiCol_Text, ImVec4(1.0f, 0.55f, 0.49f, 1));
        ImGui::PushTextWrapPos(0);
        ImGui::TextUnformatted(error.c_str());
        ImGui::PopTextWrapPos();
        ImGui::PopStyleColor();
    } else if (!notice.empty()) {
        ImGui::TextUnformatted(notice.c_str());
    }
    const float tab_width = (ImGui::GetContentRegionAvail().x - ImGui::GetStyle().ItemSpacing.x) / 2;
    const bool chat_active = tab == 0;
    if (chat_active) ImGui::PushStyleColor(ImGuiCol_Button, ImVec4(0.15f, 0.32f, 0.28f, 1));
    if (ImGui::Button("会話", ImVec2(tab_width, 48 * d))) tab = 0;
    if (chat_active) ImGui::PopStyleColor();
    ImGui::SameLine();
    char memory_label[64];
    std::snprintf(memory_label, sizeof(memory_label), "記憶 (%d)", core_ready ? conversation_memory_count() : 0);
    const bool memory_active = tab == 1;
    if (memory_active) ImGui::PushStyleColor(ImGuiCol_Button, ImVec4(0.15f, 0.32f, 0.28f, 1));
    if (ImGui::Button(memory_label, ImVec2(tab_width, 48 * d))) tab = 1;
    if (memory_active) ImGui::PopStyleColor();

    const int current_tab = tab;
    ImGui::BeginChild(current_tab == 0 ? "chat" : "memory", ImVec2(0, 0),
                      ImGuiChildFlags_Borders | ImGuiChildFlags_AlwaysUseWindowPadding);
    list_min = ImGui::GetWindowPos();
    list_max = ImVec2(list_min.x + ImGui::GetWindowSize().x, list_min.y + ImGui::GetWindowSize().y);
    if (pending_scroll != 0) {
        ImGui::SetScrollY(ImGui::GetScrollY() - pending_scroll);
        pending_scroll = 0;
        scroll_to_bottom = false;
    }
    const int count = core_ready ? (current_tab == 0 ? conversation_message_count() : conversation_memory_count()) : 0;
    if (count == 0) {
        ImGui::PushTextWrapPos(0);
        ImGui::TextUnformatted(current_tab == 0 ? "まだ会話がありません。\n下の入力欄から、話したいことを残せます。"
                                               : "AI接続後、会話から記憶を自動で整理します。\n現在は会話の全文を自動保存しています。");
        ImGui::PopTextWrapPos();
    }
    for (int i = 0; i < count; ++i) {
        ImGui::PushID(i);
        ImGui::TextDisabled(current_tab == 0 ? "あなた" : "記憶 %d", i + 1);
        const char* text = current_tab == 0 ? conversation_message_text(i) : conversation_memory_text(i);
        ImGui::PushTextWrapPos(0);
        ImGui::TextUnformatted(text ? text : "");
        ImGui::PopTextWrapPos();
        if (i + 1 != count) { ImGui::Spacing(); ImGui::Separator(); ImGui::Spacing(); }
        ImGui::PopID();
    }
    if (current_tab == 0 && scroll_to_bottom) {
        ImGui::SetScrollHereY(1);
        scroll_to_bottom = false;
    }
    ImGui::EndChild();
    ImGui::End();
}

void handle_touch(int action, float x, float y) {
    if (!context || !std::isfinite(x) || !std::isfinite(y)) return;
    ImGui::SetCurrentContext(context);
    if (action == 0) touch.begin(x, y, x >= list_min.x && x <= list_max.x && y >= list_min.y && y <= list_max.y);
    else if (action == 2) pending_scroll += touch.move(x, y, 8.0f * density);
    else if (action == 1) {
        pending_scroll += touch.move(x, y, 8.0f * density);
        if (touch.finish()) {
            // Delay click injection until finger-up so a swipe can never pin a message.
            ImGuiIO& io = ImGui::GetIO();
            io.AddMouseSourceEvent(ImGuiMouseSource_TouchScreen);
            io.AddMousePosEvent(x, y);
            io.AddMouseButtonEvent(0, true);
            io.AddMouseButtonEvent(0, false);
        }
    } else if (action == 3) touch = {};
}
} // namespace

#ifndef CONVERSATION_UI_TEST
extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeInit(JNIEnv* env, jclass, jbyteArray path,
                                                   jbyteArray jp_font, jbyteArray symbols_font) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        std::string requested_path;
        if (!read_text(env, path, requested_path) || requested_path.empty())
            return java_string(env, "保存先が無効です。");
        if (core_ready) return java_string(env, requested_path == storage_path ? "" : "保存先は変更できません。");
        if (!read_bytes(env, jp_font, japanese_font) || japanese_font.size() < 12 ||
            !read_bytes(env, symbols_font, emoji_font) || emoji_font.size() < 12)
            return java_string(env, "日本語・絵文字フォントを読み込めませんでした。");
        if (!conversation_init(requested_path.c_str())) { core_error(); return java_string(env, error); }
        storage_path = std::move(requested_path);
        core_ready = true;
        error.clear();
        return java_string(env, "");
    } catch (const std::exception&) {
        return java_string(env, "初期化に必要なメモリを確保できませんでした。");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeSurfaceCreated(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(mutex);
    // This view is the sole GL owner; its replacement context has no live objects yet.
    destroy_ui();
    while (glGetError() != GL_NO_ERROR) {}
    if (!core_ready || eglGetCurrentContext() == EGL_NO_CONTEXT) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Cannot create renderer: no core or EGL context");
        return;
    }
    IMGUI_CHECKVERSION();
    context = ImGui::CreateContext();
    ImGuiIO& io = ImGui::GetIO();
    io.IniFilename = nullptr;
    io.LogFilename = nullptr;
    io.BackendPlatformName = "conversation_android_touch";
    io.ConfigFlags |= ImGuiConfigFlags_IsTouchScreen;
    ImFontConfig config;
    config.FontDataOwnedByAtlas = false;
    const bool japanese_ok = io.Fonts->AddFontFromMemoryTTF(japanese_font.data(),
        static_cast<int>(japanese_font.size()), 17.0f, &config) != nullptr;
    config.MergeMode = true;
    const bool emoji_ok = japanese_ok && io.Fonts->AddFontFromMemoryTTF(emoji_font.data(),
        static_cast<int>(emoji_font.size()), 17.0f, &config) != nullptr;
    if (!japanese_ok || !emoji_ok) {
        error = "日本語・絵文字フォントを初期化できませんでした。";
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", error.c_str());
        destroy_ui();
        return;
    }
    renderer_ready = ImGui_ImplOpenGL3_Init("#version 300 es");
    if (!renderer_ready) {
        error = "OpenGL ES 3の描画を初期化できませんでした。";
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s", error.c_str());
        destroy_ui();
        return;
    }
    gl_context = eglGetCurrentContext();
    apply_style();
    last_frame = {};
    touch = {};
    pending_scroll = 0;
}

extern "C" JNIEXPORT void JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeResize(JNIEnv*, jclass, jint new_width, jint new_height, jfloat scale) {
    std::lock_guard<std::mutex> lock(mutex);
    width = std::max(1, static_cast<int>(new_width));
    height = std::max(1, static_cast<int>(new_height));
    density = std::isfinite(scale) && scale > 0 ? scale : 1.0f;
    if (context) { ImGui::SetCurrentContext(context); apply_style(); }
}

extern "C" JNIEXPORT void JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeRender(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(mutex);
    if (!context || !renderer_ready || eglGetCurrentContext() != gl_context) return;
    ImGui::SetCurrentContext(context);
    ImGuiIO& io = ImGui::GetIO();
    io.DisplaySize = ImVec2(static_cast<float>(width), static_cast<float>(height));
    const auto now = std::chrono::steady_clock::now();
    io.DeltaTime = last_frame.time_since_epoch().count() == 0 ? 1.0f / 60.0f :
        std::clamp(std::chrono::duration<float>(now - last_frame).count(), 0.001f, 0.1f);
    last_frame = now;
    ImGui_ImplOpenGL3_NewFrame();
    ImGui::NewFrame();
    draw_ui();
    ImGui::Render();
    glViewport(0, 0, width, height);
    glClearColor(0.075f, 0.09f, 0.11f, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
    const GLenum gl_error = glGetError();
    if (gl_error != GL_NO_ERROR) __android_log_print(ANDROID_LOG_ERROR, kLogTag, "OpenGL error: 0x%x", gl_error);
}

extern "C" JNIEXPORT void JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeTouch(JNIEnv*, jclass, jint action, jfloat x, jfloat y) {
    std::lock_guard<std::mutex> lock(mutex);
    handle_touch(action, x, y);
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeSend(JNIEnv* env, jclass, jbyteArray text) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        std::string message;
        if (!core_ready) return java_string(env, "保存先を初期化できていません。");
        if (!read_text(env, text, message)) return java_string(env, "入力に無効な文字が含まれています。");
        if (!conversation_send(message.c_str())) { core_error(); return java_string(env, error); }
        error.clear();
        notice.clear();
        tab = 0;
        scroll_to_bottom = true;
        return java_string(env, "");
    } catch (const std::exception&) {
        return java_string(env, "送信に必要なメモリを確保できませんでした。");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_jp_tekewo_conversation_MainActivity_nativePause(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(mutex);
    touch = {};
    pending_scroll = 0;
    last_frame = {};
    if (context) {
        ImGui::SetCurrentContext(context);
        ImGui::GetIO().ClearInputKeys();
        ImGui::GetIO().ClearEventsQueue();
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeTranscript(JNIEnv* env, jclass, jboolean memories) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        std::string transcript;
        const int count = core_ready ? (memories ? conversation_memory_count() : conversation_message_count()) : 0;
        for (int i = 0; i < count; ++i) {
            if (i != 0) transcript += "\n\n";
            transcript += memories ? "記憶 " + std::to_string(i + 1) + "\n" : "あなた\n";
            const char* text = memories ? conversation_memory_text(i) : conversation_message_text(i);
            if (text) transcript += text;
        }
        return java_string(env, transcript);
    } catch (const std::exception&) {
        return java_string(env, "表示に必要なメモリを確保できませんでした。");
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeMessageTexts(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(mutex);
    jclass string_class = env->FindClass("java/lang/String");
    if (!string_class) return nullptr;
    const int count = core_ready ? conversation_message_count() : 0;
    jobjectArray result = env->NewObjectArray(count, string_class, nullptr);
    env->DeleteLocalRef(string_class);
    if (!result) return nullptr;
    for (int i = 0; i < count; ++i) {
        const char* text = conversation_message_text(i);
        jstring value = java_string(env, text ? text : "");
        if (!value) { env->DeleteLocalRef(result); return nullptr; }
        env->SetObjectArrayElement(result, i, value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) { env->DeleteLocalRef(result); return nullptr; }
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeRemember(JNIEnv* env, jclass, jint index) {
    std::lock_guard<std::mutex> lock(mutex);
    if (!core_ready) return java_string(env, "保存先を初期化できていません。");
    return java_string(env, remember_message(index) ? "" : error);
}
#endif
