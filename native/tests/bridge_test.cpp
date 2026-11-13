#define CONVERSATION_BRIDGE_TEST
#include "../native_bridge.cpp"
#include <cassert>
#include <cstdio>

namespace {
std::vector<std::string> messages;
int text_requests = 0;
int cache_first = 0;
int cache_count = 0;
std::string opened_path;
std::string mock_error;
int init_calls = 0;
int import_calls = 0;
int64_t archive_cursor = -1;
bool archive_fails = false;
bool window_fails = false;

void check_person_switching() {
    assert(switch_person("previous-person", ""));
    const int before = init_calls;
    assert(switch_person("previous-person", ""));
    assert(init_calls == before);
    assert(!switch_person("bad-path", ""));
    assert(error == "cannot open requested path" && core_ready);
    assert(opened_path == "previous-person" && storage_path == "previous-person");
    assert(!switch_person("new-person", "bad-import"));
    assert(error == "invalid import" && core_ready && opened_path == "previous-person");
    assert(switch_person("new-person", "valid-import"));
    assert(storage_path == "new-person" && opened_path == "new-person" && error.empty());
    const int before_no_import = import_calls;
    assert(switch_person("another-person", ""));
    assert(import_calls == before_no_import);
    assert(archive_delta(17) == "{\"records\":[]}" && archive_cursor == 17 && error.empty());
    archive_fails = true;
    assert(archive_delta(17).empty() && error == "archive failed");
    archive_fails = false;
    assert(!switch_person("", "valid-import") && core_ready);
    assert(!switch_person(std::string("bad\0path", 8), "") && core_ready);
    assert(switch_person("", ""));
    assert(!core_ready && storage_path.empty() && error.empty());
    assert(message_snapshot(-1) == "{\"first\":0,\"total\":0,\"messages\":[]}");
    assert(archive_delta(17).empty() && !error.empty());
    assert(!switch_person("bad-path", "") && !core_ready && storage_path.empty());
    assert(switch_person("another-person", ""));
}

void check_snapshots() {
    messages = {"日本語 😀\n\"quote\"\\\t\r\x01"};
    assert(message_snapshot(-1) == "{\"first\":0,\"total\":1,\"messages\":[{\"id\":1,\"role\":\"user\",\"text\":\"日本語 😀\\u000a\\\"quote\\\"\\\\\\u0009\\u000d\\u0001\"}]}");
    messages.clear();
    for (int i = 0; i < 20000; ++i) messages.push_back("message-" + std::to_string(i));
    text_requests = 0;
    std::string latest = message_snapshot(-1);
    assert(latest.find("{\"first\":19920,\"total\":20000,") == 0);
    assert(latest.find("message-19919") == std::string::npos);
    assert(latest.find("message-19999") != std::string::npos);
    assert(text_requests == 80 && cache_first == 19920 && cache_count == 80);
    assert(latest.find("\"role\":\"assistant\"") != std::string::npos);
    text_requests = 0;
    const std::string older = message_snapshot(19880);
    assert(older.find("{\"first\":19880,") == 0 && text_requests == 80);
    assert(older.find("message-19959") != std::string::npos);
    assert(older.find("message-19960") == std::string::npos);
    assert(message_snapshot(999999).find("{\"first\":19999,") == 0);
    assert(message_snapshot(-50).find("{\"first\":0,") == 0);
    messages.push_back("new response");
    assert(message_snapshot(-1).find("{\"first\":19921,\"total\":20001,") == 0);
    assert(message_snapshot(19880).find("{\"first\":19880,") == 0);
    window_fails = true;
    assert(message_snapshot(-1).empty() && error == "window failed");
    window_fails = false;
    assert(!message_snapshot(-1).empty() && error.empty());
    messages.clear();
    assert(message_snapshot(-1) == "{\"first\":0,\"total\":0,\"messages\":[]}");
}

void check_transcript() {
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
int32_t conversation_message_count() { return static_cast<int32_t>(messages.size()); }
bool conversation_load_window(int32_t first, int32_t limit) {
    assert(first >= 0 && limit >= 0 && limit <= 80);
    if (window_fails) { mock_error = "window failed"; return false; }
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
const char* conversation_error() { return mock_error.c_str(); }
void conversation_close() { opened_path.clear(); cache_first = -1; cache_count = 0; mock_error.clear(); }
}

int main() {
    check_person_switching();
    check_snapshots();
    check_transcript();
    std::puts("PASS: person switch/import rollback, archive delta, JSON escaping and Unicode, 20k history bounded to 80, paging, read errors, recent transcript bounds");
}
