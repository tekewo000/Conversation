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
int text_requests = 0;
int cache_first = 0;
int cache_count = 0;
std::string opened_path;
std::string mock_error;
int init_calls = 0;
int import_calls = 0;
int64_t archive_cursor = -1;
bool archive_fails = false;

std::vector<unsigned char> read_font(const char* path) {
    std::ifstream stream(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(stream), {}};
}

void frame() {
    text_requests = 0;
    ImGui::NewFrame();
    draw_ui();
    ImGui::Render();
    assert(ImGui::GetDrawData()->TotalVtxCount > 0);
    assert(text_requests <= 80);
    for (ImTextureData* texture : ImGui::GetPlatformIO().Textures) {
        if (texture->Status == ImTextureStatus_WantCreate || texture->Status == ImTextureStatus_WantUpdates) {
            assert(texture->Pixels && texture->Width <= 4096 && texture->Height <= 4096);
            texture->SetTexID(1);
            texture->SetStatus(ImTextureStatus_OK);
            ++texture_requests;
        }
    }
}

ImGuiWindow* chat_window() {
    for (ImGuiWindow* window : ImGui::GetCurrentContext()->Windows)
        if (window->Active && window->ParentWindow && std::string(window->ParentWindow->Name) == "Conversation") return window;
    assert(false);
    return nullptr;
}

float message_y(int index) {
    for (const auto& p : message_positions) if (p.index == index) return p.y;
    assert(false);
    return 0;
}

