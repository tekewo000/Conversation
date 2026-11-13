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
#include <cstring>
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
bool following_bottom = true;
constexpr int kPageSize = 80;
constexpr int kPageStep = 40;
int page_first = -1;
int page_count = 0;
int page_total = 0;
std::string streaming;
struct MessagePosition { int index; float y; float height; };
std::vector<MessagePosition> message_positions;
int anchor_index = -1;
float anchor_offset = 0;
int width = 1;
int height = 1;
float density = 1.0f;
float typography_pixels = 0.0f;
float message_gap_dp = 12.0f;
float pending_scroll = 0.0f;
ImVec2 list_min;
ImVec2 list_max;
std::vector<unsigned char> japanese_font;
std::vector<unsigned char> emoji_font;
std::string storage_path;
std::string error;
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
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Core operation failed");
}

bool load_page(int first) {
    const int total = core_ready ? conversation_message_count() : 0;
    first = std::clamp(first, 0, std::max(0, total - 1));
    const int count = std::min(kPageSize, total - first);
    if (core_ready && count > 0 && !conversation_load_window(first, count)) { core_error(); return false; }
    page_first = first;
    page_count = count;
    page_total = total;
    return true;
}

bool switch_person(std::string requested_path, const std::string& payload) {
    if (requested_path.empty() || requested_path.find('\0') != std::string::npos || payload.find('\0') != std::string::npos) {
        error = "人物の保存先・資料が無効です。";
        return false;
    }
    if (core_ready && requested_path == storage_path && payload.empty()) { error.clear(); return true; }
    const bool had_previous = core_ready;
    auto restore_previous = [&] {
        conversation_close();
        core_ready = had_previous && conversation_init(storage_path.c_str());
        if (core_ready && page_first >= 0 && page_count > 0)
            conversation_load_window(page_first, page_count);
    };
    conversation_close();
    core_ready = false;
    try {
        if (conversation_init(requested_path.c_str()) &&
                (payload.empty() || conversation_import_person(payload.c_str()))) {
            storage_path.swap(requested_path);
            core_ready = true;
            page_first = -1; page_count = 0; page_total = 0;
            streaming.clear(); message_positions.clear();
            anchor_index = -1; anchor_offset = 0;
            pending_scroll = 0; touch = {};
            list_min = {}; list_max = {};
            following_bottom = true; scroll_to_bottom = true;
            if (context) {
                ImGui::SetCurrentContext(context);
                ImGui::GetIO().ClearInputKeys();
                ImGui::GetIO().ClearEventsQueue();
            }
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

void history_changed() {
    const int total = core_ready ? conversation_message_count() : 0;
    if (page_first < 0 || following_bottom) {
        if (load_page(std::max(0, total - kPageSize))) scroll_to_bottom = true;
    } else load_page(page_first);
}

void jump_to_message(int64_t id) {
    const int total = core_ready ? conversation_message_count() : 0;
    if (id <= 0 || id > total) return;
    // shortcut: IDs must be contiguous, add an ID lookup if deletion is introduced.
    const int index = static_cast<int>(id - 1);
    if (!load_page(std::min(std::max(0, index - kPageStep), std::max(0, total - kPageSize)))) return;
    if (conversation_message_id(index) != id) return;
    anchor_index = index;
    anchor_offset = 16 * density;
    scroll_to_bottom = false;
    following_bottom = false;
}

void set_streaming(std::string text) {
    streaming = std::move(text);
    if (following_bottom) scroll_to_bottom = true;
}

std::string recent_transcript() {
    std::string transcript;
    const int count = core_ready ? conversation_message_count() : 0;
    if (!core_ready || count == 0 || !conversation_load_window(std::max(0, count - 20), std::min(20, count))) return transcript;
    try {
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
    } catch (...) {
        load_page(page_first < 0 ? std::max(0, count - kPageSize) : page_first);
        throw;
    }
    // Accessibility reads the latest messages without replacing the visible history page.
    load_page(page_first < 0 ? std::max(0, count - kPageSize) : page_first);
    return transcript;
}

void apply_style() {
    ImGui::GetStyle() = ImGuiStyle();
    ImGui::StyleColorsDark();
    ImGuiStyle& style = ImGui::GetStyle();
    style.WindowPadding = ImVec2(16, 16);
    style.FramePadding = ImVec2(12, 10);
    style.ItemSpacing = ImVec2(10, 0);
    style.ChildRounding = 0;
    style.FrameRounding = 8;
    style.ScrollbarSize = 12;
    style.WindowBorderSize = 0;
    style.Colors[ImGuiCol_Text] = ImVec4(0.90f, 0.93f, 0.95f, 1);
    style.Colors[ImGuiCol_TextDisabled] = ImVec4(0.61f, 0.67f, 0.71f, 1);
    style.Colors[ImGuiCol_WindowBg] = ImVec4(0.075f, 0.09f, 0.11f, 1);
    style.Colors[ImGuiCol_ChildBg] = ImVec4(0.075f, 0.09f, 0.11f, 1);
    style.Colors[ImGuiCol_Border] = ImVec4(0.23f, 0.28f, 0.32f, 1);
    style.Colors[ImGuiCol_Button] = ImVec4(0.16f, 0.20f, 0.23f, 1);
    style.Colors[ImGuiCol_ButtonHovered] = ImVec4(0.22f, 0.29f, 0.32f, 1);
    style.Colors[ImGuiCol_ButtonActive] = ImVec4(0.25f, 0.36f, 0.34f, 1);
    style.ScaleAllSizes(density);
    style.FontSizeBase = typography_pixels > 0 ? typography_pixels : 20.0f * density;
}

void set_typography(float pixels, float gap_dp) {
    if (std::isfinite(pixels) && pixels >= 8 && pixels <= 320) typography_pixels = pixels;
    if (std::isfinite(gap_dp)) message_gap_dp = std::clamp(gap_dp, 8.0f, 24.0f);
    if (context) { ImGui::SetCurrentContext(context); apply_style(); }
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
    ImGui::BeginChild("chat", ImVec2(0, 0), ImGuiChildFlags_None, ImGuiWindowFlags_NoScrollbar);
    if (ImGui::IsWindowAppearing()) {
        if (following_bottom) scroll_to_bottom = true;
        else if (anchor_index < 0) {
            for (const auto& p : message_positions) {
                if (p.y + p.height > list_min.y) {
                    anchor_index = p.index;
                    anchor_offset = p.y - list_min.y;
                    break;
                }
            }
        }
    }
    list_min = ImGui::GetWindowPos();
    list_max = ImVec2(list_min.x + ImGui::GetWindowSize().x, list_min.y + ImGui::GetWindowSize().y);
    if (page_first < 0 || (core_ready && page_total != conversation_message_count())) history_changed();
    const float old_scroll = ImGui::GetScrollY();
    const float max_scroll = ImGui::GetScrollMaxY();
    const float wheel = ImGui::GetIO().MouseWheel * ImGui::GetFontSize() * 5;
    const float movement = pending_scroll != 0 ? pending_scroll : wheel;
    pending_scroll = 0;
    if (movement != 0) {
        scroll_to_bottom = false;
        const float target = std::clamp(old_scroll - movement, 0.0f, max_scroll);
        const bool older = movement > 0 && target <= 24 * d && page_first > 0;
        const bool newer = movement < 0 && target >= max_scroll - 24 * d && page_first + page_count < page_total;
        if ((older || newer) && !message_positions.empty()) {
            const auto visible = std::find_if(message_positions.begin(), message_positions.end(),
                [&](const MessagePosition& p) { return p.y + p.height + old_scroll - target > list_min.y; });
            const MessagePosition& anchor = visible == message_positions.end() ? message_positions.back() : *visible;
            const int first = older ? std::max(0, page_first - kPageStep) :
                std::min(page_first + kPageStep, std::max(0, page_total - kPageSize));
            if (load_page(first)) {
                anchor_index = anchor.index;
                anchor_offset = anchor.y - list_min.y + old_scroll - target;
            }
        } else ImGui::SetScrollY(target);
        following_bottom = page_first + page_count >= page_total && target >= max_scroll - 2 * d && movement < 0;
    }
    const float available = ImGui::GetContentRegionAvail().x;
    const float padding = 12 * d;
    const float max_width = std::max(1.0f, std::min(available * 0.86f, 600 * d));
    const float wrap = std::max(1.0f, max_width - padding * 2);
    message_positions.clear();
    bool restored_anchor = false;
    const bool show_stream = !streaming.empty() && page_first + page_count >= page_total;
    for (int offset = 0; offset < page_count + (show_stream ? 1 : 0); ++offset) {
        const int index = page_first + offset;
        const bool provisional = offset == page_count;
        const char* text = provisional ? streaming.c_str() : conversation_message_text(index);
        const char* role = provisional ? "assistant" : conversation_message_role(index);
        if (!text) continue;
        const bool user = role && std::strcmp(role, "user") == 0;
        const ImVec2 size = ImGui::CalcTextSize(text, nullptr, false, wrap);
        const float bubble_width = std::min(max_width, size.x + padding * 2);
        const float bubble_height = size.y + 10 * d * 2;
        const ImVec2 cursor = ImGui::GetCursorScreenPos();
        const ImVec2 origin(cursor.x + (user ? available - bubble_width : 0), cursor.y);
        message_positions.push_back({index, cursor.y, bubble_height});
        if (anchor_index == index) {
            ImGui::SetScrollY(old_scroll + cursor.y - list_min.y - anchor_offset);
            anchor_index = -1;
            restored_anchor = true;
        }
        ImDrawList* draw = ImGui::GetWindowDrawList();
        draw->AddRectFilled(origin, ImVec2(origin.x + bubble_width, origin.y + bubble_height),
                            user ? IM_COL32(222, 240, 223, 255) : IM_COL32(246, 222, 231, 255), 16 * d);
        draw->AddText(ImGui::GetFont(), ImGui::GetFontSize(),
                      ImVec2(origin.x + padding, origin.y + 10 * d),
                      IM_COL32(32, 38, 42, 255), text, nullptr, wrap);
        ImGui::Dummy(ImVec2(available, bubble_height + message_gap_dp * d));
    }
    if (scroll_to_bottom) {
        ImGui::SetScrollHereY(1);
        scroll_to_bottom = false;
        following_bottom = true;
    } else if (movement == 0 && anchor_index < 0 && !restored_anchor) {
        following_bottom = page_first + page_count >= page_total && old_scroll >= max_scroll - 2 * d;
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
        if (core_ready) return java_string(env, switch_person(std::move(requested_path), "") ? "" : error);
        if (!read_bytes(env, jp_font, japanese_font) || japanese_font.size() < 12 ||
            !read_bytes(env, symbols_font, emoji_font) || emoji_font.size() < 12)
            return java_string(env, "日本語・絵文字フォントを読み込めませんでした。");
        return java_string(env, switch_person(std::move(requested_path), "") ? "" : error);
    } catch (const std::exception&) {
        return java_string(env, "初期化に必要なメモリを確保できませんでした。");
    }
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
Java_jp_tekewo_conversation_MainActivity_nativeSetTypography(JNIEnv*, jclass, jfloat pixels, jfloat gap_dp) {
    std::lock_guard<std::mutex> lock(mutex);
    set_typography(pixels, gap_dp);
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
        history_changed();
        return java_string(env, "");
    } catch (const std::exception&) {
        return java_string(env, "送信に必要なメモリを確保できませんでした。");
    }
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
        history_changed();
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

extern "C" JNIEXPORT void JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeSetStreaming(JNIEnv* env, jclass, jbyteArray text) {
    std::lock_guard<std::mutex> lock(mutex);
    try {
        std::string message;
        if (!read_text(env, text, message)) { error = "応答に無効な文字が含まれています。"; return; }
        set_streaming(std::move(message));
    } catch (const std::exception&) { error = "応答表示に必要なメモリを確保できませんでした。"; }
}

extern "C" JNIEXPORT void JNICALL
Java_jp_tekewo_conversation_MainActivity_nativeJumpToMessage(JNIEnv*, jclass, jlong id) {
    std::lock_guard<std::mutex> lock(mutex);
    try { jump_to_message(id); }
    catch (const std::exception&) { error = "会話を読み込めませんでした。"; }
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
Java_jp_tekewo_conversation_MainActivity_nativeRecentTranscript(JNIEnv* env, jclass) {
    std::lock_guard<std::mutex> lock(mutex);
    try { return java_string(env, recent_transcript()); }
    catch (const std::exception&) { return java_string(env, "表示に必要なメモリを確保できませんでした。"); }
}
#endif
