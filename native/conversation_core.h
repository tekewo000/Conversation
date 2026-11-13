#pragma once
#include <stdbool.h>
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif
// Serialize all calls. Borrowed JSON/text buffers survive until the next call
// returning JSON; window strings survive until load_window/add/init/close.
bool conversation_init(const char* storage_dir);
bool conversation_send(const char* utf8);
int64_t conversation_add_message(const char* role, const char* utf8);
int32_t conversation_message_count(void);
// Absolute zero-based indexes; load at most 80 rows before using getters.
bool conversation_load_window(int32_t first, int32_t limit);
const char* conversation_message_text(int32_t index);
const char* conversation_message_role(int32_t index);
int64_t conversation_message_id(int32_t index);
// Bounded UTF-8 JSON. Queries are literal keywords (at most 256 bytes).
const char* conversation_context(const char* query);
// {} means no pending job; long messages are explicitly marked truncated.
const char* conversation_pending_job(void);
bool conversation_apply_job(int64_t id, const char* extraction_json);
// {} means no scheduled persona review. Apply {changes:[{field,text,reason,source_ids}]}.
const char* conversation_pending_persona(void);
bool conversation_apply_persona(int64_t through_id, const char* payload_json);
// {memories:[...]} includes source snippets, IDs, versions, and current flag.
const char* conversation_memories(const char* query);
// Fresh person DB only; retries with the same UUID and source SHA256 are idempotent.
// {person_id,name,profile,source_md,source_sha256,seeds:[typed document-sourced memories]}
// Limits: source 2 MiB, profile 128 KiB, 2048 seeds, seed text 16 KiB, JSON 32 MiB.
bool conversation_import_person(const char* payload_json);
// <=20 MiB. Immutable, contiguous SQLite transaction records after the cursor.
// {format:1,person,from_cursor,through_cursor,has_more,records:[{cursor,events}]}
const char* conversation_archive_delta(int64_t after_cursor);
// Replay into a fresh or matching DB; rejects conflicting owners/cursors atomically.
bool conversation_apply_archive(const char* delta_json);
bool conversation_export(const char* output_dir);
// Legacy read compatibility. Pin modification is retired (returns false).
bool conversation_remember(int32_t index);
int32_t conversation_memory_count(void);
const char* conversation_memory_text(int32_t index);
const char* conversation_error(void);
void conversation_close(void);
#ifdef __cplusplus
}
#endif