void check_pages_and_streaming() {
    const auto short_history = messages;
    messages.clear();
    for (int i = 0; i < 20000; ++i) messages.push_back("message-" + std::to_string(i));
    following_bottom = true;
    history_changed();
    frame(); frame();
    assert(page_first == 19920 && page_count == 80);
    assert(message_positions.size() == 80 && cache_first == page_first);
    ImGuiWindow* child = chat_window();
    assert(child->ScrollMax.y > 0 && !child->ScrollbarX && !child->ScrollbarY);
    assert(child->Scroll.y >= child->ScrollMax.y - 1);
    // Both roles are real messages with distinct left/right bubble placement.
    float assistant_left = width, user_left = width;
    for (const ImDrawVert& vertex : child->DrawList->VtxBuffer) {
        if (vertex.col == IM_COL32(139, 90, 111, 255)) assistant_left = std::min(assistant_left, vertex.pos.x);
        if (vertex.col == IM_COL32(73, 110, 84, 255)) user_left = std::min(user_left, vertex.pos.x);
    }
    assert(assistant_left < width / 3 && user_left > assistant_left + 20);
    set_streaming("応答を表示中 😀");
    frame(); frame();
    assert(messages.size() == 20000 && message_positions.size() == 81);
    assert(child->Scroll.y >= child->ScrollMax.y - 1);
    ImGui::SetScrollY(child, child->ScrollMax.y / 2);
    frame(); frame();
    const float reading_scroll = child->Scroll.y;
    assert(!following_bottom);
    set_streaming("応答を表示中 😀\n続きを読むときも位置を維持します。\n三行目");
    frame(); frame();
    assert(message_positions.size() == 81 && std::abs(child->Scroll.y - reading_scroll) < 2);
    set_streaming("");
    frame(); frame();
    // Reach the old edge, then request another swipe to reveal the preceding page.
    ImGui::SetScrollY(child, 0);
    frame(); frame();
    const int overlap = page_first;
    const float old_y = message_y(overlap);
    pending_scroll = 100;
    frame(); frame();
    assert(page_first == 19880 && page_count == 80);
    assert(std::abs(message_y(overlap) - old_y) < 2);
    assert(child->Scroll.y > 0 && !following_bottom);
    const int older_page = page_first;
    const float older_scroll = child->Scroll.y;
    const std::string transcript = recent_transcript();
    assert(transcript.find("message-19980") == 0 && transcript.find("message-19999") != std::string::npos);
    assert(cache_first == older_page && cache_count == page_count);
    frame();
    assert(page_first == older_page && std::abs(child->Scroll.y - older_scroll) < 2);
    history_changed();
    set_streaming("next reply");
    frame(); frame();
    assert(page_first == older_page && message_positions.size() == 80);
    assert(std::abs(child->Scroll.y - older_scroll) < 2);
    // The newer edge restores the overlapping transcript rather than jumping to the latest response.
    set_streaming("");
    ImGui::SetScrollY(child, child->ScrollMax.y);
    frame(); frame();
    const int bottom_overlap = message_positions.back().index;
    const float bottom_y = message_y(bottom_overlap);
    pending_scroll = -100;
    frame(); frame();
    assert(page_first > older_page);
    assert(std::abs(message_y(bottom_overlap) - bottom_y) < 2);
    following_bottom = true;
    history_changed();
    frame(); frame();
    jump_to_message(19971);
    frame();
    assert(!following_bottom);
    set_streaming("navigation must stay on the selected source");
    frame(); frame();
    assert(std::abs(message_y(19970) - list_min.y - 16 * density) < 2);
    set_streaming("");
    jump_to_message(500);
    frame(); frame();
    assert(page_first <= 499 && page_first + page_count > 499);
    assert(std::abs(message_y(499) - list_min.y - 16 * density) < 2);
    const int jumped_page = page_first;
    jump_to_message(0); jump_to_message(999999);
    assert(page_first == jumped_page);
    // A new message returns from older history to the latest page and resumes follow.
    jump_to_message(501);
    messages.push_back("new reply");
    history_changed();
    assert(following_bottom && scroll_to_bottom && anchor_index == -1);
    set_streaming("latest reply");
    frame(); frame();
    assert(page_first == static_cast<int>(messages.size()) - kPageSize);
    assert(message_positions.size() == 81);
    assert(child->Scroll.y >= child->ScrollMax.y - 1);
    set_streaming("latest reply\nmore text\nlast line");
    frame(); frame();
    assert(child->Scroll.y >= child->ScrollMax.y - 1);
    set_streaming("");
    messages = short_history;
    following_bottom = true;
    history_changed();
    frame(); frame();
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

void check_person_switching() {
    core_ready = conversation_init("previous-person");
    storage_path = "previous-person";
    page_first = 0; page_count = 1; page_total = static_cast<int>(messages.size());
    streaming = "previous partial response";
    message_positions = {{0, 12, 30}};
    anchor_index = 0; anchor_offset = 7;
    following_bottom = false; scroll_to_bottom = false; pending_scroll = 8;
    touch.begin(10, 12, true);
    const int before = init_calls;
    assert(switch_person("previous-person", ""));
    assert(init_calls == before && streaming == "previous partial response" && anchor_index == 0);
    assert(!switch_person("bad-path", ""));
    assert(error == "cannot open requested path" && core_ready);
    assert(opened_path == "previous-person" && storage_path == "previous-person");
    assert(page_first == 0 && page_count == 1 && cache_first == 0 && cache_count == 1);
    assert(streaming == "previous partial response" && message_positions.size() == 1);
    assert(anchor_index == 0 && anchor_offset == 7 && pending_scroll == 8 && touch.down);
    assert(!following_bottom && !scroll_to_bottom);
    assert(!switch_person("new-person", "bad-import"));
    assert(error == "invalid import" && core_ready && opened_path == "previous-person");
    assert(page_first == 0 && streaming == "previous partial response" && anchor_index == 0);
    assert(switch_person("new-person", "valid-import"));
    assert(storage_path == "new-person" && opened_path == "new-person" && error.empty());
    assert(page_first == -1 && page_count == 0 && page_total == 0);
    assert(streaming.empty() && message_positions.empty() && anchor_index == -1 && anchor_offset == 0);
    assert(pending_scroll == 0 && !touch.down && following_bottom && scroll_to_bottom);
    const int before_no_import = import_calls;
    assert(switch_person("another-person", ""));
    assert(import_calls == before_no_import && opened_path == "another-person");
    assert(archive_delta(17) == "{\"records\":[]}" && archive_cursor == 17 && error.empty());
    archive_fails = true;
    assert(archive_delta(17).empty() && error == "archive failed");
    archive_fails = false;
    core_ready = false;
    assert(archive_delta(17).empty() && !error.empty());
    core_ready = true;
}
} // namespace

