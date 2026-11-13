#include "imgui_internal.h"

#include <cassert>
#include <fstream>
#include <iterator>

#define CONVERSATION_UI_TEST
#define ANDROID_LOG_ERROR 0
int __android_log_print(int, const char*, const char*, ...) { return 0; }
#include "../native_ui.cpp"

namespace {
std::vector<std::string> messages = {"日本語と絵文字 😀 の折り返し表示テスト。改行も保存されます。\n二行目です。"};
std::vector<std::string> memories;
int texture_requests = 0;

std::vector<unsigned char> read_font(const char* path) {
    std::ifstream stream(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(stream), {}};
}

void frame() {
    ImGui::NewFrame();
    draw_ui();
    ImGui::Render();
    assert(ImGui::GetDrawData()->TotalVtxCount > 0);
    for (ImTextureData* texture : ImGui::GetPlatformIO().Textures) {
        if (texture->Status == ImTextureStatus_WantCreate || texture->Status == ImTextureStatus_WantUpdates) {
            assert(texture->Pixels && texture->Width <= 4096 && texture->Height <= 4096);
            texture->SetTexID(1);
            texture->SetStatus(ImTextureStatus_OK);
            ++texture_requests;
        }
    }
}

void tap(float x, float y) {
    handle_touch(0, x, y);
    handle_touch(1, x, y);
    for (int i = 0; i < 5; ++i) frame();
}

void check_gestures() {
    Touch test;
    test.begin(10, 10, true);
    assert(test.move(11, 11, 8) == 0 && test.finish());
    test.begin(10, 10, true);
    assert(test.move(10, 30, 8) == 20 && !test.finish());
    test.begin(10, 10, false);
    assert(test.move(10, 30, 8) == 0 && !test.finish());
}
} // namespace

extern "C" {
bool conversation_init(const char*) { return true; }
bool conversation_send(const char* text) { messages.emplace_back(text); return true; }
int32_t conversation_message_count() { return static_cast<int32_t>(messages.size()); }
const char* conversation_message_text(int32_t index) { return messages.at(index).c_str(); }
bool conversation_remember(int32_t index) { memories.push_back(messages.at(index)); return true; }
int32_t conversation_memory_count() { return static_cast<int32_t>(memories.size()); }
const char* conversation_memory_text(int32_t index) { return memories.at(index).c_str(); }
const char* conversation_error() { return ""; }
void conversation_close() {}
}

int main() {
    check_gestures();
    japanese_font = read_font("android/assets/NotoSansJP-Regular.ttf");
    emoji_font = read_font("android/assets/NotoEmoji-Regular.ttf");
    assert(!japanese_font.empty() && !emoji_font.empty());
    core_ready = true;
    width = 1080;
    height = 1800;
    density = 3;

    for (int generation = 0; generation < 2; ++generation) {
        context = ImGui::CreateContext();
        ImGuiIO& io = ImGui::GetIO();
        io.IniFilename = nullptr;
        io.DisplaySize = ImVec2(static_cast<float>(width), static_cast<float>(height));
        io.DeltaTime = 1.0f / 60;
        io.ConfigFlags |= ImGuiConfigFlags_IsTouchScreen;
        io.BackendFlags |= ImGuiBackendFlags_RendererHasTextures;
        ImFontConfig config;
        config.FontDataOwnedByAtlas = false;
        ImFont* font = io.Fonts->AddFontFromMemoryTTF(japanese_font.data(), static_cast<int>(japanese_font.size()), 17, &config);
        assert(font);
        config.MergeMode = true;
        assert(io.Fonts->AddFontFromMemoryTTF(emoji_font.data(), static_cast<int>(emoji_font.size()), 17, &config));
        apply_style();
        apply_style();
        assert(ImGui::GetStyle().WindowPadding.x == 48);
        ImFontBaked* baked = font->GetFontBaked(51);
        assert(baked->FindGlyphNoFallback(0x3042));
        assert(baked->FindGlyphNoFallback(0x1f600));
        frame();
        frame();
        // The transcript uses the full surface; headers/tabs must not take space.
        assert(list_min.y <= 48 && list_max.y >= height - 48);

        if (generation == 0) {
            for (int i = 0; i < 30; ++i) messages.push_back(messages.front());
            scroll_to_bottom = true;
            frame();
            frame();
            ImGuiWindow* child = nullptr;
            for (ImGuiWindow* window : ImGui::GetCurrentContext()->Windows)
                if (window->Active && window->ParentWindow && std::string(window->ParentWindow->Name) == "Conversation") child = window;
            assert(child && child->ScrollMax.y > 0);
            const float old_scroll = child->Scroll.y;
            handle_touch(0, 200, list_min.y + 100);
            handle_touch(2, 200, list_min.y + 400);
            handle_touch(1, 200, list_min.y + 400);
            assert(ImGui::GetCurrentContext()->InputEventsQueue.empty());
            frame();
            frame();
            assert(child->Scroll.y < old_scroll && memories.empty());
            const int old_requests = texture_requests;
            assert(font->GetFontBaked(51)->FindGlyphNoFallback(0x9be8));
            frame();
            assert(texture_requests > old_requests);
        }
        assert(messages.size() == 31);
        for (ImTextureData* texture : ImGui::GetPlatformIO().Textures) {
            texture->SetTexID(ImTextureID_Invalid);
            texture->SetStatus(ImTextureStatus_Destroyed);
        }
        io.BackendFlags &= ~ImGuiBackendFlags_RendererHasTextures;
        ImGui::DestroyContext(context);
        context = nullptr;
    }
    std::puts("PASS: Japanese+emoji, dynamic texture requests, tap/swipe, scroll, density reset, context recreation");
}