extern "C" {
bool conversation_init(const char* path) {
    ++init_calls; cache_first = -1; cache_count = 0;
    if (std::string(path) == "bad-path") { mock_error = "cannot open requested path"; return false; }
    opened_path = path; mock_error.clear(); return true;
}
bool conversation_import_person(const char* payload) {
    ++import_calls;
    if (std::string(payload) == "bad-import") { mock_error = "invalid import"; return false; }
    return true;
}
const char* conversation_archive_delta(int64_t after_cursor) {
    archive_cursor = after_cursor;
    if (archive_fails) { mock_error = "archive failed"; return nullptr; }
    return "{\"records\":[]}";
}
bool conversation_send(const char* text) { messages.emplace_back(text); return true; }
int32_t conversation_message_count() { return static_cast<int32_t>(messages.size()); }
bool conversation_load_window(int32_t first, int32_t limit) {
    assert(first >= 0 && limit >= 0 && limit <= 80);
    cache_first = first;
    cache_count = std::min(limit, static_cast<int32_t>(messages.size()) - first);
    return true;
}
const char* conversation_message_text(int32_t index) {
    assert(index >= cache_first && index < cache_first + cache_count);
    ++text_requests;
    return messages.at(index).c_str();
}
const char* conversation_message_role(int32_t index) {
    assert(index >= cache_first && index < cache_first + cache_count);
    return index % 2 == 0 ? "user" : "assistant";
}
int64_t conversation_message_id(int32_t index) { return index + 1; }
bool conversation_remember(int32_t index) { memories.push_back(messages.at(index)); return true; }
int32_t conversation_memory_count() { return static_cast<int32_t>(memories.size()); }
const char* conversation_memory_text(int32_t index) { return memories.at(index).c_str(); }
const char* conversation_error() { return mock_error.c_str(); }
void conversation_close() { opened_path.clear(); cache_first = -1; cache_count = 0; mock_error.clear(); }
}

int main() {
    check_gestures();
    check_person_switching();
    core_ready = true;
    const auto original = messages;
    messages.clear();
    for (int i = 0; i < 30; ++i) messages.push_back("message-" + std::to_string(i));
    const std::string recent = recent_transcript();
    assert(recent.find("message-9\n") == std::string::npos);
    assert(recent.find("message-10") == 0 && recent.find("message-29") != std::string::npos);
    std::string japanese;
    for (int i = 0; i < 10000; ++i) japanese += "あ";
    messages = {japanese};
    const std::string bounded = recent_transcript();
    assert(bounded.size() <= 16384 && bounded.size() % 3 == 0 && bounded.find("あ") == 0);
    messages.clear();
    assert(recent_transcript().empty());
    messages = original;
    japanese_font = read_font("android/assets/NotoSansJP-Regular.ttf");
    emoji_font = read_font("android/assets/NotoEmoji-Regular.ttf");
    assert(!japanese_font.empty() && !emoji_font.empty());
    core_ready = true;
    width = 1080;
    height = 1800;
    density = 3;

    float restored_source_y = 0;
    for (int generation = 0; generation < 3; ++generation) {
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
        assert(ImGui::GetStyle().FontSizeBase == (generation == 0 ? 60 : 72));
        assert(message_gap_dp == (generation == 0 ? 12 : 20));
        ImFontBaked* baked = font->GetFontBaked(51);
        assert(baked->FindGlyphNoFallback(0x3042));
        assert(baked->FindGlyphNoFallback(0x1f600));
        frame();
        frame();
        if (generation == 1) assert(chat_window()->Scroll.y >= chat_window()->ScrollMax.y - 1);
        if (generation == 2) {
            assert(!following_bottom && std::abs(message_y(9) - restored_source_y) < 2);
        }
        check_pages_and_streaming();
        // The transcript uses the full surface; headers/tabs must not take space.
        assert(list_min.y <= 48 && list_max.y >= height - 48);

        if (generation == 0) {
            for (int i = 0; i < 30; ++i) messages.push_back(messages.front());
            following_bottom = true;
            history_changed();
            frame();
            frame();
            ImGuiWindow* child = chat_window();
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
        set_typography(72, 20);
        assert(ImGui::GetStyle().FontSizeBase == 72 && message_gap_dp == 20);
        set_typography(NAN, NAN);
        assert(ImGui::GetStyle().FontSizeBase == 72 && message_gap_dp == 20);
        set_typography(72, 100);
        assert(message_gap_dp == 24);
        set_typography(72, 20);
        following_bottom = true;
        history_changed();
        frame(); frame();
        if (generation == 1) {
            jump_to_message(10);
            frame(); frame();
            restored_source_y = message_y(9);
        }
        for (ImTextureData* texture : ImGui::GetPlatformIO().Textures) {
            texture->SetTexID(ImTextureID_Invalid);
            texture->SetStatus(ImTextureStatus_Destroyed);
        }
        io.BackendFlags &= ~ImGuiBackendFlags_RendererHasTextures;
        ImGui::DestroyContext(context);
        context = nullptr;
    }
    std::puts("PASS: person switch/import rollback, view reset, archive delta, 20k history bounded to 80, role layout, anchored paging, streaming/follow, accessibility, Japanese+emoji, swipe, density, context recreation");
}
